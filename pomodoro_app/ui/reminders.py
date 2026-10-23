"""Ventana de recordatorios (de una vez o recurrentes)."""
import datetime as dt
import random
import time
import tkinter as tk
from tkinter import messagebox, ttk

from ..config import save_config
from ..constants import DAY_LETTERS
from ..core.timefmt import parse_hhmm


def reminder_when(r):
    if r["type"] == "once":
        return f"{r['date']} · {r['time']}"
    days = r.get("days", [])
    if len(days) == 7:
        return f"Diario · {r['time']}"
    if sorted(days) == [0, 1, 2, 3, 4]:
        return f"Lun–Vie · {r['time']}"
    return " ".join(DAY_LETTERS[d] for d in sorted(days)) + f" · {r['time']}"


class RemindersWindow(tk.Toplevel):
    def __init__(self, app):
        super().__init__(app)
        self.app = app
        self.title("Recordatorios")
        self.configure(bg="#f3f4f7")
        self.resizable(False, False)
        self.current = None

        top = tk.Frame(self, bg="#f3f4f7", padx=16, pady=12)
        top.pack(fill="x")
        tk.Label(top, text="⏰ Recordatorios", bg="#f3f4f7", font=("Sans", 16, "bold")).pack(side="left")

        body = tk.Frame(self, bg="#f3f4f7", padx=16)
        body.pack(fill="both", expand=True)
        self.tree = ttk.Treeview(body, columns=("cuando", "dur", "bloq"), show="tree headings", height=12)
        self.tree.heading("#0", text="Recordatorio")
        self.tree.heading("cuando", text="Cuándo")
        self.tree.heading("dur", text="Duración")
        self.tree.heading("bloq", text="Bloquea")
        self.tree.column("#0", width=230)
        self.tree.column("cuando", width=170)
        self.tree.column("dur", width=80, anchor="center")
        self.tree.column("bloq", width=70, anchor="center")
        self.tree.tag_configure("off", foreground="#9ca3af")
        self.tree.pack(side="left", fill="y")
        self.tree.bind("<<TreeviewSelect>>", lambda e: self.load_selected())

        form = ttk.LabelFrame(body, text="  Detalle  ", padding=12)
        form.pack(side="left", fill="y", padx=(14, 0))
        ttk.Label(form, text="¿Qué te recuerdo?").grid(row=0, column=0, columnspan=4, sticky="w")
        self.text = tk.StringVar()
        ttk.Entry(form, textvariable=self.text, width=34).grid(row=1, column=0, columnspan=4, sticky="we",
                                                                pady=(2, 10))
        self.kind = tk.StringVar(value="repeat")
        ttk.Radiobutton(form, text="Se repite", value="repeat", variable=self.kind,
                        command=self.update_form).grid(row=2, column=0, columnspan=2, sticky="w")
        ttk.Radiobutton(form, text="Una sola vez", value="once", variable=self.kind,
                        command=self.update_form).grid(row=2, column=2, columnspan=2, sticky="w")

        self.days_frame = tk.Frame(form)
        self.days_frame.grid(row=3, column=0, columnspan=4, sticky="w", pady=(6, 0))
        self.day_vars = []
        for d, letter in enumerate(DAY_LETTERS):
            v = tk.BooleanVar(value=d < 5)
            tk.Checkbutton(self.days_frame, text=letter, variable=v, indicatoron=False, width=3, pady=3,
                           selectcolor="#3867f0", relief="flat", bg="#e5e7eb", cursor="hand2").pack(side="left",
                                                                                                   padx=1)
            self.day_vars.append(v)
        self.quick = quick = tk.Frame(form)
        quick.grid(row=4, column=0, columnspan=4, sticky="w", pady=(4, 0))
        ttk.Button(quick, text="Lun–Vie", command=lambda: self.set_days(range(5))).pack(side="left")
        ttk.Button(quick, text="Diario", command=lambda: self.set_days(range(7))).pack(side="left", padx=4)
        self.date_row = tk.Frame(form)
        self.date_row.grid(row=5, column=0, columnspan=4, sticky="w", pady=(6, 0))
        ttk.Label(self.date_row, text="Fecha (AAAA-MM-DD)").pack(side="left")
        self.date = tk.StringVar(value=dt.date.today().isoformat())
        ttk.Entry(self.date_row, textvariable=self.date, width=12).pack(side="left", padx=6)

        ttk.Label(form, text="Hora (HH:MM)").grid(row=6, column=0, columnspan=2, sticky="w", pady=(10, 0))
        self.time = tk.StringVar(value=(dt.datetime.now() + dt.timedelta(minutes=5)).strftime("%H:%M"))
        ttk.Entry(form, textvariable=self.time, width=7).grid(row=6, column=2, sticky="w", pady=(10, 0))
        ttk.Label(form, text="Duración (min)").grid(row=7, column=0, columnspan=2, sticky="w", pady=(6, 0))
        self.duration = tk.IntVar(value=0)
        ttk.Spinbox(form, from_=0, to=240, textvariable=self.duration, width=5).grid(row=7, column=2, sticky="w",
                                                                                   pady=(6, 0))
        ttk.Label(form, text="0 = solo un aviso a pantalla completa", foreground="#6b7280").grid(
            row=8, column=0, columnspan=4, sticky="w")
        self.lock = tk.BooleanVar(value=False)
        ttk.Checkbutton(form, text="Bloquear la pantalla (no se puede cerrar)", variable=self.lock).grid(
            row=9, column=0, columnspan=4, sticky="w", pady=(8, 0))
        self.enabled = tk.BooleanVar(value=True)
        ttk.Checkbutton(form, text="Activo", variable=self.enabled).grid(row=10, column=0, columnspan=4, sticky="w")

        btns = ttk.Frame(form)
        btns.grid(row=11, column=0, columnspan=4, sticky="we", pady=(14, 0))
        ttk.Button(btns, text="Guardar", command=self.save).pack(side="left")
        ttk.Button(btns, text="Nuevo", command=self.new).pack(side="left", padx=4)
        ttk.Button(btns, text="Probar", command=self.test).pack(side="left")
        ttk.Button(btns, text="Eliminar", command=self.delete).pack(side="right")

        tk.Label(self, bg="#f3f4f7", fg="#6b7280", padx=16, pady=10, justify="left",
                 text="Sin duración: aviso de unos segundos. Con duración y bloqueo: funciona como un descanso "
                      "(si es largo, a la mitad\npuedes salir). También te llega al teléfono.").pack(anchor="w")
        self.render()
        self.update_form()

    def set_days(self, days):
        for d, v in enumerate(self.day_vars):
            v.set(d in days)

    def update_form(self):
        if self.kind.get() == "once":
            self.days_frame.grid_remove()
            self.quick.grid_remove()
            self.date_row.grid()
        else:
            self.days_frame.grid()
            self.quick.grid()
            self.date_row.grid_remove()

    def render(self):
        self.tree.delete(*self.tree.get_children())
        for r in sorted(self.app.cfg["reminders"], key=lambda r: r["time"]):
            dur = f"{r['duration']} min" if r["duration"] else "aviso"
            self.tree.insert("", "end", iid=r["id"], text=r["text"], values=(reminder_when(r), dur,
                                                                             "sí" if r["lock"] else ""),
                             tags=[] if r.get("enabled", True) else ["off"])

    def find(self, rid):
        return next((r for r in self.app.cfg["reminders"] if r["id"] == rid), None)

    def load_selected(self):
        sel = self.tree.selection()
        r = self.find(sel[0]) if sel else None
        if not r:
            return
        self.current = r["id"]
        self.text.set(r["text"])
        self.kind.set(r["type"])
        self.set_days(r.get("days", []))
        self.date.set(r.get("date") or dt.date.today().isoformat())
        self.time.set(r["time"])
        self.duration.set(r["duration"])
        self.lock.set(r["lock"])
        self.enabled.set(r.get("enabled", True))
        self.update_form()

    def new(self):
        self.current = None
        self.tree.selection_remove(*self.tree.selection())
        self.text.set("")
        self.time.set((dt.datetime.now() + dt.timedelta(minutes=5)).strftime("%H:%M"))
        self.duration.set(0)
        self.lock.set(False)
        self.enabled.set(True)

    def collect(self):
        text = self.text.get().strip()
        t = parse_hhmm(self.time.get())
        if not text or not t:
            messagebox.showerror("Recordatorio", "Escribe qué recordar y una hora válida (HH:MM).", parent=self)
            return None
        r = {"id": self.current or f"r{time.time_ns():x}{random.randrange(4096):03x}", "text": text, "type": self.kind.get(),
             "time": t.strftime("%H:%M"), "lock": self.lock.get(), "enabled": self.enabled.get()}
        try:
            r["duration"] = max(0, int(self.duration.get()))
        except (tk.TclError, ValueError):
            r["duration"] = 0
        if r["type"] == "once":
            try:
                r["date"] = dt.date.fromisoformat(self.date.get().strip()).isoformat()
            except ValueError:
                messagebox.showerror("Recordatorio", "Fecha inválida. Usa AAAA-MM-DD.", parent=self)
                return None
        else:
            r["days"] = [d for d, v in enumerate(self.day_vars) if v.get()]
            if not r["days"]:
                messagebox.showerror("Recordatorio", "Elige al menos un día.", parent=self)
                return None
        return r

    def save(self):
        r = self.collect()
        if not r:
            return
        rems = [x for x in self.app.cfg["reminders"] if x["id"] != r["id"]]
        rems.append(r)
        self.app.cfg["reminders"] = rems
        self.current = r["id"]
        save_config(self.app.cfg)
        self.render()
        self.tree.selection_set(r["id"])

    def delete(self):
        if not self.current:
            return
        self.app.cfg["reminders"] = [x for x in self.app.cfg["reminders"] if x["id"] != self.current]
        save_config(self.app.cfg)
        self.new()
        self.render()

    def test(self):
        r = self.collect()
        if r:
            self.app.pending_reminders.append(dict(r, duration=min(r["duration"], 1)))

