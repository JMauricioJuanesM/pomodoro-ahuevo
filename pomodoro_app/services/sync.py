"""Sincronización con el servidor (VPS): configuración, temporizador y estadísticas con la app del teléfono.

La compu manda su configuración (horario, reglas…) y el estado del temporizador cada vez que cambian,
y se entera al instante de lo que haga el teléfono (iniciar, pausar, descanso…) con un long-poll.
Si `~/.config/pomodoro-ahuevo/sync.json` no existe, todo esto queda apagado y la app funciona igual.
"""
import json
import threading
import time
import urllib.error
import urllib.request

from ..config import save_config
from ..paths import SYNC_FILE

# Lo que se comparte con el teléfono (lo demás es local: ventana, IP del teléfono, consejos…).
SYNC_KEYS = ("work_min", "short_min", "long_min", "cycles", "warn_sec", "auto_start_work", "allow_skip",
             "focus_allow_skip", "long_flex", "long_flex_same_as_short", "long_lock_first_min",
             "long_lock_last_min", "autostart", "overtime_mode", "reminders", "activities", "schedule", "grid_hours",
             "phone_break_end", "phone_lock_delay_sec", "phone_read_sec", "phone_work_guard",
             "phone_work_warn_min", "phone_work_lock_min", "phone_dnd", "phone_distract", "phone_distracting")
ALIVE_SEC = 120  # un dispositivo cuenta como "vivo" si habló con el servidor hace menos de esto
SOURCE = "desktop"


def read_settings():
    try:
        with open(SYNC_FILE) as f:
            s = json.load(f)
        return s if s.get("url") and s.get("token") else None
    except (OSError, ValueError):
        return None


class Sync:
    def __init__(self, app):
        self.app = app
        self.settings = read_settings()
        self.rev = -1                 # -1: la primera consulta responde al instante
        self.remote = None            # último estado recibido del servidor
        self.timer_rev_seen = 0
        self.config_rev_seen = 0
        self.sent_config = None       # JSON de la última configuración enviada
        self.sent_timer = None
        self.pending = {}             # lo que falta enviar: {"config": body, "timer": body}
        self.lock = threading.Condition()
        self.last_heartbeat = 0
        self.online = False
        self.started = time.time()
        if self.settings:
            threading.Thread(target=self._poll_loop, daemon=True).start()
            threading.Thread(target=self._send_loop, daemon=True).start()

    @property
    def enabled(self):
        return self.settings is not None

    def ready(self):
        """¿Ya sabemos qué tiene el servidor? (o no hay servidor, o no responde tras 10 s)
        Antes de esto la jornada no arranca sola: quizá el teléfono ya trae un pomodoro en curso."""
        return not self.enabled or self.remote is not None or time.time() - self.started > 10

    # ---------- red (hilos aparte) ----------
    def _req(self, method, path, body=None, timeout=15):
        req = urllib.request.Request(self.settings["url"].rstrip("/") + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Authorization": "Bearer " + self.settings["token"],
                                              "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.load(r)

    def _poll_loop(self):
        while True:
            try:
                st = self._req("GET", f"/api/wait?rev={self.rev}&source={SOURCE}&timeout=40", timeout=60)
                self.online = True
                self.rev = st["rev"]
                self.app.after(0, lambda st=st: self._on_state(st))
            except (OSError, ValueError, urllib.error.URLError):
                self.online = False
                time.sleep(15)

    def _send_loop(self):
        while True:
            with self.lock:
                while not self.pending:
                    self.lock.wait()
                kind, body = next(iter(self.pending.items()))
            try:
                self._req("PUT" if kind in ("config", "timer") else "POST", f"/api/{kind}", body)
                with self.lock:
                    if self.pending.get(kind) is body:   # no llegó otra versión mientras enviábamos
                        del self.pending[kind]
            except (OSError, ValueError, urllib.error.URLError):
                time.sleep(15)

    def _queue(self, kind, body):
        with self.lock:
            self.pending[kind] = body
            self.lock.notify()

    # ---------- hilo de la interfaz ----------
    def tick(self):
        """Cada segundo: envía lo que haya cambiado y la señal de vida."""
        if not self.enabled or self.remote is None:   # primero hay que saber qué tiene el servidor
            return
        cfg = {k: self.app.cfg.get(k) for k in SYNC_KEYS}
        cfg_json = json.dumps(cfg, sort_keys=True, ensure_ascii=False)
        if cfg_json != self.sent_config:
            self.sent_config = cfg_json
            self._queue("config", {"source": SOURCE, "config": cfg})
        snap = self.app.timer_snapshot()
        if snap != self.sent_timer:
            self.sent_timer = snap
            self._queue("timer", {"source": SOURCE, "timer": snap})
        if time.time() - self.last_heartbeat > 30:
            self.last_heartbeat = time.time()
            self._queue("heartbeat", {"source": SOURCE, "stats": self.app.cfg["stats"],
                                      "phone_stats": self.app.cfg.get("phone_stats", {})})

    def _on_state(self, st):
        first = self.remote is None
        self.remote = st
        cfg = self.app.cfg
        changed = False
        # Estadísticas: el máximo por día entre lo que contó cada quien.
        for key in ("stats", "phone_stats"):
            local = cfg.setdefault(key, {})
            for day, v in (st.get(key) or {}).items():
                if v > local.get(day, 0):
                    local[day] = v
                    changed = True
        # Configuración editada desde el teléfono (también si fue mientras la compu estaba apagada:
        # `sync_config_rev` recuerda la última versión que esta compu ya conocía).
        seen_rev = self.config_rev_seen if not first else cfg.get("sync_config_rev", 0)
        if st.get("config") and st["config_source"] != SOURCE and st["config_rev"] > seen_rev:
            cfg.update({k: v for k, v in st["config"].items() if k in SYNC_KEYS})
            self.sent_config = json.dumps({k: cfg.get(k) for k in SYNC_KEYS}, sort_keys=True, ensure_ascii=False)
            if self.app.idle():   # p. ej. cambiaste la duración del pomodoro desde el teléfono
                self.app.phase_total = self.app.remaining = self.app.duration("work")
            changed = True
        self.config_rev_seen = st.get("config_rev", 0)
        if cfg.get("sync_config_rev") != self.config_rev_seen:
            cfg["sync_config_rev"] = self.config_rev_seen
            changed = True
        # Temporizador movido desde el teléfono.
        t = st.get("timer")
        # Al arrancar se retoma lo del servidor aunque lo haya escrito esta misma compu (reinicio de la app).
        if t and (first or st["timer_source"] != SOURCE) and st["timer_rev"] > self.timer_rev_seen:
            recent = time.time() - t.get("updated", 0) < 12 * 3600
            if (not first or (recent and self.app.idle())) and self.app.apply_remote_timer(t):
                self.sent_timer = self.app.timer_snapshot()   # no regresárselo al servidor
            else:
                self.sent_timer = None   # aquí no aplica: que el teléfono vea el estado real de la compu
        self.timer_rev_seen = st.get("timer_rev", 0)
        if changed:
            save_config(cfg)
        self.app.render()

    # ---------- consultas ----------
    def device_alive(self, name):
        dev = ((self.remote or {}).get("devices") or {}).get(name)
        if not dev or not self.online:
            return False
        return self.remote["server_time"] - dev.get("seen", 0) < ALIVE_SEC

    def phone_app_alive(self):
        """¿La app del teléfono está corriendo y vigilando? Entonces ADB no repite sus avisos."""
        if not self.device_alive("phone"):
            return False
        return bool((self.remote["devices"]["phone"].get("info") or {}).get("guard"))

    def phone_app_info(self):
        return ((self.remote or {}).get("devices") or {}).get("phone", {}).get("info") or {}
