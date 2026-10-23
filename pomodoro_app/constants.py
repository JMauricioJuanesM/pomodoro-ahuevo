"""Constantes de dominio y de presentación compartidas por todas las capas."""

DAYS = ["Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo"]
DAY_LETTERS = ["L", "M", "X", "J", "V", "S", "D"]

# Tipos de actividad del horario.
KINDS = {
    "trabajo": "Trabajo: corre el pomodoro",
    "enfocado": "Enfocado: corre el pomodoro, pero puedo saltar los descansos (reuniones)",
    "descanso": "Descanso programado: bloquea la pantalla",
    "libre": "Fuera de trabajo",
}
WORK_KINDS = ("trabajo", "enfocado")   # tipos en los que corre el pomodoro y cuentan como jornada
KIND_ICONS = {"trabajo": "💻", "enfocado": "🎯", "descanso": "🔒", "libre": "🌿"}

COLORS = {
    "work": "#c0392b",
    "short": "#1e8449",
    "long": "#1f618d",
    "overtime": "#6c3483",
    "shutdown": "#922b21",
    "waiting": "#566573",
    "remind": "#b9770e",
}
DARK_BG = "#15171c"
CARD_BG = "#22252d"
MUTED_FG = "#8b919c"

NAMES = {"work": "Trabajo", "short": "Descanso corto", "long": "Descanso largo"}
FLEX_PHASES = ("long", "sched", "remind")

# Vibración: pares (duración ms, intensidad 0-255) → tres pulsos fuertes.
VIBRATION = ["0", "0", "600", "255", "200", "0", "600", "255", "200", "0", "1000", "255"]

PRESET_COLORS = ["#00ffff", "#46bdc6", "#4a86e8", "#0000ff", "#9900ff", "#ff00ff", "#990000", "#e06666",
                 "#ff9900", "#ffff00", "#00ff00", "#3c9e5c", "#b7b7b7", "#ffffff"]
EMOJIS = "💻 📚 🎓 ⚙️ 👤 💬 🍽 🍳 ☕ 🏃 🚶 🧘 🎮 📖 🚿 🛏 ❤️ 🎵 🛒 🧹 🚗 📞"
