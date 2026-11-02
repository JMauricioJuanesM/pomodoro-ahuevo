"""Frases para reforzar creencias (las mismas reglas que `Mind.kt` en el teléfono).

El mazo lo reescribe cada noche la IA (`server/nightly/`); mientras no haya, se usan las de `SEED`.
Cada frase: {"id", "text", "sub", "when": [...], "weight", "score"}.
"""
import datetime as dt
import random

SEED = [
    ("Lo que haces hoy decide quién eres mañana.", "Un pomodoro a la vez.", "unlock work"),
    ("Descansar también es parte del trabajo.", "Levántate, estírate, toma agua. Lejos de la pantalla.", "break"),
    ("Mira lejos. Respira hondo. Mueve el cuerpo.", "", "break"),
    ("El scroll no descansa la mente: la cansa.", "", "break unlock"),
    ("No necesitas ganas. Necesitas empezar.", "Las ganas llegan a los 5 minutos.", "work morning"),
    ("Hoy no tienes que ser perfecto, solo constante.", "", "morning break"),
]


def deck(mind):
    msgs = (mind or {}).get("messages") or []
    if msgs:
        return msgs
    return [{"id": f"seed{i}", "text": t, "sub": s, "when": w.split()} for i, (t, s, w) in enumerate(SEED)]


def pick(mind, moment, recent=()):
    """Una frase para `moment` ("break", "work", "morning", "night", "urge"…) al azar según peso y reacciones."""
    all_ = deck(mind)
    pool = [m for m in all_ if moment in (m.get("when") or [moment])] or all_
    fresh = [m for m in pool if m.get("id") not in recent] or pool
    if not fresh:
        return None
    weights = [max(0.2, m.get("weight", 1) + 0.3 * m.get("score", 0)) for m in fresh]
    return random.choices(fresh, weights)[0]


def moment_now(phase):
    h = dt.datetime.now().hour
    if phase in ("short", "long", "sched"):
        return "break"
    return "morning" if h < 10 else "night" if h >= 22 else "work"
