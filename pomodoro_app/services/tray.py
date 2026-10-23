"""Cliente del icono de la barra superior: lanza `tray_helper.py` y habla con él por stdin/stdout."""
import json
import subprocess
import tkinter as tk

from ..paths import TRAY_HELPER


class TrayClient:
    def __init__(self, root, on_command):
        self.root = root
        self.on_command = on_command   # recibe "show", "toggle", "break", … (ver tray_helper.py)
        self.ok = False
        self.last = None
        self.proc = None
        try:
            self.proc = subprocess.Popen(["/usr/bin/python3", TRAY_HELPER], stdin=subprocess.PIPE,
                                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
        except OSError:
            return
        self.ok = True
        root.tk.createfilehandler(self.proc.stdout, tk.READABLE, self._on_readable)

    def _on_readable(self, *_):
        line = self.proc.stdout.readline().strip()
        if not line:  # el icono murió: sin barra, cerrar vuelve a cerrar de verdad
            self.root.tk.deletefilehandler(self.proc.stdout)
            self.ok = False
            return
        self.on_command(line)

    def update(self, msg):
        if not self.ok or msg == self.last:
            return
        self.last = msg
        try:
            self.proc.stdin.write(json.dumps(msg, ensure_ascii=False) + "\n")
            self.proc.stdin.flush()
        except (OSError, ValueError):
            self.ok = False

    def close(self):
        if self.proc:
            self.proc.terminate()
