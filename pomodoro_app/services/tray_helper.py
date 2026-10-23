#!/usr/bin/env python3
"""Icono del Pomodoro en la barra superior de GNOME (proceso auxiliar).

Lo lanza services/tray.py con /usr/bin/python3 (necesita PyGObject del sistema).
Usa libayatana-appindicator3 directamente con ctypes (no requiere instalar el paquete
gir1.2-ayatanaappindicator3). Se comunica con la app por stdin/stdout:
  entrada: una línea JSON por actualización {"label", "status", "phone", "running", "work"}
  salida:  un comando por línea: show, toggle, break, widget, schedule, reminders, hide, quit
"""
import ctypes
import json
import sys

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import GLib, Gtk  # noqa: E402

lib = ctypes.CDLL("libayatana-appindicator3.so.1")
lib.app_indicator_new.restype = ctypes.c_void_p
lib.app_indicator_new.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int]
lib.app_indicator_set_status.argtypes = [ctypes.c_void_p, ctypes.c_int]
lib.app_indicator_set_menu.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
lib.app_indicator_set_label.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p]
lib.app_indicator_set_icon_full.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p]
lib.app_indicator_set_title.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
ctypes.pythonapi.PyCapsule_GetPointer.restype = ctypes.c_void_p
ctypes.pythonapi.PyCapsule_GetPointer.argtypes = [ctypes.py_object, ctypes.c_char_p]

CATEGORY_APPLICATION_STATUS, STATUS_ACTIVE = 0, 1


def send(cmd):
    sys.stdout.write(cmd + "\n")
    sys.stdout.flush()


class Tray:
    def __init__(self):
        self.ind = lib.app_indicator_new(b"pomodoro-ahuevo", b"alarm-symbolic", CATEGORY_APPLICATION_STATUS)
        lib.app_indicator_set_title(self.ind, b"Pomodoro")
        self.menu = Gtk.Menu()
        self.status = self.item("Pomodoro", None)
        self.status.set_sensitive(False)
        self.phone = self.item("📱 …", None)
        self.phone.set_sensitive(False)
        self.menu.append(Gtk.SeparatorMenuItem())
        self.item("Mostrar ventana", "show")
        self.toggle = self.item("Iniciar", "toggle")
        self.brk = self.item("Descanso ya", "break")
        self.item("◉ Widget", "widget")
        self.item("📅 Horario", "schedule")
        self.item("⏰ Recordatorios", "reminders")
        self.item("Ocultar todo (queda aquí)", "hide")
        self.menu.append(Gtk.SeparatorMenuItem())
        self.item("Salir del Pomodoro", "quit")
        self.menu.show_all()
        lib.app_indicator_set_menu(self.ind, ctypes.pythonapi.PyCapsule_GetPointer(self.menu.__gpointer__, None))
        lib.app_indicator_set_status(self.ind, STATUS_ACTIVE)
        GLib.io_add_watch(sys.stdin, GLib.IO_IN | GLib.IO_HUP, self.on_input)

    def item(self, label, cmd):
        it = Gtk.MenuItem(label=label)
        if cmd:
            it.connect("activate", lambda *_: send(cmd))
        self.menu.append(it)
        return it

    def on_input(self, src, cond):
        line = sys.stdin.readline()
        if not line:          # la app principal se cerró
            Gtk.main_quit()
            return False
        try:
            st = json.loads(line)
        except ValueError:
            return True
        lib.app_indicator_set_label(self.ind, st.get("label", "").encode(), b"00:00")
        self.status.set_label(st.get("status", ""))
        self.phone.set_label(st.get("phone", ""))
        self.toggle.set_label("Pausar" if st.get("running") else "Iniciar")
        self.toggle.set_sensitive(st.get("work", True))
        self.brk.set_sensitive(st.get("work", True))
        icon = b"dialog-warning-symbolic" if st.get("alert") else b"alarm-symbolic"
        lib.app_indicator_set_icon_full(self.ind, icon, b"Pomodoro")
        return True


if __name__ == "__main__":
    Tray()
    Gtk.main()
