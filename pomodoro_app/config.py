"""Configuración: valores por defecto, carga y guardado.

El horario y las actividades por defecto NO están en el código: se leen de
`~/.config/pomodoro-ahuevo/defaults.json` (tu horario personal, fuera del repositorio) o, si no
existe, de `defaults.example.json` (un horario de ejemplo que sí va en el repositorio).
"""
import copy
import json
import os

from .paths import CONFIG_DIR, CONFIG_FILE, EXAMPLE_DEFAULTS, PERSONAL_DEFAULTS

DEFAULT_CONFIG = {
    "work_min": 25,
    "short_min": 5,
    "long_min": 15,
    "cycles": 4,               # pomodoros antes del descanso largo
    "warn_sec": 60,            # aviso antes de que empiece el descanso
    "auto_start_work": True,   # al terminar el descanso arranca el siguiente pomodoro
    "block_shortcuts": True,   # desactiva Super, Alt+Tab, etc. durante el descanso
    "lock_if_escape": True,    # bloquea la sesión si la ventana pierde el foco
    "allow_skip": False,       # botón para saltar cualquier descanso
    "focus_allow_skip": True,  # en actividades "enfocado" se pueden saltar los descansos
    "sound": True,
    # descanso largo / programado flexible
    "long_flex": True,
    "long_flex_same_as_short": True,   # bloqueo inicial y final = duración del descanso corto
    "long_lock_first_min": 5,
    "long_lock_last_min": 5,
    # horario y jornada (activities y schedule salen de defaults.json)
    "grid_hours": [6, 23],
    "autostart": True,         # abrir la app al iniciar sesión y arrancar al empezar el trabajo
    "overtime_mode": True,     # al terminar el trabajo del día: trabajo ÷2, descansos ×2, ofrecer apagar
    "shutdown_grace_min": 5,
    # recordatorios
    "reminders": [],
    "reminders_fired": [],
    "reminder_msg_sec": 15,
    # teléfono
    "phone_enabled": True,
    "phone_break_end": "lock",  # "notify" | "lock"
    "phone_lock_delay_sec": 45,
    "phone_read_sec": 15,      # segundos para leer el aviso antes de bloquear el teléfono
    "phone_work_guard": True,
    "phone_work_warn_min": 3,
    "phone_work_lock_min": 5,
    "phone_dnd": False,        # No molestar en el teléfono mientras trabajas
    "phone_distract": True,    # sacarte de las apps que distraen durante el trabajo
    "phone_distracting": ["com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
                          "com.google.android.youtube", "com.facebook.katana", "com.twitter.android",
                          "com.reddit.frontpage", "com.snapchat.android", "com.netflix.mediaclient",
                          "com.amazon.avod.thirdpartyclient", "tv.twitch.android.app", "com.pinterest"],
    "phone_serial": None,
    "phone_ip": None,
    "phone_stats": {},         # segundos de teléfono en horario de trabajo, por día
    # ventana
    "always_on_top": False,
    "widget_mode": False,
    "widget_size": 120,
    "widget_pos": None,
    "stats": {},
    "tips_log": {},
    "sched_state": {},
}


def _read_json(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def default_schedule():
    """(actividades, horario) originales: el personal si existe, si no el de ejemplo."""
    data = _read_json(PERSONAL_DEFAULTS) or _read_json(EXAMPLE_DEFAULTS) or {}
    return copy.deepcopy(data.get("activities", {})), copy.deepcopy(data.get("schedule", {}))


def load_config():
    cfg = copy.deepcopy(DEFAULT_CONFIG)
    cfg["activities"], cfg["schedule"] = default_schedule()
    cfg.update(_read_json(CONFIG_FILE) or {})
    return cfg


def save_config(cfg):
    os.makedirs(CONFIG_DIR, exist_ok=True)
    with open(CONFIG_FILE + ".tmp", "w") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)
    os.replace(CONFIG_FILE + ".tmp", CONFIG_FILE)
