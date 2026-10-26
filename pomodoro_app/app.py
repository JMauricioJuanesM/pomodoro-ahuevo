"""La app: estado del pomodoro y unión de las piezas (ventana, temporizador, jornada, recordatorios)."""
import datetime as dt
import time
import tkinter as tk
from tkinter import messagebox

from .config import load_config
from .constants import COLORS, NAMES
from .controller.reminders import RemindersMixin
from .controller.timer import TimerMixin
from .controller.workday import WorkdayMixin
from .services.autostart import sync_autostart
from .services.phone import Phone
from .services.screenwatch import ScreenWatch
from .services.shortcuts import restore_shortcuts
from .services.sync import Sync
from .services.system import notify
from .services.tray import TrayClient
from .ui.main_window import MainWindowMixin
from .ui.widget import Widget


class Pomodoro(MainWindowMixin, TimerMixin, WorkdayMixin, RemindersMixin, tk.Tk):
    def __init__(self, sock, auto=False):
        super().__init__()
        self.title("Pomodoro")
        self.minsize(410, 1)   # la altura se ajusta sola (crece cuando aparece el aviso del teléfono)
        self.resizable(False, False)
        self.cfg = load_config()

        self.phase = "work"
        self.phase_title = NAMES["work"]
        self.phase_color = COLORS["work"]
        self.overtime = False        # la fase actual se calculó en horas extra
        self.done_in_cycle = 0
        self.running = False
        self.end_time = None
        self.phase_total = self.duration("work")
        self.remaining = self.phase_total
        self.warned = False
        self.overlay = None          # pantalla completa visible
        self.break_free = False      # descanso flexible con la pantalla liberada
        self.break_skippable = False  # el descanso actual trae botón de saltar
        self.break_focus = False     # …porque empezó en una actividad «enfocado»
        self.relock_warned = False
        self.current_tip = ""
        self.break_context = ""
        self.shutdown_at = None
        self.shutdown_total = 1
        self.shutdown_warned = False
        self.auto_date = None        # día en que ya arrancó la jornada sola
        self.overtime_ok_date = None  # día en que aceptaste seguir en horas extra
        self.last_nag = time.time()
        self.sched_key = None        # bloque del horario cuyo descanso ya empezó
        self.pending_reminders = []
        self.active_reminder = None  # (texto, hasta) para recordatorios con duración sin bloqueo
        self.resume_work = None      # pomodoro interrumpido por un recordatorio
        self.last_second = 0
        self.last_guard = 0
        self.last_phone_nag = 0
        self.hidden_notice = False
        self.phone = Phone(self)
        self.sync = Sync(self)
        self.screenwatch = ScreenWatch(self, self.sync.settings)

        self._build()
        self.widget = Widget(self)
        self.apply_topmost()
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self.tray = TrayClient(self, self.on_tray_command)
        if sock:
            self.sock = sock
            self.tk.createfilehandler(sock, tk.READABLE, self.on_socket)
        try:
            sync_autostart(self.cfg)
        except OSError:
            pass
        if self.cfg["widget_mode"]:
            self.after(100, self.enter_widget)
        elif auto and self.tray_ok:   # al iniciar sesión arranca discreto, en la barra
            self.withdraw()
        self.phone.tick(False)
        self.render()
        self.tick()

    # ---------- barra superior ----------
    @property
    def tray_ok(self):
        return self.tray.ok

    def on_tray_command(self, line):
        actions = {"show": self.exit_widget, "toggle": self.toggle, "break": self.break_now,
                   "widget": self.enter_widget, "schedule": self.open_schedule,
                   "reminders": self.open_reminders, "hide": self.hide_all, "quit": self.quit_app}
        if line in actions and not (self.overlay and line != "quit"):
            actions[line]()

    def update_tray(self, v):
        status = f"{v['title'].capitalize()} · {v['time']}"
        if v["act_label"]:
            status += f"   ({v['act_label']})"
        self.tray.update({"label": v["time"], "status": status, "phone": self.phone.summary(),
                          "running": self.running, "work": self.phase == "work" and not self.shutdown_at,
                          "alert": self.phone.needs_action()})

    def on_socket(self, *_):
        conn, _ = self.sock.accept()
        msg = conn.recv(16)
        conn.close()
        if msg == b"show" and not self.overlay:
            self.exit_widget()

    def quit_app(self):
        if self.phase != "work" or self.overlay or self.shutdown_at:
            notify("Ahora no", "No se puede salir durante un descanso, al final de la jornada o apagando.")
            return
        if self.running:
            self.show_main()
            if not messagebox.askyesno("Salir", "¿Salir y perder el pomodoro actual?", parent=self):
                return
        restore_shortcuts()
        self.phone.shutdown()
        self.tray.close()
        self.destroy()

    # ---------- teléfono ----------
    def retry_phone(self):
        self.phone.last_connect = self.phone.last_scan = 0
        self.phone.tick(self.phase == "work" and self.running)
        self.phone_lbl.config(text="📱 Buscando el teléfono…")

    def add_phone_time(self, secs):
        today = dt.date.today().isoformat()
        stats = self.cfg.setdefault("phone_stats", {})
        stats[today] = stats.get(today, 0) + secs
