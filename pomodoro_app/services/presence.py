"""Sensor de presencia: qué ventana tienes al frente y cuánto llevas sin tocar teclado ni mouse.

La fuente buena es la extensión «vigía» de GNOME (`focus.json` en $XDG_RUNTIME_DIR/pomodoro-vigia, cada
2 s): en Wayland es lo único que puede ver la ventana al frente. Si todavía no la carga GNOME (una
extensión actualizada carga al volver a iniciar sesión), solo se sabe el tiempo inactivo, por D-Bus.
Corre en un hilo aparte; la interfaz lee `latest` cuando quiere.
"""
import json
import os
import re
import subprocess
import threading
import time

from .screenwatch import SHOT_DIR

FOCUS_FILE = os.path.join(SHOT_DIR, "focus.json")
STALE_SEC = 10   # focus.json más viejo que esto: la extensión no está corriendo
ACTION_FILE = os.path.join(SHOT_DIR, "action")


def _dbus_idle():
    try:
        out = subprocess.run(["gdbus", "call", "--session", "--dest", "org.gnome.Mutter.IdleMonitor",
                              "--object-path", "/org/gnome/Mutter/IdleMonitor/Core",
                              "--method", "org.gnome.Mutter.IdleMonitor.GetIdletime"],
                             capture_output=True, text=True, timeout=3).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r"(\d+)", out)
    return int(m.group(1)) / 1000 if m else None


class Presence:
    def __init__(self):
        # {"ts", "idle" (s), "app", "title", "locked", "fullscreen", "window" (¿se sabe la ventana?)} o None
        self.latest = None
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        while True:
            try:
                self.latest = self._sample()
            except Exception:  # noqa: BLE001 — un sensor que falla no debe tumbar la app
                self.latest = None
            time.sleep(2)

    @staticmethod
    def _sample():
        now = time.time()
        try:
            with open(FOCUS_FILE) as f:
                d = json.load(f)
            if now - d.get("ts", 0) < STALE_SEC and d.get("idle_ms") is not None:
                app = d.get("app_id") or d.get("app") or ""
                return {"ts": now, "idle": d["idle_ms"] / 1000, "app": app.lower(), "title": d.get("title") or "",
                        "locked": bool(d.get("locked")), "fullscreen": bool(d.get("fullscreen")),
                        "window": True}
        except (OSError, ValueError):
            pass
        idle = _dbus_idle()
        if idle is None:
            return None
        return {"ts": now, "idle": idle, "app": "", "title": "", "locked": False, "fullscreen": False,
                "window": False}

    def fresh(self):
        s = self.latest
        return s if s and time.time() - s["ts"] < STALE_SEC else None

    @staticmethod
    def minimize_focused():
        """Le pide a la extensión que minimice la ventana al frente."""
        try:
            with open(ACTION_FILE, "w") as f:
                f.write("minimize")
        except OSError:
            pass
