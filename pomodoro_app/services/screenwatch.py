"""Vigilancia de la pantalla de la compu contra recaídas (lo mismo que ScreenWatch en el teléfono).

La extensión de GNOME «vigía» (`gnome-extension/`) deja una captura reducida de la ventana al frente
cuando es de riesgo (navegadores…) en $XDG_RUNTIME_DIR/pomodoro-vigia. Aquí se manda al servidor, que la
revisa con NudeNet en memoria, y se borra. Según lo que vea:
  - explícito → cierra la ventana, bloquea la sesión, cancela los desbloqueos del teléfono y avisa al bot;
  - sugerente → aviso; a la tercera en 2 min, lo mismo que explícito.
Sin `sync.json` (servidor) no hace nada.
"""
import json
import os
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.request

from ..paths import PROJECT_DIR
from .system import lock_session, notify

UUID = "vigia@mauriciojuanes.com"
EXT_SRC = os.path.join(PROJECT_DIR, "gnome-extension", UUID)
EXT_DST = os.path.expanduser(f"~/.local/share/gnome-shell/extensions/{UUID}")
SHOT_DIR = os.path.join(os.environ.get("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}"), "pomodoro-vigia")
SUGGESTIVE_LIMIT = 3
SUGGESTIVE_WINDOW = 120
COOLDOWN = 30


def _gsettings(*args):
    try:
        return subprocess.run(["gsettings", *args], capture_output=True, text=True, timeout=5).stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        return ""


def ensure_extension():
    """Instala (o actualiza) la extensión y la deja activada. En Wayland, una extensión nueva carga al
    volver a iniciar sesión; después de eso, si alguien la apaga, aquí se vuelve a encender."""
    if not os.path.isdir(EXT_SRC):
        return
    for name in os.listdir(EXT_SRC):
        src, dst = os.path.join(EXT_SRC, name), os.path.join(EXT_DST, name)
        try:
            with open(src, "rb") as a:
                new = a.read()
            with open(dst, "rb") as b:
                if b.read() == new:
                    continue
        except OSError:
            pass
        os.makedirs(EXT_DST, exist_ok=True)
        shutil.copyfile(src, dst)
    enabled = _gsettings("get", "org.gnome.shell", "enabled-extensions")
    if UUID not in enabled:
        items = [x.strip(" '") for x in enabled.strip("@as[]").split(",") if x.strip(" '")]
        _gsettings("set", "org.gnome.shell", "enabled-extensions", str(items + [UUID]))
    if _gsettings("get", "org.gnome.shell", "disable-user-extensions") == "true":
        _gsettings("set", "org.gnome.shell", "disable-user-extensions", "false")


class ScreenWatch:
    def __init__(self, app, settings):
        self.app = app
        self.settings = settings      # {"url", "token"} del servidor, o None
        self.last_ts = 0
        self.cooldown_until = 0
        self.suggestive = []
        self.last_ensure = 0
        if settings:
            threading.Thread(target=self._loop, daemon=True).start()

    def _req(self, method, path, data=None, ctype="application/json", timeout=15):
        req = urllib.request.Request(self.settings["url"].rstrip("/") + path, method=method, data=data,
                                     headers={"Authorization": "Bearer " + self.settings["token"],
                                              "Content-Type": ctype})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.load(r)

    # ---------- hilo aparte ----------
    def _loop(self):
        while True:
            try:
                if time.time() - self.last_ensure > 300:
                    self.last_ensure = time.time()
                    ensure_extension()
                self._check()
            except (OSError, ValueError, urllib.error.URLError):
                pass
            time.sleep(2)

    def _check(self):
        meta_path, shot_path = os.path.join(SHOT_DIR, "meta.json"), os.path.join(SHOT_DIR, "shot.jpg")
        try:
            with open(meta_path) as f:
                meta = json.load(f)
            with open(shot_path, "rb") as f:
                jpeg = f.read()
        except (OSError, ValueError):
            return
        os.remove(shot_path)   # no se queda en disco
        if meta.get("ts", 0) <= self.last_ts or time.time() < self.cooldown_until:
            return
        self.last_ts = meta["ts"]
        level = self._req("POST", "/api/screen", jpeg, "image/jpeg").get("level", "none")
        if level != "none":
            self.app.after(0, lambda: self._handle(level, meta.get("app") or "?", meta.get("title") or ""))

    # ---------- hilo de la interfaz ----------
    def _handle(self, level, app, title):
        name = app.split(".")[-1].capitalize()
        if level == "suggestive":
            now = time.time()
            self.suggestive = [t for t in self.suggestive if now - t < SUGGESTIVE_WINDOW] + [now]
            if len(self.suggestive) < SUGGESTIVE_LIMIT:
                notify(f"👀 Contenido sugerente en {name}", "Sal de ahí antes de que escale.", urgent=True)
                return
            self.suggestive = []
        self.cooldown_until = time.time() + COOLDOWN
        self._write_action("close")
        lock_session()
        notify(f"🚫 Contenido {'explícito' if level == 'explicit' else 'sugerente'} en {name}",
               "Cerré la ventana y bloqueé la sesión. Respira, levántate y sal del cuarto.", urgent=True)
        threading.Thread(target=self._report, args=(level, app, name), daemon=True).start()

    def _write_action(self, action):
        try:
            with open(os.path.join(SHOT_DIR, "action"), "w") as f:
                f.write(action)
        except OSError:
            pass

    def _report(self, level, app, name):
        """Avisa al bot (alerta) y cancela los desbloqueos vigentes del teléfono."""
        try:
            self._req("POST", "/api/alert", json.dumps({"source": "desktop", "level": level, "app": app,
                                                        "app_name": name, "action": "lock"}).encode())
            blocks = (self._req("GET", "/api/state?source=desktop") or {}).get("blocks")
            if blocks and blocks.get("relocks"):
                apps = set(blocks.get("blocked_apps") or []) | {r["pkg"] for r in blocks["relocks"]
                                                               if r.get("kind") == "app" and r.get("pkg")}
                blocks.update(relocks=[], blocked_apps=sorted(apps))
                self._req("PUT", "/api/blocks", json.dumps({"source": "desktop", "blocks": blocks}).encode())
        except (OSError, ValueError, urllib.error.URLError):
            pass
