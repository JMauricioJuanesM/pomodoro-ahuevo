"""Rutas de archivos. `POMODORO_CONFIG_DIR` permite usar otra carpeta (p. ej. para pruebas)."""
import os

PROJECT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENTRY_SCRIPT = os.path.join(PROJECT_DIR, "pomodoro.py")
TRAY_HELPER = os.path.join(PROJECT_DIR, "pomodoro_app", "services", "tray_helper.py")
EXAMPLE_DEFAULTS = os.path.join(PROJECT_DIR, "defaults.example.json")

CONFIG_DIR = os.environ.get("POMODORO_CONFIG_DIR") or os.path.expanduser("~/.config/pomodoro-ahuevo")
CONFIG_FILE = os.path.join(CONFIG_DIR, "config.json")
# Tu horario "original" (el que restaura el botón del editor). Vive fuera del repositorio.
PERSONAL_DEFAULTS = os.path.join(CONFIG_DIR, "defaults.json")
# Servidor de sincronización con el teléfono: {"url": …, "token": …}. Sin este archivo no se sincroniza.
SYNC_FILE = os.path.join(CONFIG_DIR, "sync.json")
KEYS_BACKUP = os.path.join(CONFIG_DIR, "atajos_respaldo.json")

PANEL_CONFIG = os.path.expanduser("~/.config/adb-panel/config.json")
PANEL_META = os.path.expanduser("~/.cache/adb-panel/meta.json")

AUTOSTART_FILE = os.path.expanduser("~/.config/autostart/pomodoro-ahuevo.desktop")
SYSTEMD_DIR = os.path.expanduser("~/.config/systemd/user")
TIMER_NAME = "pomodoro-ahuevo"
SOCKET_NAME = "\0pomodoro-ahuevo"
SOUND = "/usr/share/sounds/freedesktop/stereo/complete.oga"
