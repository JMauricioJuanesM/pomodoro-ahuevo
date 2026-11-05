"""Pantallas completas (una ventana por monitor) que no se dejan cerrar: descanso, aviso y fin de jornada."""
import datetime as dt
import os
import time
import tkinter as tk

from ..core.reason import nonsense
from ..core.schedule import act_label, next_change
from ..core.timefmt import fmt, fmt_hm
from ..services.screenwatch import SHOT_DIR
from ..services.system import lock_session, monitors

OVERLAY_FLAG = os.path.join(SHOT_DIR, "overlay")   # la extensión vigía no deja abrir la vista de actividades


class Overlay:
    """Ventanas a pantalla completa (una por monitor) que no se dejan cerrar.

    Las subclases guardan lo que necesitan, implementan `fill` y al final de su __init__ llaman `build`.
    Si conectas otro monitor con la pantalla abierta, también se tapa. Si una ventana acaba en otro monitor
    (arrastrada desde la vista de actividades, o con un atajo), se regresa a su lugar y se bloquea la sesión.
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
        self.moved_ticks = 0

    def fill(self, box, main):
        """Contenido de una pantalla; `main` es la del primer monitor, la que lleva textos y botones."""
        raise NotImplementedError

    def build(self):
        self.mons = monitors() or [None]
        self.windows = [self._window(mon, main=not i) for i, mon in enumerate(self.mons)]
        self.main = self.windows[0]
        self._touch_flag()
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
            self._touch_flag()
        for w in self.windows:
            w.attributes("-topmost", True)
            w.lift()
        escaped = self._check_places()
        if self.main.focus_displayof() is not None:
            self.unfocused_ticks = 0
        else:
            self.main.focus_force()
            self.unfocused_ticks += 1
            escaped = escaped or self.unfocused_ticks >= 4
        if self.app.cfg["lock_if_escape"] and escaped and time.time() - self.last_lock > 15:
            self.last_lock = time.time()
            lock_session()
        self.main.after(500, self.guard)

    @staticmethod
    def _inside(w, mon):
        try:
            cx = w.winfo_rootx() + w.winfo_width() // 2
            cy = w.winfo_rooty() + w.winfo_height() // 2
        except tk.TclError:
            return True
        x, y, mw, mh = mon
        return x <= cx < x + mw and y <= cy < y + mh

    def _check_places(self):
        """¿Alguna pantalla se salió de su monitor? La regresa. True si fue un intento de escapar."""
        if len(self.mons) < 2 or None in self.mons:
            return False
        moved = [(w, mon) for w, mon in zip(self.windows, self.mons) if not self._inside(w, mon)]
        if not moved:
            self.moved_ticks = 0
            return False
        if monitors() != self.mons:   # conectaste o quitaste un monitor: no es trampa
            self.sync_monitors()
            return False
        self.moved_ticks += 1
        for w, mon in moved:
            self._place(w, mon)
        if self.moved_ticks >= 3:   # el gestor de ventanas no la deja regresar: se rehacen las secundarias
            self.moved_ticks = 0
            if not self._inside(self.main, self.mons[0]):
                self._place(self.main, self.mons[0])
            for w in self.windows[1:]:
                w.destroy()
            self.windows = [self.main] + [self._window(mon, main=False) for mon in self.mons[1:]]
        return True

    @staticmethod
    def _touch_flag():
        try:
            os.makedirs(SHOT_DIR, exist_ok=True)
            with open(OVERLAY_FLAG, "w") as f:
                f.write(str(time.time()))
        except OSError:
            pass

    def close(self):
        try:
            self.main.grab_release()
        except tk.TclError:
            pass
        for w in self.windows:
            w.destroy()
        self.windows = []
        try:
            os.remove(OVERLAY_FLAG)
        except OSError:
            pass


class BreakScreen(Overlay):
    def __init__(self, app, title, color, remaining, tip, context, overtime, phrase=None, ask=None):
        super().__init__(app)
        self.title = title.upper() + (" · HORAS EXTRA" if overtime else "")
        self.color = "#cccccc" if color.lower() in ("#000000", "#111111") else color
        self.remaining, self.tip, self.context, self.overtime = remaining, tip, context, overtime
        self.phrase, self.ask = phrase, ask
        self.time_labels = []
        self.build()

    def fill(self, box, main):
        app = self.app
        self.label(box, self.title, 28, self.color, True)
        self.time_labels.append(self.label(box, fmt(self.remaining), 120, bold=True, pady=10))
        if not main:
            return
        self.label(box, "Aléjate de la computadora.", 20, "#ccc")
        if self.phrase:   # frase del mazo de Faro (ver core/mind.py)
            self.label(box, self.phrase.get("text", ""), 22, "#f0d589", True, pady=(18, 0))
            if self.phrase.get("sub"):
                self.label(box, self.phrase["sub"], 14, "#bbb")
            row = tk.Frame(box, bg=self.BG)
            row.pack(pady=(4, 0))
            for icon, val in (("👍", 1), ("👎", -1)):
                b = self.button(row, icon, None)
                b.config(command=lambda v=val, b=b: (app.mind_react(self.phrase.get("id"), v), b.config(state="disabled")))
                b.pack(side="left", padx=4)
        else:
            self.label(box, self.tip, 17, "#9ad", pady=(14, 0))
        if self.context:
            self.label(box, self.context, 12, "#777", pady=(8, 0))
        if self.ask:   # el descanso es buen momento para contestar lo que pregunta Faro
            self.label(box, "💡 " + self.ask["question"], 15, "#9ad", pady=(24, 4))
            self.ask_entry = tk.Text(box, width=60, height=3, font=("Sans", 12), bg="#222", fg="white",
                                     insertbackground="white", relief="flat", wrap="word")
            self.ask_entry.pack()
            self.ask_btn = self.button(box, "Enviar", self.send_ask, bg="#1f618d", fg="white")
            self.ask_btn.pack(pady=6)

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

    def send_ask(self):
        text = self.ask_entry.get("1.0", "end").strip()
        if text:
            self.app.answer_ask(self.ask, text)
            self.ask_entry.config(state="disabled")
            self.ask_btn.config(text="¡Gracias!", state="disabled")

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
        fs = app.cfg.get("focus_stats", {}).get(dt.date.today().isoformat())
        if fs:
            self.label(box, f"Trabajo efectivo: {fmt_hm(fs.get('effective', 0))}  ·  tardaste en arrancar "
                            f"{fmt_hm(fs.get('late', 0))}  ·  distraído {fmt_hm(fs.get('distract', 0))}", 13, "#888",
                       pady=(4, 0))
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


class NudgeScreen(Overlay):
    """Llevas rato sin arrancar (sensor de presencia): pide empezar, o que digas en qué estás trabajando
    lejos de la compu (lo juzga la IA). No tiene «cerrar»: se va cuando arranca el pomodoro."""

    BG = "#140f0b"

    def __init__(self, app, act, late):
        super().__init__(app)
        self.act, self.late = act, late
        self.build()
        self.update(late)
        self.main.after(300, lambda: self.windows and self.entry.focus_set())

    def fill(self, box, main):
        self.label(box, "¿ARRANCAMOS?", 34, "#f5b041", True)
        if not main:
            return
        self.late_lbl = self.label(box, "", 22, "#ddd", pady=(14, 4))
        self.label(box, "El pomodoro solo corre cuando trabajas de verdad; este rato no cuenta.", 14, "#999")
        self.button(box, "▶  Empezar ahora", self.app.start_effective, bg="#c0392b", fg="white").pack(pady=28)
        self.label(box, "¿Estás trabajando lejos de la compu? Dime en qué:", 12, "#888", pady=(6, 4))
        row = tk.Frame(box, bg=self.BG)
        row.pack()
        self.entry = tk.Entry(row, width=44, font=("Sans", 12), bg="#222", fg="white", insertbackground="white",
                              relief="flat")
        self.entry.pack(side="left", ipady=6)
        self.entry.bind("<Return>", lambda e: self.declare())
        self.ask_btn = self.button(row, "Cuenta como trabajo", self.declare)
        self.ask_btn.pack(side="left", padx=8)
        self.answer = self.label(box, "", 12, "#e59866", pady=(10, 0))

    def update(self, late):
        if self.windows:
            self.late_lbl.config(text=f"Toca {self.act} desde hace {fmt(late)}.")

    def declare(self):
        text = self.entry.get().strip()
        if len(text) < 4:
            self.answer.config(text="Escribe qué estás haciendo.")
            return
        self.ask_btn.config(state="disabled")
        self.answer.config(text="Pensándolo…")
        self.app.declare_intent(text, self.on_verdict)

    def on_verdict(self, ok, msg):
        if self.windows:
            self.ask_btn.config(state="normal")
            self.answer.config(text=msg)


class SkipScreen(Overlay):
    """Saltarse el pomodoro en horario de trabajo (descanso ya, reiniciar, pausar sin sensor): a pantalla
    completa, con razón de al menos MIN_REASON letras, la frase PHRASE escrita a mano y una espera que crece
    con cada salto del día. «Mejor sigo trabajando» la cierra sin hacer nada."""

    BG = "#140b0b"
    PHRASE = "Es necesario de verdad, no estoy evitando el trabajo"
    MIN_REASON = 40

    def __init__(self, app, what, wait, left, on_confirm):
        super().__init__(app)
        self.what, self.left, self.on_confirm = what, left, on_confirm
        self.until = time.time() + wait
        self.build()
        if left:
            self.main.after(300, lambda: self.windows and self.reason.focus_set())
            self.tick()

    def fill(self, box, main):
        self.label(box, f"¿DE VERDAD VAS A {self.what.upper()}?", 30, "#e74c3c", True)
        if not main:
            return
        if not self.left:
            self.label(box, "Hoy ya usaste todos tus saltos. Sigue con lo que toca.", 16, "#ddd", pady=(18, 24))
        else:
            self.label(box, f"Es horario de trabajo. Te quedan {self.left} hoy y tu bot se va a enterar con tu razón.",
                       14, "#bbb", pady=(14, 18))
            self.label(box, "¿Por qué es absolutamente necesario?", 12, "#888", pady=(0, 4))
            self.reason = tk.Text(box, width=60, height=3, font=("Sans", 12), bg="#222", fg="white",
                                  insertbackground="white", relief="flat", wrap="word")
            self.reason.pack()
            self.label(box, f"Escribe: «{self.PHRASE}»", 12, "#888", pady=(14, 4))
            self.phrase = tk.Entry(box, width=60, font=("Sans", 12), bg="#222", fg="white", insertbackground="white",
                                   relief="flat")
            self.phrase.pack(ipady=6)
            for w in (self.reason, self.phrase):   # escrito a mano, no pegado
                for seq in ("<<Paste>>", "<Control-v>", "<Shift-Insert>", "<Button-2>"):
                    w.bind(seq, lambda e: "break")
            self.status = self.label(box, "", 12, "#e59866", pady=(12, 0))
            self.ok_btn = self.button(box, "", self.confirm)
            self.ok_btn.pack(pady=(10, 0))
        self.button(box, "Mejor sigo trabajando", self.app.close_overlay, bg="#c0392b", fg="white").pack(pady=24)

    def tick(self):
        if not self.windows:
            return
        left = self.until - time.time()
        self.ok_btn.config(text=f"Espera {fmt(left)}" if left > 0 else f"Sí, {self.what}",
                           state="disabled" if left > 0 else "normal")
        self.main.after(500, self.tick)

    def confirm(self):
        reason = self.reason.get("1.0", "end").strip()
        if time.time() < self.until:
            return
        if len(reason) < self.MIN_REASON:
            self.status.config(text=f"La razón es muy corta ({len(reason)}/{self.MIN_REASON}).")
        elif nonsense(reason):
            self.status.config(text="Eso no parece una razón: escríbela con palabras de verdad.")
        elif self.phrase.get().strip().lower() != self.PHRASE.lower():
            self.status.config(text="La frase no coincide.")
        else:
            self.app.close_overlay()
            self.on_confirm(reason)


class AskScreen(Overlay):
    """Faro pregunta algo (p. ej. qué mejorarías); se puede dejar para luego."""

    def __init__(self, app, ask):
        super().__init__(app)
        self.ask = ask
        self.build()

    def fill(self, box, main):
        self.label(box, "💡 FARO QUIERE MEJORAR CONTIGO", 18, "#9ad", True)
        self.label(box, self.ask["question"], 30, bold=True, pady=20)
        if not main:
            return
        for opt in self.ask.get("options") or []:
            self.button(box, opt, lambda o=opt: self.send(o)).pack(pady=3)
        self.entry = tk.Text(box, width=70, height=5, font=("Sans", 13), bg="#222", fg="white",
                             insertbackground="white", relief="flat", wrap="word")
        self.entry.pack(pady=10)
        self.entry.focus_set()
        row = tk.Frame(box, bg=self.BG)
        row.pack(pady=10)
        self.button(row, "Enviar", lambda: self.send(self.entry.get("1.0", "end").strip()),
                    bg="#1f618d", fg="white").pack(side="left", padx=6)
        self.button(row, "Ahora no", lambda: self.app.ask_postpone(self.ask)).pack(side="left", padx=6)

    def send(self, text):
        if text:
            self.app.answer_ask(self.ask, text)
            self.app.close_faro_overlay()


class TaskScreen(Overlay):
    """Venció una tarea de TickTick: hay que decidir (hecha, ahora o posponer; a la tercera, con razón)."""

    BG = "#1d120c"

    def __init__(self, app, task, snoozes):
        super().__init__(app)
        self.task, self.snoozes = task, snoozes
        self.build()

    def fill(self, box, main):
        t = self.task
        self.label(box, f"⏰ {t.get('project', '')}", 18, "#f5b041", True)
        self.label(box, t["title"], 44, bold=True, pady=20)
        if not main:
            return
        if self.snoozes:
            self.label(box, f"Ya la pospusiste {self.snoozes} {'vez' if self.snoozes == 1 else 'veces'}.", 14, "#e59866")
        row = tk.Frame(box, bg=self.BG)
        row.pack(pady=20)
        self.button(row, "✅ Ya la hice", lambda: self.app.task_done(t), bg="#1e8449", fg="white").pack(side="left", padx=6)
        self.button(row, "🎯 La hago ahora", lambda: self.app.task_focus(t), bg="#1f618d", fg="white").pack(side="left", padx=6)
        self.reason = None
        if self.snoozes >= 2:
            self.label(box, "¿Por qué la vuelves a posponer?", 13, "#aaa", pady=(10, 2))
            self.reason = tk.Entry(box, width=60, font=("Sans", 12), bg="#222", fg="white", insertbackground="white",
                                   relief="flat")
            self.reason.pack()
        row2 = tk.Frame(box, bg=self.BG)
        row2.pack(pady=12)
        for mins, lbl in ((15, "15 min"), (60, "1 h"), (180, "3 h")):
            self.button(row2, f"⏰ {lbl}", lambda m=mins: self.snooze(m)).pack(side="left", padx=4)

    def snooze(self, mins):
        reason = self.reason.get().strip() if self.reason else ""
        if self.reason and len(reason) < 15:
            self.reason.config(bg="#5a1d1d")
            return
        self.app.task_snooze(self.task, mins, reason)


class AwayScreen(Overlay):
    """Volviste a la compu tras un rato fuera sin contestar qué hacías: «¿qué hiciste estos N min?», a
    pantalla completa. Si fue trabajo, se te devuelve ese rato (ver `controller/offpc.py`)."""

    BG = "#0f1a14"

    def __init__(self, app, away, options):
        super().__init__(app)
        self.away, self.options = away, options
        self.build()
        self.main.after(300, lambda: self.windows and self.entry.focus_set())

    def fill(self, box, main):
        self.label(box, f"Estuviste {self.away} lejos de la compu", 30, "#f5b041", True)
        if not main:
            return
        self.label(box, "¿Qué hiciste? Si fue trabajo, te devuelvo ese rato al pomodoro.", 15, "#aaa", pady=(10, 22))
        for opt in self.options:
            self.button(box, opt, lambda o=opt: self.answer(o)).pack(fill="x", pady=3)
        row = tk.Frame(box, bg=self.BG)
        row.pack(pady=(18, 0))
        self.entry = tk.Entry(row, width=44, font=("Sans", 12), bg="#222", fg="white", insertbackground="white",
                              relief="flat")
        self.entry.pack(side="left", ipady=6)
        self.entry.bind("<Return>", lambda e: self.answer(self.entry.get()))
        self.ask_btn = self.button(row, "Cuenta como trabajo", lambda: self.answer(self.entry.get()), bg="#c0392b",
                                   fg="white")
        self.ask_btn.pack(side="left", padx=8)
        self.msg = self.label(box, "", 13, "#e59866", pady=(14, 0))

    def answer(self, text):
        text = (text or "").strip()
        if len(text) < 4:
            self.msg.config(text="Escribe qué hiciste.")
            return
        self.msg.config(text="Pensándolo…")
        self.app.offpc_answer(text, source="desktop", done=lambda ok, m: self.show_result(m))

    def show_result(self, msg):
        if self.windows:
            self.msg.config(text=msg)


class LadderShutdownScreen(Overlay):
    """Último escalón de la escalera de castigos: cuenta regresiva y la compu se apaga."""

    def __init__(self, app, why, secs, on_done):
        super().__init__(app)
        self.why, self.left, self.on_done = why, int(secs), on_done
        self.counts = []
        self.build()
        self.countdown()

    def fill(self, box, main):
        self.label(box, "⏻ LA COMPU SE APAGA", 26, "#e74c3c", True)
        self.counts.append(self.label(box, fmt(self.left), 110, bold=True, pady=10))
        if not main:
            return
        self.label(box, self.why, 18, "#ccc")
        self.label(box, "Ya hubo aviso, minimizar, cerrar y bloquear. Lo que sigue es apagar.", 15, "#999",
                   pady=(14, 0))
        self.label(box, "Levántate, sal del cuarto y habla con alguien. Esto también pasa.", 17, "#f0d589", True,
                   pady=(26, 0))

    def countdown(self):
        if not self.windows:
            return
        if self.left <= 0:
            self.on_done()
            return
        self.counts = [c for c in self.counts if c.winfo_exists()]
        for c in self.counts:
            c.config(text=fmt(self.left))
        self.left -= 1
        self.main.after(1000, self.countdown)
