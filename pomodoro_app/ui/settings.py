"""Ventana de ajustes y selector de apps que distraen."""
import tkinter as tk
from tkinter import ttk

from ..config import save_config
from ..services.autostart import sync_autostart
from ..services.phone import app_name, installed_apps


def open_settings(app):
    if app.phase != "work" or app.overlay:
        return
    win = tk.Toplevel(app)
    win.title("Ajustes del Pomodoro")
    win.resizable(False, False)
    win.transient(app)
    if app.cfg["always_on_top"]:
        win.attributes("-topmost", True)
    outer = ttk.Frame(win, padding=14)
    outer.pack()
    vars_ = {}

    def section(title, row, col=0):
        f = ttk.LabelFrame(outer, text=f"  {title}  ", padding=10)
        f.grid(row=row, column=col, sticky="nsew", padx=6, pady=6)
        return f

    def num(f, r, key, label, lo, hi):
        ttk.Label(f, text=label).grid(row=r, column=0, sticky="w", pady=2)
        v = tk.IntVar(value=app.cfg[key])
        ttk.Spinbox(f, from_=lo, to=hi, textvariable=v, width=6).grid(row=r, column=1, sticky="e", padx=(10, 0))
        vars_[key] = ("int", v, lo, hi)

    def check(f, r, key, label):
        v = tk.BooleanVar(value=app.cfg[key])
        ttk.Checkbutton(f, text=label, variable=v).grid(row=r, column=0, columnspan=2, sticky="w", pady=2)
        vars_[key] = ("bool", v, None, None)

    def choice(f, r, key, label, options):
        ttk.Label(f, text=label).grid(row=r, column=0, sticky="w", pady=2)
        names = list(options.values())
        v = tk.StringVar(value=options.get(app.cfg[key], names[0]))
        ttk.Combobox(f, textvariable=v, values=names, state="readonly", width=24).grid(
            row=r, column=1, sticky="e", padx=(10, 0))
        vars_[key] = ("choice", v, options, None)

    f = section("Tiempos", 0)
    num(f, 0, "work_min", "Trabajo (min)", 1, 180)
    num(f, 1, "short_min", "Descanso corto (min)", 1, 60)
    num(f, 2, "long_min", "Descanso largo (min)", 1, 180)
    num(f, 3, "cycles", "Pomodoros antes del largo", 1, 12)
    num(f, 4, "warn_sec", "Aviso antes del descanso (seg)", 0, 600)
    check(f, 5, "auto_start_work", "Iniciar el siguiente pomodoro solo")
    check(f, 6, "sound", "Sonido")

    f = section("Descansos largos y programados", 0, 1)
    check(f, 0, "long_flex", "Dejarme salir a la mitad")
    check(f, 1, "long_flex_same_as_short", "Bloqueo al inicio y al final = descanso corto")
    num(f, 2, "long_lock_first_min", "…o bloqueado al inicio (min)", 1, 120)
    num(f, 3, "long_lock_last_min", "…y al final (min)", 1, 120)
    ttk.Label(f, text="Ej.: comida de 60 min y corto de 10 → los primeros\n10 min bloqueado, luego puedes salir, y en "
                      "los\núltimos 10 se vuelve a bloquear.", foreground="#777").grid(row=4, column=0, columnspan=2,
                                                                                     sticky="w", pady=(6, 0))

    f = section("Jornada", 1)
    b = app.bounds()
    ttk.Label(f, text=f"Sale de tu horario. Hoy: {b[0]:%H:%M}–{b[1]:%H:%M}" if b else
              "Hoy no hay trabajo en tu horario.", foreground="#555").grid(row=0, column=0, columnspan=2,
                                                                           sticky="w")
    check(f, 1, "autostart", "Abrir y arrancar sola al empezar el trabajo")
    check(f, 2, "overtime_mode", "Al terminar el trabajo del día: modo pesado")
    num(f, 3, "shutdown_grace_min", "Minutos para guardar antes de apagar", 1, 30)
    num(f, 4, "reminder_msg_sec", "Segundos que dura un aviso", 5, 120)

    f = section("Teléfono (ADB)", 1, 1)
    check(f, 0, "phone_enabled", "Conectar con el teléfono")
    choice(f, 1, "phone_break_end", "Al terminar el descanso",
           {"notify": "Solo avisar", "lock": "Avisar y bloquearlo"})
    num(f, 2, "phone_lock_delay_sec", "…si sigues en él tras (seg)", 10, 600)
    num(f, 6, "phone_read_sec", "Segundos para leer antes de bloquear", 5, 60)
    check(f, 7, "phone_dnd", "No molestar en el teléfono mientras trabajo")
    check(f, 8, "phone_distract", "Sacarme de las apps que distraen")
    ttk.Button(f, text="Elegir apps que distraen…", command=lambda: DistractingApps(app, win)).grid(
        row=9, column=0, columnspan=2, sticky="w", pady=(4, 0))
    check(f, 3, "phone_work_guard", "Vigilarlo durante el trabajo")
    num(f, 4, "phone_work_warn_min", "Avisar tras (min de uso)", 1, 60)
    num(f, 5, "phone_work_lock_min", "Bloquearlo tras (min de uso)", 1, 120)

    f = section("Bloqueo y ventana", 2)
    check(f, 0, "block_shortcuts", "Desactivar atajos de GNOME en el descanso")
    check(f, 1, "lock_if_escape", "Bloquear la sesión si escapo del descanso")
    check(f, 2, "allow_skip", "Permitir saltar descansos (modo blando)")
    check(f, 3, "focus_allow_skip", "En actividades «Enfocado», poder saltar los descansos")
    check(f, 4, "always_on_top", "Ventana siempre encima")
    num(f, 5, "widget_size", "Tamaño del widget (px)", 70, 300)

    def save():
        for key, (kind, v, lo, hi) in vars_.items():
            try:
                val = v.get()
            except tk.TclError:
                continue
            if kind == "int":
                val = min(hi, max(lo, int(val)))
            elif kind == "choice":
                val = next(k for k, name in lo.items() if name == val)
            app.cfg[key] = val
        if app.cfg["phone_work_lock_min"] <= app.cfg["phone_work_warn_min"]:
            app.cfg["phone_work_lock_min"] = app.cfg["phone_work_warn_min"] + 1
        save_config(app.cfg)
        try:
            sync_autostart(app.cfg)
        except OSError:
            pass
        if not app.running:
            app.phase_total = app.remaining = app.duration("work")
        app.done_in_cycle = min(app.done_in_cycle, app.cfg["cycles"] - 1)
        app.apply_topmost()
        if app.widget.size != app.cfg["widget_size"]:
            app.rebuild_widget()
        app.render()
        win.destroy()

    ttk.Button(outer, text="Guardar", command=save).grid(row=9, column=0, columnspan=2, pady=(10, 0))



class DistractingApps(tk.Toplevel):
    """Lista de apps del teléfono con casillas para marcar las que distraen."""

    def __init__(self, app, parent):
        super().__init__(parent)
        self.app = app
        self.title("Apps que distraen")
        self.transient(parent)
        self.geometry("380x520")
        f = ttk.Frame(self, padding=12)
        f.pack(fill="both", expand=True)
        ttk.Label(f, text="Durante el trabajo, si abres una de estas apps, te aviso y a los pocos segundos "
                          "te saco de ella.", wraplength=350).pack(anchor="w", pady=(0, 8))
        canvas = tk.Canvas(f, highlightthickness=0)
        sb = ttk.Scrollbar(f, orient="vertical", command=canvas.yview)
        inner = ttk.Frame(canvas)
        inner.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.create_window((0, 0), window=inner, anchor="nw")
        canvas.configure(yscrollcommand=sb.set)
        canvas.pack(side="left", fill="both", expand=True)
        sb.pack(side="left", fill="y")
        for w in (canvas, inner):
            w.bind_all("<Button-4>", lambda e: canvas.yview_scroll(-3, "units"))
            w.bind_all("<Button-5>", lambda e: canvas.yview_scroll(3, "units"))
        current = set(app.cfg["phone_distracting"])
        apps = installed_apps()
        known = {p for p, _ in apps}
        apps += [(p, app_name(p) + "  (no instalada)") for p in sorted(current - known)]
        self.vars = {}
        for pkg, name in apps:
            v = tk.BooleanVar(value=pkg in current)
            ttk.Checkbutton(inner, text=name, variable=v).pack(anchor="w")
            self.vars[pkg] = v
        if not apps:
            ttk.Label(inner, text="Abre el Panel ADB una vez para que se carguen tus apps.").pack()
        b = ttk.Frame(self, padding=(12, 0, 12, 12))
        b.pack(fill="x")
        ttk.Button(b, text="Guardar", command=self.save).pack(side="right")
        ttk.Button(b, text="Cancelar", command=self.close).pack(side="right", padx=6)
        self.protocol("WM_DELETE_WINDOW", self.close)

    def save(self):
        self.app.cfg["phone_distracting"] = sorted(p for p, v in self.vars.items() if v.get())
        save_config(self.app.cfg)
        self.close()

    def close(self):
        self.unbind_all("<Button-4>")
        self.unbind_all("<Button-5>")
        self.destroy()

