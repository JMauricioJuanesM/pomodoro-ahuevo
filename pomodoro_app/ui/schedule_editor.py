"""Editor del horario semanal (como hoja de cálculo) y diálogo de actividad."""
import copy
import datetime as dt
import random
import time
import tkinter as tk
from tkinter import colorchooser, messagebox, ttk

from ..config import default_schedule, save_config
from ..constants import DAY_LETTERS, DAYS, EMOJIS, KIND_ICONS, KINDS, PRESET_COLORS
from ..core.schedule import is_work
from ..core.timefmt import hour_label
from ..services.autostart import sync_autostart
from .theme import text_color


class ActivityDialog(tk.Toplevel):
    """Crear o editar una actividad: texto/emoji, color y tipo."""

    def __init__(self, editor, aid=None):
        super().__init__(editor)
        self.editor, self.aid = editor, aid
        self.result = None
        a = editor.acts.get(aid) or {"label": "", "color": "#00ffff", "kind": "trabajo"}
        self.title("Actividad")
        self.resizable(False, False)
        self.transient(editor)
        f = ttk.Frame(self, padding=18)
        f.pack()

        ttk.Label(f, text="Texto o emoji").grid(row=0, column=0, sticky="w")
        self.label = tk.StringVar(value=a["label"])
        e = ttk.Entry(f, textvariable=self.label, width=26, font=("Sans", 12))
        e.grid(row=1, column=0, columnspan=2, sticky="we", pady=(2, 6))
        e.focus_set()
        em = tk.Frame(f)
        em.grid(row=2, column=0, columnspan=2, sticky="w", pady=(0, 12))
        for i, ch in enumerate(EMOJIS.split()):
            tk.Button(em, text=ch, relief="flat", font=("Sans", 12), padx=2, pady=0, cursor="hand2",
                      command=lambda c=ch: self.label.set((self.label.get() + " " + c).strip())).grid(
                row=i // 11, column=i % 11)

        ttk.Label(f, text="Color").grid(row=3, column=0, sticky="w")
        self.color = a["color"]
        sw = tk.Frame(f)
        sw.grid(row=4, column=0, columnspan=2, sticky="w", pady=(2, 4))
        for i, c in enumerate(PRESET_COLORS):
            tk.Button(sw, bg=c, activebackground=c, width=2, relief="flat", cursor="hand2",
                      command=lambda c=c: self.set_color(c)).grid(row=0, column=i, padx=1)
        row = tk.Frame(f)
        row.grid(row=5, column=0, columnspan=2, sticky="w", pady=(2, 12))
        self.preview = tk.Label(row, text=" Vista previa ", width=16, font=("Sans", 11, "bold"), pady=4)
        self.preview.pack(side="left")
        ttk.Button(row, text="Otro color…", command=self.pick_color).pack(side="left", padx=8)

        ttk.Label(f, text="¿Qué pasa en esta actividad?").grid(row=6, column=0, sticky="w")
        self.kind = tk.StringVar(value=a["kind"])
        for i, (k, text) in enumerate(KINDS.items()):
            ttk.Radiobutton(f, text=text, value=k, variable=self.kind).grid(row=7 + i, column=0, columnspan=2,
                                                                             sticky="w")

        btns = ttk.Frame(f)
        btns.grid(row=7 + len(KINDS), column=0, columnspan=2, sticky="we", pady=(16, 0))
        ttk.Button(btns, text="Guardar", command=self.save).pack(side="right")
        ttk.Button(btns, text="Cancelar", command=self.destroy).pack(side="right", padx=6)
        if aid:
            ttk.Button(btns, text="Eliminar", command=self.delete).pack(side="left")
        self.label.trace_add("write", lambda *_: self.update_preview())
        self.update_preview()
        self.grab_set()

    def set_color(self, c):
        self.color = c
        self.update_preview()

    def pick_color(self):
        c = colorchooser.askcolor(self.color, parent=self)[1]
        if c:
            self.set_color(c)

    def update_preview(self):
        self.preview.config(text=self.label.get() or " Vista previa ", bg=self.color, fg=text_color(self.color))

    def save(self):
        label = self.label.get().strip()
        if not label:
            return
        self.result = ("save", {"label": label, "color": self.color, "kind": self.kind.get()})
        self.destroy()

    def delete(self):
        if messagebox.askyesno("Eliminar", "¿Eliminar esta actividad? Sus celdas quedarán vacías.", parent=self):
            self.result = ("delete", None)
            self.destroy()


class ScheduleEditor(tk.Toplevel):
    """El horario como hoja de cálculo: arrastra para seleccionar celdas y elige una actividad."""

    LINE = "#c8ccd4"

    def __init__(self, app):
        super().__init__(app)
        self.app = app
        self.title("Horario")
        self.configure(bg="#f3f4f7")
        self.acts = copy.deepcopy(app.cfg["activities"])
        self.sched = copy.deepcopy(app.cfg["schedule"])
        self.h0, self.h1 = app.cfg["grid_hours"]
        self.sel = set()
        self.anchor = None
        self.cells = {}

        top = tk.Frame(self, bg="#f3f4f7", padx=16, pady=12)
        top.pack(fill="x")
        tk.Label(top, text="📅 Tu horario", bg="#f3f4f7", font=("Sans", 16, "bold")).pack(side="left")
        tk.Label(top, text="Arrastra sobre las celdas para seleccionarlas y luego elige una actividad a la derecha.",
                 bg="#f3f4f7", fg="#6b7280").pack(side="left", padx=14)

        body = tk.Frame(self, bg="#f3f4f7", padx=16)
        body.pack(fill="both", expand=True)
        self.grid_frame = tk.Frame(body, bg=self.LINE)
        self.grid_frame.pack(side="left", anchor="n")
        self._build_grid()

        self.palette = tk.Frame(body, bg="#f3f4f7", padx=16)
        self.palette.pack(side="left", fill="y", anchor="n")
        self._build_palette()

        bottom = tk.Frame(self, bg="#f3f4f7", padx=16, pady=12)
        bottom.pack(fill="x")
        self.summary = tk.Label(bottom, bg="#f3f4f7", fg="#6b7280", justify="left")
        self.summary.pack(side="left")
        ttk.Button(bottom, text="Guardar", command=self.save).pack(side="right")
        ttk.Button(bottom, text="Cancelar", command=self.destroy).pack(side="right", padx=6)
        ttk.Button(bottom, text="Restaurar mi horario original", command=self.restore).pack(side="right", padx=12)
        self.render()

    # ---------- cuadrícula ----------
    def _build_grid(self):
        g = self.grid_frame
        tk.Label(g, text="", bg="#e8eaef", width=6).grid(row=0, column=0, padx=(0, 1), pady=(0, 1), sticky="nsew")
        today = dt.date.today().weekday()
        for d, name in enumerate(DAYS):
            tk.Label(g, text=name, bg="#dce6ff" if d == today else "#e8eaef", font=("Sans", 10, "bold"),
                     width=12, pady=4).grid(row=0, column=d + 1, padx=(0, 1), pady=(0, 1), sticky="nsew")
        for r, h in enumerate(range(self.h0, self.h1 + 1), start=1):
            tk.Label(g, text=hour_label(h), bg="#e8eaef", font=("Sans", 9, "bold"), width=6).grid(
                row=r, column=0, padx=(0, 1), pady=(0, 1), sticky="nsew")
            for d in range(7):
                c = tk.Label(g, width=12, height=1, font=("Sans", 9), cursor="hand2", highlightthickness=2,
                             pady=0)
                c.grid(row=r, column=d + 1, padx=(0, 1), pady=(0, 1), sticky="nsew")
                c.bind("<ButtonPress-1>", lambda e, k=(d, h): self.press(k))
                c.bind("<B1-Motion>", self.drag)
                c.bind("<Double-Button-1>", lambda e, k=(d, h): self.edit_cell(k))
                c.cell_key = (d, h)
                self.cells[(d, h)] = c

    def press(self, key):
        self.anchor = key
        self.sel = {key}
        self.render()

    def drag(self, e):
        w = self.winfo_containing(e.x_root, e.y_root)
        key = getattr(w, "cell_key", None)
        if not key or not self.anchor:
            return
        (d0, h0), (d1, h1) = self.anchor, key
        self.sel = {(d, h) for d in range(min(d0, d1), max(d0, d1) + 1) for h in range(min(h0, h1), max(h0, h1) + 1)}
        self.render()

    def edit_cell(self, key):
        aid = self.sched.get(str(key[0]), {}).get(str(key[1]))
        if aid:
            self.edit_activity(aid)

    def render(self):
        now = dt.datetime.now()
        for (d, h), c in self.cells.items():
            aid = self.sched.get(str(d), {}).get(str(h))
            a = self.acts.get(aid)
            bg = a["color"] if a else "white"
            border = "#3867f0" if (d, h) in self.sel else ("#111111" if (d, h) == (now.weekday(), now.hour) else bg)
            c.config(text=a["label"] if a else "", bg=bg, fg=text_color(bg), highlightbackground=border,
                     highlightcolor=border)
        self.render_summary()

    def render_summary(self):
        lines = []
        for d in range(7):
            hours = [int(h) for h, a in self.sched.get(str(d), {}).items()
                     if is_work((self.acts.get(a) or {}).get("kind"))]
            lines.append(f"{DAY_LETTERS[d]}: {min(hours)}–{max(hours) + 1} h" if hours else f"{DAY_LETTERS[d]}: libre")
        self.summary.config(text="Jornada de trabajo →  " + "   ".join(lines))

    # ---------- paleta ----------
    def _build_palette(self):
        for w in self.palette.winfo_children():
            w.destroy()
        tk.Label(self.palette, text="Actividades", bg="#f3f4f7", font=("Sans", 11, "bold")).pack(anchor="w")
        tk.Label(self.palette, text="Clic = pintar la selección · ✎ = editar", bg="#f3f4f7", fg="#6b7280").pack(
            anchor="w", pady=(0, 8))
        for kind in KINDS:
            items = [(aid, a) for aid, a in self.acts.items() if a["kind"] == kind]
            if not items:
                continue
            tk.Label(self.palette, text=f"{KIND_ICONS[kind]} {KINDS[kind]}", bg="#f3f4f7", fg="#374151",
                     font=("Sans", 9, "bold")).pack(anchor="w", pady=(8, 2))
            grid = tk.Frame(self.palette, bg="#f3f4f7")
            grid.pack(fill="x")
            for i, (aid, a) in enumerate(items):
                row = tk.Frame(grid, bg="#f3f4f7")
                row.grid(row=i // 2, column=i % 2, sticky="w", pady=1, padx=(0, 6))
                chip = tk.Label(row, text=a["label"], bg=a["color"], fg=text_color(a["color"]), width=12,
                                font=("Sans", 10, "bold"), pady=2, cursor="hand2")
                chip.pack(side="left")
                chip.bind("<Button-1>", lambda e, aid=aid: self.paint(aid))
                tk.Button(row, text="✎", relief="flat", bg="#f3f4f7", cursor="hand2", padx=2, pady=0,
                          command=lambda aid=aid: self.edit_activity(aid)).pack(side="left", padx=2)
        row = tk.Frame(self.palette, bg="#f3f4f7")
        row.pack(fill="x", pady=(12, 0))
        ttk.Button(row, text="🧽 Vaciar celdas", command=lambda: self.paint(None)).pack(side="left")
        ttk.Button(row, text="＋ Nueva actividad", command=lambda: self.edit_activity(None)).pack(side="left", padx=6)

    def paint(self, aid):
        if not self.sel:
            messagebox.showinfo("Horario", "Primero arrastra sobre las celdas que quieres cambiar.", parent=self)
            return
        for d, h in self.sel:
            day = self.sched.setdefault(str(d), {})
            if aid:
                day[str(h)] = aid
            else:
                day.pop(str(h), None)
        self.sel = set()
        self.render()

    def edit_activity(self, aid):
        dlg = ActivityDialog(self, aid)
        self.wait_window(dlg)
        if not dlg.result:
            return
        action, data = dlg.result
        if action == "delete":
            del self.acts[aid]
            for day in self.sched.values():
                for h in [h for h, a in day.items() if a == aid]:
                    del day[h]
        else:
            new_id = aid or f"a{time.time_ns():x}{random.randrange(4096):03x}"
            self.acts[new_id] = data
            if not aid and self.sel:
                self.paint(new_id)
        self._build_palette()
        self.render()

    def restore(self):
        if messagebox.askyesno("Horario", "¿Volver a tu horario original (defaults.json)? Se pierden los cambios.", parent=self):
            self.acts, self.sched = default_schedule()
            self._build_palette()
            self.render()

    def save(self):
        self.app.cfg["activities"] = self.acts
        self.app.cfg["schedule"] = self.sched
        save_config(self.app.cfg)
        try:
            sync_autostart(self.app.cfg)
        except OSError:
            pass
        self.app.render()
        self.destroy()

