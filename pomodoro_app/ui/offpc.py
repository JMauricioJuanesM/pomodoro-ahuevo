"""Al volver a la compu tras un rato fuera sin haber contestado: «¿qué hiciste estos N min?»."""
import tkinter as tk

BG = "#1b1f2a"


class AwayDialog(tk.Toplevel):
    """Ventana chica (no pantalla completa). Cerrarla equivale a «me fui»: ese rato no cuenta."""

    def __init__(self, app, away, options):
        super().__init__(app)
        self.app = app
        self.title("Faro · ¿Qué hiciste?")
        self.configure(bg=BG, padx=22, pady=18)
        self.resizable(False, False)
        self.attributes("-topmost", True)
        self.protocol("WM_DELETE_WINDOW", lambda: self.answer(options[-1]))
        tk.Label(self, text=f"Estuviste {away} lejos de la compu", bg=BG, fg="#f5b041",
                 font=("Sans", 15, "bold")).pack(anchor="w")
        tk.Label(self, text="¿Era trabajo? Si cuenta, te devuelvo ese rato al pomodoro.", bg=BG, fg="#aaa",
                 font=("Sans", 11)).pack(anchor="w", pady=(2, 12))
        for opt in options:
            tk.Button(self, text=opt, anchor="w", relief="flat", bg="#2a3040", fg="white", activebackground="#3a4256",
                      font=("Sans", 11), padx=10, pady=6, command=lambda o=opt: self.answer(o)).pack(fill="x", pady=2)
        row = tk.Frame(self, bg=BG)
        row.pack(fill="x", pady=(10, 0))
        self.entry = tk.Entry(row, width=36, font=("Sans", 11), bg="#111", fg="white", insertbackground="white",
                              relief="flat")
        self.entry.pack(side="left", ipady=5, fill="x", expand=True)
        self.entry.bind("<Return>", lambda e: self.answer(self.entry.get()))
        tk.Button(row, text="Cuenta", relief="flat", bg="#c0392b", fg="white", padx=10,
                  command=lambda: self.answer(self.entry.get())).pack(side="left", padx=(8, 0))
        self.msg = tk.Label(self, text="", bg=BG, fg="#e59866", font=("Sans", 11), wraplength=380, justify="left")
        self.msg.pack(anchor="w", pady=(10, 0))
        self.after(200, self.entry.focus_set)

    def answer(self, text):
        text = (text or "").strip()
        if len(text) < 4:
            self.msg.config(text="Escribe qué hiciste.")
            return
        self.msg.config(text="Pensándolo…")
        self.app.offpc_answer(text, source="desktop", done=lambda ok, m: self.show_result(m))

    def show_result(self, msg):
        try:
            self.msg.config(text=msg)
        except tk.TclError:   # ya se cerró
            pass

    def close(self):
        try:
            self.destroy()
        except tk.TclError:
            pass
