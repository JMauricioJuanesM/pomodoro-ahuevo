"""Desactiva y restaura los atajos de GNOME que permiten escapar de la pantalla del descanso."""
import json
import os
import shutil

from ..paths import CONFIG_DIR, KEYS_BACKUP
from .system import run

SHORTCUTS = [
    ("org.gnome.mutter", "overlay-key", "''"),
    ("org.gnome.desktop.wm.keybindings", "switch-applications", "@as []"),
    ("org.gnome.desktop.wm.keybindings", "switch-applications-backward", "@as []"),
    ("org.gnome.desktop.wm.keybindings", "switch-windows", "@as []"),
    ("org.gnome.desktop.wm.keybindings", "switch-windows-backward", "@as []"),
    ("org.gnome.desktop.wm.keybindings", "switch-group", "@as []"),
    ("org.gnome.desktop.wm.keybindings", "switch-to-workspace-left", "@as []"),
    ("org.gnome.desktop.wm.keybindings", "switch-to-workspace-right", "@as []"),
    ("org.gnome.desktop.wm.keybindings", "minimize", "@as []"),
    ("org.gnome.desktop.wm.keybindings", "close", "@as []"),
    ("org.gnome.desktop.wm.keybindings", "toggle-fullscreen", "@as []"),
    ("org.gnome.shell.keybindings", "toggle-overview", "@as []"),
    ("org.gnome.shell.keybindings", "toggle-application-view", "@as []"),
    ("org.gnome.shell.keybindings", "toggle-message-tray", "@as []"),
]


def disable_shortcuts():
    if not shutil.which("gsettings") or os.path.exists(KEYS_BACKUP):
        return
    backup = []
    for schema, key, _ in SHORTCUTS:
        val = run("gsettings", "get", schema, key)
        if val:
            backup.append([schema, key, val])
    os.makedirs(CONFIG_DIR, exist_ok=True)
    # El respaldo se escribe ANTES de tocar nada, para poder restaurar tras un crash.
    with open(KEYS_BACKUP, "w") as f:
        json.dump(backup, f)
    off = {(s, k): o for s, k, o in SHORTCUTS}
    for schema, key, _ in backup:
        run("gsettings", "set", schema, key, off[(schema, key)])


def restore_shortcuts():
    try:
        with open(KEYS_BACKUP) as f:
            backup = json.load(f)
    except (OSError, ValueError):
        return
    for schema, key, val in backup:
        run("gsettings", "set", schema, key, val)
    os.remove(KEYS_BACKUP)
