"""Pantallas completas (una ventana por monitor) que no se dejan cerrar: descanso, aviso y fin de jornada."""
import datetime as dt
import time
import tkinter as tk

from ..core.schedule import act_label, next_change
from ..core.timefmt import fmt
from ..services.system import lock_session, monitors


class Overlay:
    """Ventanas a pantalla completa (una por monitor) que no se dejan cerrar.

    Las subclases guardan lo que necesitan, implementan `fill` y al final de su __init__ llaman `build`.
    Si conectas otro monitor con la pantalla abierta, también se tapa.
    """

    BG = "#111"
    MONITOR_CHECK = 4   # vueltas de la guardia (cada 0.5 s) entre revisiones de los monitores

    def __init__(self, app):
        self.app = app
        self.windows = []
        self.mons = []
        self.unfocused_ticks = 0
        self.guard_ticks = 0
        self.last_lock = 0.0

    def fill(self, box, main):
        """Contenido de una pantalla; `main` es la del primer monitor, la que lleva textos y botones."""
        raise NotImplementedError

    def build(self):
        self.mons = monitors() or [None]
        self.windows = [self._window(mon, main=not i) for i, mon in enumerate(self.mons)]
        self.main = self.windows[0]
        self.main.focus_force()
        try:
            self.main.grab_set_global()
        except tk.TclError:
            pass
        self.guard()

    def _window(self, mon, main):
        w = tk.Toplevel(self.app)
        w.configure(bg=self.BG)
        w.protocol("WM_DELETE_WINDOW", lambda: None)
        self._place(w, mon)
        for seq in ("<Escape>", "<Alt-F4>", "<Control-w>", "<Control-q>"):
            w.bind(seq, lambda e: "break")
        box = tk.Frame(w, bg=self.BG)
        box.place(relx=0.5, rely=0.5, anchor="center")
        self.fill(box, main)
        return w

    @staticmethod
    def _place(w, mon):
        if mon:
            x, y, mw, mh = mon
            w.attributes("-fullscreen", False)
            w.geometry(f"{mw}x{mh}+{x}+{y}")
        w.attributes("-topmost", True)
        w.update_idletasks()
        w.attributes("-fullscreen", True)

    def sync_monitors(self):
        """Si conectaste o quitaste un monitor, rehace las pantallas secundarias sin cerrar esta."""
        mons = monitors()
        if not mons or mons == self.mons:
            return
        if mons[0] != self.mons[0]:
            self._place(self.main, mons[0])
        self.mons = mons
        for w in self.windows[1:]:
            w.destroy()
        self.windows = [self.main] + [self._window(mon, main=False) for mon in mons[1:]]

    def label(self, box, text, size, fg="white", bold=False, **pack):
        lbl = tk.Label(box, text=text, fg=fg, bg=self.BG, font=("Sans", size, "bold" if bold else "normal"),
                       wraplength=1000, justify="center")
        lbl.pack(**pack)
        return lbl

    def button(self, box, text, cmd, bg="#333", fg="#ddd"):
        return tk.Button(box, text=text, command=cmd, bg=bg, fg=fg, activebackground="#555",
                         activeforeground="white", relief="flat", padx=18, pady=8, font=("Sans", 12, "bold"),
                         cursor="hand2", disabledforeground="#666")

    def guard(self):
        """Mantiene la ventana al frente; si se escapa demasiado, bloquea la sesión."""
        if not self.windows:
            return
        self.guard_ticks += 1
        if self.guard_ticks % self.MONITOR_CHECK == 0:
            self.sync_monitors()
        for w in self.windows:
            w.attributes("-topmost", True)
            w.lift()
        if self.main.focus_displayof() is not None:
            self.unfocused_ticks = 0
        else:
            self.main.focus_force()
            self.unfocused_ticks += 1
            if (self.app.cfg["lock_if_escape"] and self.unfocused_ticks >= 4
                    and time.time() - self.last_lock > 15):
                self.last_lock = time.time()
                lock_session()
        self.main.after(500, self.guard)

    def close(self):
        try:
            self.main.grab_release()
        except tk.TclError:
            pass
        for w in self.windows:
            w.destroy()
        self.windows = []


class BreakScreen(Overlay):
    def __init__(self, app, title, color, remaining, tip, context, overtime):
        super().__init__(app)
        self.title = title.upper() + (" · HORAS EXTRA" if overtime else "")
        self.color = "#cccccc" if color.lower() in ("#000000", "#111111") else color
        self.remaining, self.tip, self.context, self.overtime = remaining, tip, context, overtime
        self.time_labels = []
        self.build()

    def fill(self, box, main):
        app = self.app
        self.label(box, self.title, 28, self.color, True)
        self.time_labels.append(self.label(box, fmt(self.remaining), 120, bold=True, pady=10))
        if not main:
            return
        self.label(box, "Aléjate de la computadora.", 20, "#ccc")
        self.label(box, self.tip, 17, "#9ad", pady=(14, 0))
        if self.context:
            self.label(box, self.context, 12, "#777", pady=(8, 0))

        self.flex_frame = tk.Frame(box, bg=self.BG)
        self.flex_frame.pack(pady=(30, 0))
        self.flex_label = self.label(self.flex_frame, "", 13, "#aaa")
        self.flex_btn = self.button(self.flex_frame, "Salir del descanso por ahora", app.leave_break)

        if self.overtime:
            self.label(box, "Tu jornada ya terminó. Deberías estar descansando.", 14, "#e59866", pady=(30, 8))
            row = tk.Frame(box, bg=self.BG)
            row.pack()
            self.button(row, "Apagar la compu", app.shutdown_now, bg="#922b21", fg="white").pack(side="left", padx=6)
            self.button(row, f"Guardar y apagar en {app.cfg['shutdown_grace_min']} min",
                        app.shutdown_soon).pack(side="left", padx=6)
        if app.break_skippable:
            text = "Saltar descanso (estoy en algo importante)" if app.break_focus else "Saltar descanso"
            self.button(box, text, app.skip_break).pack(pady=30)

    def update(self, remaining):
        self.remaining = remaining
        self.time_labels = [l for l in self.time_labels if l.winfo_exists()]   # monitores que se quitaron
        for l in self.time_labels:
            l.config(text=fmt(remaining))

    def set_flex(self, state, secs=0):
        """state: None = no aplica; 'wait' = aún bloqueado; 'open' = puedes salir."""
        if state is None:
            self.flex_label.config(text="")
            self.flex_btn.pack_forget()
        elif state == "wait":
            self.flex_label.config(text=f"Podrás salir del descanso en {fmt(secs)}")
            self.flex_btn.pack_forget()
        else:
            self.flex_label.config(text=f"Ya puedes salir. La pantalla se vuelve a bloquear cuando falten "
                                        f"{fmt(secs)}.")
            if not self.flex_btn.winfo_ismapped():
                self.flex_btn.pack(pady=10)


class MessageScreen(Overlay):
    """Recordatorio a pantalla completa por unos segundos."""

    def __init__(self, app, text, subtitle, secs, closable):
        super().__init__(app)
        self.text, self.subtitle, self.closable = text, subtitle, closable
        self.left = secs
        self.build()
        self.countdown()

    def fill(self, box, main):
        self.label(box, "⏰ RECORDATORIO", 22, "#f5b041", True)
        self.label(box, self.text, 54, bold=True, pady=20)
        if not main:
            return
        if self.subtitle:
            self.label(box, self.subtitle, 15, "#999")
        self.count = self.label(box, "", 12, "#666", pady=(26, 0))
        if self.closable:
            self.button(box, "Entendido", self.done, bg="#1f618d", fg="white").pack(pady=18)

    def countdown(self):
        if not self.windows:
            return
        if self.left <= 0:
            self.done()
            return
        self.count.config(text=f"Se cierra en {self.left} s")
        self.left -= 1
        self.main.after(1000, self.countdown)

    def done(self):
        if self.app.overlay is self:
            self.app.close_overlay()


class EndDayScreen(Overlay):
    CONTINUE_DELAY = 20

    def __init__(self, app, end):
        super().__init__(app)
        self.end = end
        self.wait = self.CONTINUE_DELAY
        self.build()
        self.countdown()

    def fill(self, box, main):
        app = self.app
        self.label(box, "TU JORNADA TERMINÓ", 40, "#e59866", True)
        if not main:
            return
        today = app.cfg["stats"].get(dt.date.today().isoformat(), 0)
        now = dt.datetime.now().strftime("%H:%M")
        when = (f"Tu trabajo de hoy terminaba a las {self.end:%H:%M}." if self.end
                else "Hoy no tenías trabajo en tu horario.")
        self.label(box, f"Son las {now}. {when}", 20, "#ccc", pady=(16, 4))
        hours = today * app.cfg["work_min"] / 60
        self.label(box, f"Hoy hiciste {today} pomodoro{'s' if today != 1 else ''} "
                        f"(≈ {hours:.1f} h de trabajo enfocado). Suficiente por hoy.", 15, "#999")
        phone_min = int(app.cfg.get("phone_stats", {}).get(dt.date.today().isoformat(), 0) // 60)
        if app.cfg["phone_enabled"]:
            self.label(box, f"📱 Usaste el teléfono {phone_min} min en horario de trabajo.", 13, "#888",
                       pady=(4, 0))
        aid, start = next_change(app.cfg, dt.datetime.now())
        if aid:
            self.label(box, f"Lo que sigue en tu horario: {act_label(app.cfg, aid)} a las {start:%H:%M}.", 15,
                       "#9ad", pady=(8, 0))
        row = tk.Frame(box, bg=self.BG)
        row.pack(pady=40)
        self.button(row, "Apagar la compu", app.shutdown_now, bg="#922b21", fg="white").pack(side="left", padx=8)
        self.button(row, f"Dame {app.cfg['shutdown_grace_min']} min para guardar y apágala",
                    app.shutdown_soon, bg="#1f618d", fg="white").pack(side="left", padx=8)
        self.cont = self.button(box, "", app.continue_overtime)
        self.cont.config(state="disabled", font=("Sans", 10))
        self.cont.pack()
        self.label(box, "En horas extra los pomodoros duran la mitad y los descansos el doble.", 10, "#666",
                   pady=(6, 0))

    def countdown(self):
        if not self.windows:
            return
        if self.wait > 0:
            self.cont.config(text=f"Seguir trabajando en horas extra ({self.wait})")
            self.wait -= 1
            self.main.after(1000, self.countdown)
        else:
            self.cont.config(text="Seguir trabajando en horas extra", state="normal")
