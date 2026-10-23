"""Ventana principal: construcción, cambio a widget/barra y dibujo del estado actual (`view` → `render`)."""
import datetime as dt
import time
import tkinter as tk

from ..config import save_config
from ..constants import CARD_BG, COLORS, DARK_BG, MUTED_FG
from ..core.schedule import act_label, activity, block_at, is_work, next_change
from ..core.timefmt import fmt
from ..services.system import notify
from .reminders import RemindersWindow
from .schedule_editor import ScheduleEditor
from .settings import open_settings
from .theme import on_dark, text_color
from .widget import Widget


class MainWindowMixin:
    # ---------- construcción ----------
    def _build(self):
        self.configure(bg=DARK_BG)
        root = tk.Frame(self, bg=DARK_BG, padx=22, pady=16)
        root.pack(fill="both", expand=True)

        # Aviso del teléfono (solo aparece cuando hace falta conectarlo)
        self.banner = tk.Label(root, font=("Sans", 10, "bold"), padx=12, pady=8, wraplength=350,
                               cursor="hand2", justify="left")
        self.banner.bind("<Button-1>", lambda e: self.retry_phone())

        # Actividad del horario
        self.act_lbl = tk.Label(root, font=("Sans", 11, "bold"), padx=14, pady=6)
        self.act_lbl.pack()
        self.next_lbl = tk.Label(root, font=("Sans", 9), bg=DARK_BG, fg=MUTED_FG)
        self.next_lbl.pack(pady=(4, 0))

        # Anillo con el tiempo
        size = 250
        self.ring = c = tk.Canvas(root, width=size, height=size, bg=DARK_BG, highlightthickness=0)
        c.pack(pady=(10, 0))
        box = (14, 14, size - 14, size - 14)
        c.create_oval(*box, outline="#2a2e37", width=14)
        self.ring_arc = c.create_arc(*box, start=90, extent=-359.9, style="arc", width=14, outline="#c0392b")
        self.ring_phase = c.create_text(size / 2, size * 0.32, fill=MUTED_FG, font=("Sans", 11, "bold"))
        self.ring_time = c.create_text(size / 2, size * 0.5, fill="white", font=("Sans", 46, "bold"))
        self.ring_dots = c.create_text(size / 2, size * 0.68, fill=MUTED_FG, font=("Sans", 13))
        self.status_lbl = tk.Label(root, bg=DARK_BG, fg="#d6d9df", font=("Sans", 10), wraplength=360)
        self.status_lbl.pack(pady=(6, 0))

        # Controles
        self.btn_main = tk.Button(root, text="▶  Iniciar", command=self.toggle, relief="flat", bd=0,
                                  highlightthickness=0,
                                  font=("Sans", 13, "bold"), pady=9, cursor="hand2")
        self.btn_main.pack(fill="x", pady=(12, 8))
        row = tk.Frame(root, bg=DARK_BG)
        row.pack(fill="x")
        self.btn_reset = self._ghost(row, "↺  Reiniciar", self.reset)
        self.btn_reset.pack(side="left", expand=True, fill="x", padx=(0, 4))
        self.btn_break = self._ghost(row, "☕  Descanso ya", self.break_now)
        self.btn_break.pack(side="left", expand=True, fill="x", padx=(4, 0))

        # Barra de herramientas
        tk.Frame(root, bg="#2a2e37", height=1).pack(fill="x", pady=(16, 8))
        bar = tk.Frame(root, bg=DARK_BG)
        bar.pack(fill="x")
        self.pin_btn = None
        for icon, text, cmd in (("📅", "Horario", self.open_schedule), ("⏰", "Recordatorios", self.open_reminders),
                                ("◉", "Widget", self.enter_widget), ("📌", "Encima", self.toggle_topmost),
                                ("⚙", "Ajustes", self.open_settings)):
            b = self._tool(bar, icon, text, cmd)
            b.pack(side="left", expand=True, fill="x")
            if text == "Encima":
                self.pin_btn = b
        self.stats_lbl = tk.Label(root, bg=DARK_BG, fg=MUTED_FG, font=("Sans", 9), wraplength=360)
        self.stats_lbl.pack(side="bottom", pady=(4, 0))
        self.phone_lbl = tk.Label(root, bg=DARK_BG, fg=MUTED_FG, font=("Sans", 9), cursor="hand2")
        self.phone_lbl.pack(side="bottom", pady=(8, 0))
        self.phone_lbl.bind("<Button-1>", lambda e: self.retry_phone())

    def _ghost(self, parent, text, cmd):
        return tk.Button(parent, text=text, command=cmd, relief="flat", bd=0, highlightthickness=0,
                         bg=CARD_BG, fg="#e5e7eb",
                         activebackground="#2a2e37", activeforeground="white", font=("Sans", 10, "bold"),
                         pady=7, cursor="hand2", disabledforeground="#555a63")

    def _tool(self, parent, icon, text, cmd):
        b = tk.Label(parent, text=f"{icon}\n{text}", bg=DARK_BG, fg="#c3c7cf", font=("Sans", 9), pady=6,
                     cursor="hand2")
        b.bind("<Button-1>", lambda e: cmd())
        b.bind("<Enter>", lambda e: b.config(bg=CARD_BG))
        b.bind("<Leave>", lambda e: b.config(bg=DARK_BG))
        return b

    # ---------- ventanas secundarias ----------
    def open_schedule(self):
        ScheduleEditor(self)

    def open_reminders(self):
        RemindersWindow(self)

    def open_settings(self):
        open_settings(self)

    # ---------- ventana / widget ----------
    def apply_topmost(self):
        on = bool(self.cfg["always_on_top"])
        self.attributes("-topmost", on)
        self.pin_btn.config(text="📌\nEncima ✓" if on else "📌\nEncima", fg="#ffffff" if on else "#c3c7cf")

    def toggle_topmost(self):
        self.cfg["always_on_top"] = not self.cfg["always_on_top"]
        save_config(self.cfg)
        self.apply_topmost()

    def rebuild_widget(self):
        """Vuelve a crear el widget (p. ej. al cambiar su tamaño)."""
        self.widget.destroy()
        self.widget = Widget(self)
        if self.cfg["widget_mode"]:
            self.widget.show()

    def enter_widget(self):
        self.cfg["widget_mode"] = True
        save_config(self.cfg)
        self.withdraw()
        if not self.overlay:
            self.widget.show()

    def exit_widget(self):
        self.cfg["widget_mode"] = False
        save_config(self.cfg)
        self.widget.hide()
        self.show_main()

    def show_main(self):
        if self.cfg["widget_mode"]:
            self.widget.show()
            return
        self.deiconify()
        self.lift()
        self.apply_topmost()

    def hide_all(self):
        """Deja solo el icono de la barra (ni ventana ni widget)."""
        if not self.tray_ok:
            return
        self.widget.hide()
        self.withdraw()
        if not self.hidden_notice:
            self.hidden_notice = True
            notify("El Pomodoro sigue corriendo", "Lo encuentras en la barra superior, junto al reloj.")

    def on_close(self):
        """La X de la ventana: si hay icono en la barra, solo se oculta ahí."""
        if self.tray_ok:
            self.withdraw()
            if self.cfg["widget_mode"]:
                self.widget.show()
            if not self.hidden_notice:
                self.hidden_notice = True
                notify("El Pomodoro sigue corriendo",
                       "Lo encuentras en la barra superior. Para salir: menú del icono → Salir.")
            return
        self.quit_app()

    # ---------- teléfono ----------
    def phone_prompt(self):
        """Invita a conectar el cable cuando el teléfono no se puede alcanzar (sin bloquear nada)."""
        if not self.phone.needs_action():
            if self.banner.winfo_ismapped():
                self.banner.pack_forget()
            return
        conn = self.phone.state["conn"]
        text = {"unauthorized": "📱 Acepta «Permitir depuración USB» en la pantalla del teléfono.",
                "needs_cable": "🔌 Conecta el cable USB al teléfono un momento para reactivar su control "
                               "(después ya puedes quitarlo). Toca aquí para reintentar.",
                "never": "🔌 Conecta tu teléfono por cable (con depuración USB) para enlazarlo."}[conn]
        blink = int(time.time()) % 2 == 0
        self.banner.config(text=text, bg="#f5a623" if blink else "#c47f12", fg="#111")
        if not self.banner.winfo_ismapped():
            self.banner.pack(before=self.act_lbl, fill="x", pady=(0, 10))
        # Notificación de escritorio cada 30 min, solo en horario despierto.
        if time.time() - self.last_phone_nag > 30 * 60 and 8 <= dt.datetime.now().hour < 23:
            self.last_phone_nag = time.time()
            notify("📱 Tu teléfono está desconectado", text, urgent=self.phase == "work" and self.running)

    # ---------- vista ----------
    def view(self):
        """Todo lo que hay que mostrar (ventana y widget) en este momento."""
        now = dt.datetime.now()
        aid, _, bend = block_at(self.cfg, now)
        a = activity(self.cfg, aid)
        v = {"act_label": a["label"] if a else "", "act_color": a["color"] if a else "#444",
             "act_until": bend, "frac": self.remaining / self.phase_total if self.phase_total else 1,
             "time": fmt(self.remaining), "status": ""}
        if self.shutdown_at:
            left = self.shutdown_at - time.time()
            v.update(accent=COLORS["shutdown"], title="APAGANDO", short="apagando", time=fmt(left),
                     frac=left / self.shutdown_total, status="Guarda tu trabajo: la compu se apaga sola.")
            return v
        if self.phase == "work":
            work_color = a["color"] if a and is_work(a["kind"]) else COLORS["work"]
            if not self.running and self.cfg["autostart"] and self.before_start() \
                    and self.auto_date != dt.date.today():
                v.update(accent=COLORS["waiting"], title="EN ESPERA", short="en espera", frac=1,
                         status=f"Tu jornada empieza a las {self.bounds()[0]:%H:%M}. Arranco sola.")
                return v
            ot = self.overtime if self.running else self.in_overtime()
            v.update(accent=COLORS["overtime"] if ot else work_color,
                     title="HORAS EXTRA" if ot else "TRABAJO", short="extra" if ot else "trabajo")
            if not self.running:
                v.update(title="EN PAUSA", short="pausa", status="Pulsa Iniciar cuando estés listo.")
            elif ot:
                v["status"] = "Tu jornada ya terminó: pomodoros cortos y descansos dobles."
            elif self.focus_now():
                v["status"] = "🎯 Enfocado: si estás en algo importante, puedes saltar el descanso."
            return v
        v.update(accent=self.phase_color, title=self.phase_title.upper()[:22],
                 short={"short": "descanso", "long": "descanso", "sched": "descanso",
                        "remind": "recordatorio"}[self.phase])
        if self.break_free:
            to_lock = self.remaining - (self.flex_limits() or (0, 0))[1]
            v.update(short="libre", status=f"Descanso libre: la pantalla se vuelve a bloquear en {fmt(to_lock)}.")
        return v

    def render(self):
        v = self.view()
        accent = on_dark(v["accent"])
        c = self.ring
        c.itemconfig(self.ring_arc, outline=accent, extent=-359.9 * max(0.0, min(1.0, v["frac"])))
        c.itemconfig(self.ring_phase, text=v["title"], fill=accent)
        c.itemconfig(self.ring_time, text=v["time"])
        n = self.cfg["cycles"]
        c.itemconfig(self.ring_dots, text=" ".join("●" if i < self.done_in_cycle else "○" for i in range(n)))
        self.status_lbl.config(text=v["status"])

        if self.active_reminder:
            self.act_lbl.config(text=f"⏰ {self.active_reminder[0]} · quedan "
                                     f"{fmt(self.active_reminder[1] - time.time())}", bg="#f5b041", fg="#111")
        elif v["act_label"]:
            self.act_lbl.config(text=f"{v['act_label']}   ·   hasta las {v['act_until']:%H:%M}",
                                bg=v["act_color"], fg=text_color(v["act_color"]))
        else:
            self.act_lbl.config(text="Nada en tu horario ahora", bg=CARD_BG, fg=MUTED_FG)
        nxt, start = next_change(self.cfg, dt.datetime.now())
        self.next_lbl.config(text=f"Después: {act_label(self.cfg, nxt)} a las {start:%H:%M}" if nxt else "")

        work = self.phase == "work" and not self.shutdown_at
        playing = self.running and work
        self.btn_main.config(text="⏸  Pausar" if playing else "▶  Iniciar", state="normal" if work else "disabled",
                             bg=accent, fg=text_color(accent), activebackground=accent,
                             activeforeground=text_color(accent), disabledforeground="#777")
        if not work:
            self.btn_main.config(bg=CARD_BG)
        self.btn_reset.config(state="normal" if work else "disabled")
        self.btn_break.config(state="normal" if work else "disabled")
        today = dt.date.today().isoformat()
        n = self.cfg["stats"].get(today, 0)
        b = self.bounds()
        day = f"jornada {b[0]:%H:%M}–{b[1]:%H:%M}" if b else "hoy sin trabajo"
        phone_min = int(self.cfg.get("phone_stats", {}).get(today, 0) // 60)
        self.stats_lbl.config(text=f"Hoy: {n} pomodoro{'s' if n != 1 else ''}  ·  {day}  ·  "
                                   f"📱 {phone_min} min en el cel trabajando")
        self.phone_lbl.config(text=self.phone.summary(), fg="#f5a623" if self.phone.needs_action() else MUTED_FG)
        self.title(f"{v['time']} · {v['title'].capitalize()}")
        v["phone_alert"] = self.phone.needs_action()
        if self.widget.winfo_viewable():
            self.widget.render(v)
        self.update_tray(v)
