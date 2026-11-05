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
    "focus_allow_skip": True,
    # saltarse el pomodoro en horario de trabajo (pausar, reiniciar, descanso ya): razón, frase y espera
    "skip_friction": True,
    "skips_per_day": 2,        # en la compu; en el teléfono es `phone_skips_per_day`
    "phone_skips_per_day": 2,  # en actividades "enfocado" se pueden saltar los descansos
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
    # en horario de trabajo, apps del teléfono que nunca se bloquean (además de llamadas, SMS e inicio)
    "phone_essential": ["com.google.android.apps.maps", "com.waze", "com.google.android.apps.authenticator2",
                        "com.azure.authenticator", "com.android.camera", "com.android.deskclock",
                        "com.google.android.deskclock", "com.google.android.calendar",
                        "com.ticktick.task"],
    # sensor de presencia: el pomodoro corre solo mientras trabajas de verdad
    "focus_sensor": True,
    "focus_start_sec": 15,     # segundos trabajando (en una app de trabajo) para que arranque el pomodoro
    "focus_idle_min": 2,       # sin tocar la compu (en otra cosa) → te fuiste: se pausa y no cuenta
    "focus_idle_work_min": 5,  # …con una app de trabajo al frente (quizá estás leyendo)
    "focus_distract_sec": 60,  # en algo que no toca durante el pomodoro: aviso a la mitad, luego la escalera
    # escalera de castigos (contenido de riesgo y distracciones): aviso → minimizar → cerrar pestaña →
    # cerrar la app y bloquear la sesión → apagar. Ver controller/enforce.py.
    "ladder_content_gap_sec": [25, 30, 40, 60],    # espera mínima (s) para subir cada escalón si sigue
    "ladder_distract_gap_sec": [30, 90, 120, 180],  # el aviso llega a la mitad de focus_distract_sec
    "ladder_decay_min": 10,       # sin reincidir este rato, se baja un escalón
    "ladder_distract_max": 4,     # escalón máximo por distraerte (4 = apagar; 3 = solo cerrar y bloquear)
    "ladder_shutdown_sec": 60,    # cuenta regresiva antes de apagar
    "focus_nudge_min": [2, 5, 8],  # minutos de retraso para subir la presión (aviso, teléfono estricto, pantalla)
    "focus_llm": True,         # preguntarle a la IA lo que las reglas no saben decidir
    "focus_llm_model": "claude-haiku-5-5",
    "focus_llm_per_hour": 20,
    "focus_llm_per_day": 120,
    "offpc": True,             # si te vas en horario de trabajo, preguntar qué haces (trabajo lejos de la compu)
    "offpc_max_min": 90,       # tope diario de trabajo fuera de la compu que cuenta
    "offpc_learned": {},       # actividad → lo que has hecho lejos de la compu (para los botones rápidos)
    "focus_stats": {},         # por día: segundos efectivos, de retraso, distraído y fuera
    # Faro: lo comparten la compu y el teléfono (ver services/sync.py)
    "mind_on_unlock": True,      # frase en pantalla completa al desbloquear el teléfono
    "mind_unlock_every_min": 20,
    "mind_show_sec": 7,          # sin mind_until_touch, se quita sola a los tantos segundos
    "mind_until_touch": True,    # la frase se queda hasta que la toques (no antes de mind_min_sec)
    "mind_min_sec": 3,
    "mind_on_break": True,       # frase en la pantalla del descanso (compu)
    "phone_break_lock": True,    # el descanso tapa también el teléfono
    "phone_long_flex": True,     # el descanso largo tapa el teléfono por tramos (tapado, libre, tapado…)
    "phone_long_block_min": 0,   # minutos de cada tramo; 0 = un tercio del descanso (entre 5 y 10)
    "break_allow": ["messaging"],  # tipos de app que sí se pueden usar con el teléfono tapado
    "wa_guard": True,            # WhatsApp en el trabajo: chats con tope, estados y grupos no
    "wa_budget_min": 5,
    "wa_groups_ok": False,
    "wa_groups_allowed": [],
    "tasks_firm": True,          # pantalla completa cuando vence una tarea de TickTick
    "tasks_allday_hour": 11,
    "auto_update": True,         # el teléfono instala solo la versión que publica el VPS
    "phone_app_categories": {},  # paquete → categoría, si quieres corregir a la IA
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
