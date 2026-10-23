"""Comandos del sistema: notificaciones, sonido, bloqueo de sesión, apagado y monitores."""
import os
import re
import shutil
import subprocess

from ..paths import SOUND


def run(*cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=10).stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        return ""


def notify(title, body="", urgent=False):
    if shutil.which("notify-send"):
        subprocess.Popen(["notify-send", "-a", "Pomodoro", *(["-u", "critical"] if urgent else []), title, body])


def play_sound():
    if shutil.which("canberra-gtk-play") and os.path.exists(SOUND):
        subprocess.Popen(["canberra-gtk-play", "-f", SOUND],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def lock_session():
    run("loginctl", "lock-session")


def poweroff():
    from .shortcuts import restore_shortcuts
    restore_shortcuts()
    if subprocess.run(["systemctl", "poweroff"]).returncode != 0:
        subprocess.run(["loginctl", "poweroff"])


def monitors():
    """Lista de (x, y, w, h) de cada monitor; vacía si no se puede saber."""
    out = run("xrandr", "--listmonitors")
    mons = []
    for m in re.finditer(r"(\d+)/\d+x(\d+)/\d+\+(-?\d+)\+(-?\d+)", out):
        w, h, x, y = map(int, m.groups())
        mons.append((x, y, w, h))
    return mons
