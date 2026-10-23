"""Teléfono por ADB: conexión automática (cable/WiFi/búsqueda en la red), avisos, bloqueo y vigilancia."""
import json
import queue
import re
import shlex
import shutil
import socket
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor

from ..config import save_config
from ..constants import VIBRATION
from ..paths import PANEL_CONFIG, PANEL_META
from .system import notify, run


class Phone:
    """Habla con el teléfono por ADB (cable o WiFi) en un hilo aparte para no trabar la interfaz.

    Se conecta solo: por cable activa el modo WiFi; sin conexión prueba la última IP y, si no,
    busca el teléfono en la red local. Cuando nada de eso funciona (p. ej. tras reiniciar el
    teléfono), `state["conn"]` queda en "needs_cable" y la app invita a conectar el cable.
    """

    def __init__(self, app):
        self.app = app
        self.q = queue.Queue()
        self.serial = None
        self.state = {"conn": "searching", "model": "", "battery": None, "charging": False, "app": ""}
        self.last_connect = self.last_scan = self.last_battery = self.last_battery_warn = 0
        self.last_tick = time.time()
        self.wifi_done_for = None     # cable para el que ya se activó el modo WiFi
        self.on_since = None
        self.warned = False
        self.distract_pkg = None      # app que distrae sobre la que ya se avisó
        self.dnd_by_us = False
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        while True:
            fn = self.q.get()
            try:
                fn()
            except Exception:  # noqa: BLE001 — el teléfono puede desconectarse en cualquier momento
                pass

    @staticmethod
    def _later(secs, fn):
        """Corre `fn` en `secs` segundos sin impedir que la app se cierre."""
        t = threading.Timer(secs, fn)
        t.daemon = True
        t.start()

    def submit(self, fn):
        if self.app.cfg["phone_enabled"] and shutil.which("adb"):
            self.q.put(fn)

    def adb(self, *args, timeout=15, serial=None):
        serial = serial or self.serial
        cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.stdout

    def shell(self, *args):
        """`adb shell` con cada argumento entrecomillado (para textos con espacios)."""
        return self.adb("shell", " ".join(shlex.quote(a) for a in args))

    # ---------- conexión ----------
    @staticmethod
    def devices():
        out = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=10).stdout
        return [tuple(l.split()[:2]) for l in out.splitlines()[1:] if len(l.split()) >= 2]

    def known_ip(self):
        ip = self.app.cfg.get("phone_ip")
        try:
            with open(PANEL_CONFIG) as f:
                ip = json.load(f).get("wifi_ip") or ip
        except (OSError, ValueError):
            pass
        return ip

    def remember(self, **kw):
        def save():
            self.app.cfg.update(kw)
            save_config(self.app.cfg)
        self.app.after(0, save)

    def enable_wifi(self, usb):
        """Con el cable conectado: deja el teléfono escuchando ADB por WiFi y se conecta."""
        self.wifi_done_for = usb
        port = self.adb("shell", "getprop", "service.adb.tcp.port", serial=usb).strip()
        if port != "5555":
            self.adb("tcpip", "5555", serial=usb)
            time.sleep(3)
            self.adb("wait-for-device", serial=usb, timeout=20)
        m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)", self.adb("shell", "ip", "-f", "inet", "addr", "show",
                                                              "wlan0", serial=usb))
        if m:
            self.adb("connect", f"{m.group(1)}:5555", timeout=8, serial="")
            self.remember(phone_ip=m.group(1))
            self.app.after(0, lambda: notify("📱 Teléfono listo por WiFi", "Ya puedes desconectar el cable."))

    def scan_lan(self):
        """Busca en la red local un teléfono con ADB por WiFi (puerto 5555) y se conecta."""
        self.last_scan = time.time()
        m = re.search(r"src (\d+\.\d+\.\d+)\.\d+", run("ip", "-4", "route", "get", "1.1.1.1"))
        if not m:
            return
        hosts = [f"{m.group(1)}.{i}" for i in range(1, 255)]

        def probe(h):
            try:
                socket.create_connection((h, 5555), 0.35).close()
                return True
            except OSError:
                return False

        with ThreadPoolExecutor(64) as ex:
            found = [h for h, ok in zip(hosts, ex.map(probe, hosts)) if ok]
        want = self.app.cfg.get("phone_serial")
        for h in found:
            target = f"{h}:5555"
            self.adb("connect", target, timeout=8, serial="")
            got = self.adb("shell", "getprop", "ro.serialno", serial=target).strip()
            if got and (not want or got == want):
                self.remember(phone_ip=h)
                return
            self.adb("disconnect", target, serial="")

    def ensure(self):
        devs = self.devices()
        ready = [s for s, st in devs if st == "device"]
        usb = [s for s in ready if ":" not in s]
        if usb and usb[0] != self.wifi_done_for and not any(":" in s for s in ready):
            self.enable_wifi(usb[0])
        if not ready:
            ip = self.known_ip()
            if ip and time.time() - self.last_connect > 20:
                self.last_connect = time.time()
                self.adb("connect", f"{ip}:5555", timeout=8, serial="")
            if time.time() - self.last_scan > 180 and not self.serial_ready():
                self.scan_lan()
            devs = self.devices()
            ready = [s for s, st in devs if st == "device"]
            usb = [s for s in ready if ":" not in s]
        if not usb:
            self.wifi_done_for = None
        if not ready:
            self.serial = None
            if any(st == "unauthorized" for _, st in devs):
                conn = "unauthorized"
            else:
                known = self.app.cfg.get("phone_serial") or self.known_ip()
                conn = "needs_cable" if known else "never"
            self.state = dict(self.state, conn=conn)
            return False
        self.serial = (usb or ready)[0]
        st = dict(self.state, conn="usb" if usb else "wifi")
        if not st["model"]:
            st["model"] = (self.adb("shell", "getprop", "ro.product.marketname").strip()
                           or self.adb("shell", "getprop", "ro.product.model").strip())
            serial = self.adb("shell", "getprop", "ro.serialno").strip()
            if serial and serial != self.app.cfg.get("phone_serial"):
                self.remember(phone_serial=serial)
        self.state = st
        return True

    def serial_ready(self):
        return any(st == "device" for _, st in self.devices())

    def refresh_battery(self):
        self.last_battery = time.time()
        out = self.adb("shell", "dumpsys", "battery")
        level = re.search(r"level: (\d+)", out)
        charging = re.search(r"(AC|USB|Wireless) powered: true", out)
        self.state = dict(self.state, battery=int(level.group(1)) if level else None, charging=bool(charging))

    def foreground(self):
        m = re.search(r"topResumedActivity=\S+ \S+ ([\w.]+)/", self.adb("shell", "dumpsys", "activity", "activities"))
        return m.group(1) if m else ""

    # ---------- primitivas ----------
    def _notify(self, title, text, vibrate=True):
        if not self.ensure():
            return
        if self.dnd_by_us:   # en No molestar no vibra: lo quitamos un momento para avisar
            self.shell("cmd", "notification", "set_dnd", "off")
        self.shell("cmd", "notification", "post", "-S", "bigtext", "-t", title,
                   "pomodoro" + str(int(time.time())), text)
        if vibrate:
            self._vibrate()
        if self.dnd_by_us:
            time.sleep(4)
            self.shell("cmd", "notification", "set_dnd", "priority")

    def _vibrate(self):
        # Tres pulsos fuertes (≈2.5 s): se nota aunque el teléfono esté en la bolsa.
        self.shell("cmd", "vibrator_manager", "synced", "waveform", "-a", *VIBRATION)

    def screen_on(self):
        return "mWakefulness=Awake" in self.adb("shell", "dumpsys", "power")

    def _lock(self):
        self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        self.adb("shell", "input", "keyevent", "KEYCODE_SLEEP")

    def _warn_then(self, title, text, action, still_valid=lambda: True):
        """Primero avisa (con tiempo para leer) y después ejecuta `action` si sigue aplicando."""
        secs = self.app.cfg["phone_read_sec"]
        self._notify(title, text)

        def later():
            if still_valid() and self.ensure():
                action()
        self._later(secs, lambda: self.submit(later))

    def _set_dnd(self, on):
        if on and not self.dnd_by_us:
            if self.shell("settings", "get", "global", "zen_mode").strip() == "0":  # no pisar el tuyo
                self.shell("cmd", "notification", "set_dnd", "priority")
                self.dnd_by_us = True
        elif not on and self.dnd_by_us:
            self.shell("cmd", "notification", "set_dnd", "off")
            self.dnd_by_us = False

    # ---------- acciones que usa la app ----------
    def notify(self, title, text, vibrate=True):
        self.submit(lambda: self._notify(title, text, vibrate))

    def break_over(self, text):
        self.notify("⏰ Se acabó el descanso", text)
        if self.app.cfg["phone_break_end"] == "lock":
            delay = self.app.cfg["phone_lock_delay_sec"]
            self._later(delay, lambda: self.submit(self._lock_if_on))

    def working(self):
        return self.app.phase == "work" and self.app.running

    def _lock_if_on(self):
        if self.working() and self.ensure() and self.screen_on():
            secs = self.app.cfg["phone_read_sec"]
            self._warn_then("🔒 Ya es hora de trabajar",
                            f"Sigues en el teléfono y el descanso ya terminó.\nEn {secs} s se bloquea.",
                            lambda: self.screen_on() and self._lock(), self.working)

    def tick(self, working):
        """Cada ~15 s: conexión, batería, No molestar, uso y apps que distraen."""
        self.submit(lambda: self._tick(working))

    def _tick(self, working):
        now = time.time()
        elapsed = min(now - self.last_tick, 30)
        self.last_tick = now
        if not self.ensure():
            self.dnd_by_us = False
            return
        if now - self.last_battery > 120:
            self.refresh_battery()
        cfg = self.app.cfg
        self._set_dnd(working and cfg["phone_dnd"])
        if not working:
            self.on_since, self.warned, self.distract_pkg = None, False, None
            b = self.state["battery"]
            if b is not None and b <= 20 and not self.state["charging"] and now - self.last_battery_warn > 3600:
                self.last_battery_warn = now
                self._notify("🔋 Batería baja", f"Te queda {b}%. Aprovecha el descanso para cargar el teléfono.")
            return
        if not self.screen_on():
            self.on_since, self.warned, self.distract_pkg = None, False, None
            self.state = dict(self.state, app="")
            return
        # Pantalla encendida en horario de trabajo: se cuenta para tus estadísticas.
        self.app.after(0, lambda: self.app.add_phone_time(elapsed))
        fg = self.foreground()
        self.state = dict(self.state, app=fg)
        if cfg["phone_distract"] and fg in cfg["phone_distracting"]:
            if self.distract_pkg != fg:
                self.distract_pkg = fg
                name = app_name(fg)
                secs = cfg["phone_read_sec"]
                self._warn_then(f"📵 {name} en horario de trabajo", f"En {secs} s te saco de {name}.",
                                lambda: self.foreground() == fg and self.adb("shell", "input", "keyevent",
                                                                             "KEYCODE_HOME"),
                                self.working)
            return
        self.distract_pkg = None
        if not cfg["phone_work_guard"]:
            return
        self.on_since = self.on_since or now
        mins = (now - self.on_since) / 60
        if mins >= cfg["phone_work_lock_min"]:
            self.on_since, self.warned = None, False
            secs = cfg["phone_read_sec"]
            self._warn_then("🔒 Deja el teléfono", f"Llevas {int(mins)} min en él en horario de trabajo.\n"
                                                  f"En {secs} s se bloquea.",
                            lambda: self.screen_on() and self._lock(), self.working)
            self.app.after(0, lambda: notify("Voy a bloquear tu teléfono", "Estabas en él durante el trabajo."))
        elif mins >= cfg["phone_work_warn_min"] and not self.warned:
            self.warned = True
            left = cfg["phone_work_lock_min"] - cfg["phone_work_warn_min"]
            self._notify("👀 Estás en el teléfono", f"Es hora de trabajo. Si sigues, en {left} min lo bloqueo.")

    def shutdown(self):
        """Al salir de la app: quita el No molestar que pusimos nosotros."""
        if self.dnd_by_us and self.serial:
            try:
                self.shell("cmd", "notification", "set_dnd", "off")
            except Exception:  # noqa: BLE001
                pass

    def summary(self):
        st = self.state
        conn = st["conn"]
        if conn in ("usb", "wifi"):
            bat = ""
            if st["battery"] is not None:
                bat = f"  ·  {'⚡' if st['charging'] else '🔋'} {st['battery']}%"
            return f"📱 {st['model'] or 'Teléfono'}  ·  {'cable' if conn == 'usb' else 'WiFi'}{bat}"
        return {"searching": "📱 Buscando el teléfono…",
                "unauthorized": "📱 Acepta la depuración USB en el teléfono",
                "needs_cable": "📱 Sin conexión con el teléfono",
                "never": "📱 Conecta tu teléfono por cable una vez"}[conn]

    def needs_action(self):
        return self.app.cfg["phone_enabled"] and self.state["conn"] in ("needs_cable", "unauthorized", "never")


def app_name(pkg):
    """Nombre bonito de una app usando la caché del Panel ADB."""
    try:
        with open(PANEL_META) as f:
            name = (json.load(f).get(pkg) or {}).get("name")
    except (OSError, ValueError):
        name = None
    if not name:
        return pkg.rsplit(".", 1)[-1]
    return re.split(r":\s| [-–|] ", name, maxsplit=1)[0].strip() or name


def installed_apps():
    """[(paquete, nombre)] de las apps conocidas por el Panel ADB."""
    try:
        with open(PANEL_META) as f:
            meta = json.load(f)
    except (OSError, ValueError):
        return []
    return sorted(((p, app_name(p)) for p in meta), key=lambda x: x[1].lower())
