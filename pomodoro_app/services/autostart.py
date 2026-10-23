"""Arranque automático: al iniciar sesión (autostart de GNOME) y al empezar el trabajo (timer de systemd)."""
import datetime as dt
import os
import shutil

from ..core.schedule import work_bounds
from ..paths import AUTOSTART_FILE, ENTRY_SCRIPT, SYSTEMD_DIR, TIMER_NAME
from .system import run


def sync_autostart(cfg):
    """Abre la app al iniciar sesión y, con un timer de systemd, al empezar el trabajo de cada día."""
    cal_days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    today = dt.date.today()
    calendars = []
    for d in range(7):
        date = today + dt.timedelta(days=(d - today.weekday()) % 7)
        b = work_bounds(cfg, date)
        if b:
            calendars.append(f"OnCalendar={cal_days[d]} *-*-* {b[0]:%H:%M}:00")
    files = {
        AUTOSTART_FILE: f"""[Desktop Entry]
Type=Application
Name=Pomodoro
Exec=python3 {ENTRY_SCRIPT} --auto
Icon=alarm-symbolic
X-GNOME-Autostart-enabled=true
""",
        os.path.join(SYSTEMD_DIR, TIMER_NAME + ".service"): f"""[Unit]
Description=Abre el Pomodoro al inicio de la jornada

[Service]
Type=exec
ExecStart=/usr/bin/python3 {ENTRY_SCRIPT} --auto
""",
        os.path.join(SYSTEMD_DIR, TIMER_NAME + ".timer"): """[Unit]
Description=Inicio de la jornada (Pomodoro)

[Timer]
""" + "\n".join(calendars) + """

[Install]
WantedBy=timers.target
""",
    }
    enabled = cfg["autostart"] and calendars
    changed = False
    for path, content in files.items():
        if enabled:
            try:
                with open(path) as f:
                    if f.read() == content:
                        continue
            except OSError:
                pass
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w") as f:
                f.write(content)
            changed = True
        elif os.path.exists(path):
            os.remove(path)
            changed = True
    if changed and shutil.which("systemctl"):
        run("systemctl", "--user", "daemon-reload")
        run("systemctl", "--user", "enable" if enabled else "disable", "--now", TIMER_NAME + ".timer")
        if enabled:
            run("systemctl", "--user", "restart", TIMER_NAME + ".timer")
