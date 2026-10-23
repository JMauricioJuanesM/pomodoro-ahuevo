"""Widget: círculo pequeño, siempre encima, con el tiempo y la actividad actual."""
import tkinter as tk

from ..config import save_config
from ..constants import MUTED_FG
from ..services.system import monitors
from .theme import on_dark, round_rect, round_rect_points, text_color
from .x11 import make_round


class Widget(tk.Toplevel):
    """Círculo pequeño, siempre encima, que se arrastra a cualquier esquina.

    El anillo toma el color de la actividad actual del horario y abajo muestra su icono/texto.
    """

    BG = "#16181d"

    def __init__(self, app):
        super().__init__(app)
        self.app = app
        self.size = s = app.cfg["widget_size"]
        self.overrideredirect(True)
        self.attributes("-topmost", True)
        self.configure(bg=self.BG)
        self.canvas = c = tk.Canvas(self, width=s, height=s, bg=self.BG, highlightthickness=0, cursor="hand2")
        c.pack()
        pad, ring = s * 0.06, max(5, s // 15)
        box = (pad, pad, s - pad, s - pad)
        c.create_oval(*box, outline="#2a2e37", width=ring)
        self.arc = c.create_arc(*box, start=90, extent=-359.9, style="arc", outline="#c0392b", width=ring)
        self.phase_txt = c.create_text(s / 2, s * 0.29, fill=MUTED_FG, font=("Sans", max(6, int(s / 16))))
        self.time_txt = c.create_text(s / 2, s * 0.47, fill="white", font=("Sans", int(s / 5.8), "bold"))
        self.pill_y = s * 0.70
        self.pill = round_rect(c, 0, 0, 1, 1, 1, fill="#333", outline="")
        self.act_txt = c.create_text(s / 2, self.pill_y, fill="white", font=("Sans", max(6, int(s / 16)), "bold"))
        d = s * 0.075   # puntito ámbar: el teléfono necesita que lo conectes
        self.alert = c.create_oval(s * 0.78 - d, s * 0.2 - d, s * 0.78 + d, s * 0.2 + d, fill="#f5a623",
                                   outline=self.BG, width=2, state="hidden")

        self.press = None
        self.click_job = None
        c.bind("<ButtonPress-1>", self.on_press)
        c.bind("<B1-Motion>", self.on_drag)
        c.bind("<ButtonRelease-1>", self.on_release)
        c.bind("<Double-Button-1>", self.on_double)
        # Al soltar (no al presionar): si no, la ventana sin bordes cierra el menú en cuanto sueltas el botón.
        c.bind("<ButtonRelease-3>", self.on_menu)

        x, y = app.cfg["widget_pos"] or self.default_pos()
        self.geometry(f"{s}x{s}+{x}+{y}")
        self.update_idletasks()
        self.after(50, lambda: make_round(self, s))
        self.withdraw()

    def default_pos(self):
        mons = monitors()
        x, y, w, h = mons[0] if mons else (0, 0, self.winfo_screenwidth(), self.winfo_screenheight())
        return x + w - self.size - 24, y + h - self.size - 64

    def show(self):
        self.deiconify()
        self.attributes("-topmost", True)
        self.lift()
        self.after(50, lambda: make_round(self, self.size))

    def hide(self):
        self.withdraw()

    def render(self, view):
        c = self.canvas
        c.itemconfig(self.arc, outline=on_dark(view["accent"]),
                     extent=-359.9 * max(0.0, min(1.0, view["frac"])))
        c.itemconfig(self.time_txt, text=view["time"])
        c.itemconfig(self.phase_txt, text=view["short"])
        c.itemconfig(self.alert, state="normal" if view.get("phone_alert") else "hidden")
        label, color = view["act_label"], view["act_color"]
        if label:
            text = label if len(label) <= 11 else label[:10] + "…"
            c.itemconfig(self.act_txt, text=text, fill=text_color(color), state="normal")
            x1, y1, x2, y2 = c.bbox(self.act_txt)   # la etiqueta mide lo que mide su texto
            ph = (y2 - y1) / 2 + 1
            c.coords(self.pill, *round_rect_points(x1 - ph, self.pill_y - ph, x2 + ph, self.pill_y + ph, ph))
            c.itemconfig(self.pill, fill=color, state="normal")
        else:
            c.itemconfig(self.pill, state="hidden")
            c.itemconfig(self.act_txt, state="hidden")

    # ---------- ratón ----------
    def on_press(self, e):
        self.press = (e.x_root, e.y_root, self.winfo_x(), self.winfo_y(), False)

    def on_drag(self, e):
        if not self.press:
            return
        x0, y0, wx, wy, moved = self.press
        dx, dy = e.x_root - x0, e.y_root - y0
        if moved or abs(dx) + abs(dy) > 4:
            self.press = (x0, y0, wx, wy, True)
            self.geometry(f"+{wx + dx}+{wy + dy}")

    def on_release(self, e):
        if not self.press:
            return
        moved = self.press[4]
        self.press = None
        if moved:
            self.app.cfg["widget_pos"] = [self.winfo_x(), self.winfo_y()]
            save_config(self.app.cfg)
        else:  # click sencillo: menú (esperando por si es doble click)
            self.click_job = self.after(260, lambda: self.on_menu(e))

    def on_double(self, e):
        if self.click_job:
            self.after_cancel(self.click_job)
            self.click_job = None
        self.app.exit_widget()

    def on_menu(self, e):
        m = tk.Menu(self, tearoff=0)
        m.add_command(label="Abrir ventana", command=self.app.exit_widget)
        if self.app.phase == "work" and not self.app.shutdown_at:
            m.add_command(label="Pausar" if self.app.running else "Iniciar", command=self.app.toggle)
            m.add_command(label="Descanso ya", command=self.app.break_now)
        m.add_command(label="📅 Horario", command=self.app.open_schedule)
        m.add_command(label="⏰ Recordatorios", command=self.app.open_reminders)
        m.add_separator()
        m.add_command(label=self.app.phone.summary(), state="disabled")
        if self.app.phone.needs_action():
            m.add_command(label="🔌 Reintentar conexión", command=self.app.retry_phone)
        m.add_separator()
        if self.app.tray_ok:
            m.add_command(label="Ocultar (queda en la barra)", command=self.app.hide_all)
        m.add_command(label="Salir", command=self.app.quit_app)
        self.click_job = None
        m.tk_popup(e.x_root + 2, e.y_root + 2)
        m.grab_release()

