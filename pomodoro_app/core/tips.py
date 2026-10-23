"""Consejos para los descansos, elegidos según el tipo de descanso, la hora y lo ya dado hoy."""
import datetime as dt
import random
import time

from .timefmt import day_period

# (texto, tipo de descanso: short/long/any, momento del día: manana/mediodia/tarde/noche/any, categoría)
TIPS = [
    # ojos
    ("Regla 20-20-20: mira algo a más de 6 metros durante 20 segundos.", "short", "any", "ojos"),
    ("Cierra los ojos y cúbrelos con las palmas un minuto. Que descansen en la oscuridad.", "short", "any", "ojos"),
    ("Parpadea lento 10 veces: frente a la pantalla parpadeas la mitad de lo normal.", "short", "any", "ojos"),
    ("Asómate a la ventana y enfoca lo más lejano que veas.", "any", "any", "ojos"),
    # agua y comida
    ("Toma un vaso de agua completo.", "short", "any", "agua"),
    ("Rellena tu botella de agua y déjala junto al teclado.", "any", "any", "agua"),
    ("¿Desayunaste? Si no, come algo con proteína: huevo, yogur, nueces.", "long", "manana", "comida"),
    ("Un snack ligero ayuda al bajón de la tarde: fruta, cacahuates, un poco de queso.", "any", "tarde", "comida"),
    ("Evita el café después de las 3 pm; mejor agua o té sin cafeína para dormir bien.", "short", "tarde", "agua"),
    ("Cena ligero y temprano. Tu sueño te lo agradece.", "long", "noche", "comida"),
    ("Toma un té caliente sin cafeína y date un respiro tranquilo.", "any", "noche", "agua"),
    # cuerpo
    ("Ponte de pie y estira los brazos hacia el techo 15 segundos.", "short", "any", "movimiento"),
    ("Gira los hombros hacia atrás 10 veces y luego hacia adelante.", "short", "any", "movimiento"),
    ("Inclina la cabeza hacia cada hombro, 15 segundos por lado.", "short", "any", "movimiento"),
    ("Estira muñecas y dedos: abre y cierra las manos 10 veces.", "short", "any", "movimiento"),
    ("Haz 10 sentadillas. Sube el pulso y despierta el cerebro.", "short", "any", "movimiento"),
    ("Camina por la casa: ve a la cocina, al patio, a donde sea, pero muévete.", "short", "any", "movimiento"),
    ("Rutina completa de estiramientos: cuello, hombros, espalda, cadera y piernas, 30 segundos cada uno.",
     "long", "any", "movimiento"),
    ("Sal a caminar 10 minutos, aunque sea a la tienda.", "long", "manana", "movimiento"),
    ("Acuéstate en el piso con las piernas sobre una silla 5 minutos. Descansa la espalda.", "long", "tarde",
     "movimiento"),
    ("Ordena algo pequeño: lava los trastes, tiende la cama. Mover el cuerpo despeja la mente.", "long", "any",
     "movimiento"),
    ("Toma el sol unos minutos: la luz de la mañana ajusta tu reloj interno.", "any", "manana", "movimiento"),
    # respiración y mente
    ("Respira 4-7-8: inhala 4 segundos, aguanta 7, exhala 8. Repite 4 veces.", "short", "any", "mente"),
    ("Respiración de caja: 4 segundos inhalar, 4 aguantar, 4 exhalar, 4 aguantar.", "short", "any", "mente"),
    ("Relaja la mandíbula, baja los hombros y suelta la frente. Revisa dónde guardas tensión.", "short", "any",
     "mente"),
    ("Escribe en papel las 3 cosas más importantes de hoy. Lo demás puede esperar.", "any", "manana", "mente"),
    ("Medita 5 minutos: siéntate, cierra los ojos y solo cuenta respiraciones.", "long", "any", "mente"),
    ("Anota en papel lo que quedó pendiente para mañana y suéltalo por hoy.", "any", "noche", "mente"),
    ("Baja el brillo de las pantallas y usa luz cálida: tu cerebro se prepara para dormir.", "any", "noche",
     "mente"),
    ("Siesta corta: 15-20 minutos máximo, con alarma. Más de eso te deja aturdido.", "long", "mediodia", "mente"),
    ("Pon una canción que te guste y no hagas nada más mientras suena.", "short", "any", "mente"),
    # social
    ("Mándale un mensaje a alguien que quieres. Sin trabajo de por medio.", "long", "any", "social"),
    ("Llama a un amigo o a tu familia 5 minutos.", "long", "tarde", "social"),
    ("Platica con quien esté en casa. Algo que no sea trabajo.", "any", "any", "social"),
    ("Lávate la cara con agua fría. Reinicio instantáneo.", "short", "any", "agua"),
    ("No agarres el celular: este descanso es para tus ojos y tu cabeza, no para otra pantalla.", "any", "any",
     "mente"),
]

# Consejos según la actividad del horario (se buscan las palabras en el nombre de la actividad).
ACTIVITY_TIPS = [
    (("comer", "comida", "almuerzo", "lunch", "🍽"), [
        "Come lejos de la pantalla y sin el celular. Saborea.",
        "Mastica despacio: tu cerebro tarda 20 minutos en notar que ya comiste.",
        "Que tu plato tenga verduras y proteína: evitas el bajón de la tarde.",
        "Cuando termines, camina 10 minutos: ayuda a la digestión y despeja.",
        "Sirve agua con la comida y no regreses a la compu hasta que se acabe el bloque.",
    ]),
    (("cena",), [
        "Cena ligero: tu cuerpo duerme mejor sin digestiones pesadas.",
        "Deja el celular en otro cuarto mientras cenas.",
    ]),
    (("run", "correr", "walk", "caminar", "gym", "ejercicio", "🏃"), [
        "Ponte los tenis ya: lo difícil es empezar.",
        "Calienta 5 minutos antes y estira al terminar.",
        "Sal sin audífonos un rato: mira alrededor.",
    ]),
    (("leer", "📖", "lectura"), [
        "Libro de papel, luz cálida y el celular lejos.",
        "Lee algo que no tenga que ver con trabajo.",
    ]),
    (("pareja", "familia", "amigos", "❤"), [
        "Tiempo de calidad: celular boca abajo y en silencio.",
        "Pregunta cómo le fue en su día y escucha de verdad.",
    ]),
    (("baño", "🚿", "ducha"), [
        "Agua tibia y sin prisa: es tu reinicio del día.",
    ]),
    (("play", "jugar", "🎮"), [
        "Juega sin culpa: también es descanso. Ponle hora de fin.",
    ]),
    (("libre",), [
        "Tiempo libre de verdad: lejos de la compu.",
        "Haz algo que disfrutes y que no tenga pantalla.",
    ]),
]

CATEGORY_DUE = {"agua": 75 * 60, "ojos": 50 * 60}  # categorías que conviene repetir cada tanto


def _tips_log(cfg):
    today = dt.date.today().isoformat()
    log = cfg.get("tips_log") or {}
    if log.get("date") != today:
        log = {"date": today, "used": [], "last_cat": {}}
    cfg["tips_log"] = log
    return log


def pick_tip(cfg, kind):
    """Consejo según tipo de descanso y hora, sin repetir los ya dados hoy."""
    log = _tips_log(cfg)
    period, now = day_period(), time.time()

    def fits(t):
        return t[1] in (kind, "any") and t[2] in (period, "any")

    pool = [t for t in TIPS if fits(t) and t[0] not in log["used"]]
    if not pool:  # ya se dieron todos: vuelve a empezar
        log["used"] = []
        pool = [t for t in TIPS if fits(t)]
    # Si hace rato que no tomas agua o descansas la vista, eso va primero.
    due = [t for t in pool if t[3] in CATEGORY_DUE
           and now - log["last_cat"].get(t[3], 0) > CATEGORY_DUE[t[3]]]
    last = log.get("last_category")
    candidates = due or [t for t in pool if t[3] != last] or pool
    # Los consejos específicos de la hora pesan más que los genéricos.
    weights = [3 if t[2] == period else 1 for t in candidates]
    tip = random.choices(candidates, weights)[0]
    log["used"].append(tip[0])
    log["last_cat"][tip[3]] = now
    log["last_category"] = tip[3]
    return tip[0]


def activity_tip(cfg, label):
    """Consejo que va con la actividad del horario; si no hay, uno general de descanso largo."""
    log = _tips_log(cfg)
    low = label.lower()
    for words, tips in ACTIVITY_TIPS:
        if any(w in low for w in words):
            fresh = [t for t in tips if t not in log["used"]] or tips
            tip = random.choice(fresh)
            log["used"].append(tip)
            return tip
    return pick_tip(cfg, "long")
