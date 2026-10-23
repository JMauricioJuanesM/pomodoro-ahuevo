"""Consultas sobre el horario semanal (cfg["schedule"]: día → hora → id de actividad)."""
import datetime as dt

from ..constants import WORK_KINDS


def cell_activity(cfg, when):
    """Id de la actividad del horario en ese momento (o None)."""
    return cfg["schedule"].get(str(when.weekday()), {}).get(str(when.hour))


def activity(cfg, aid):
    return cfg["activities"].get(aid) if aid else None


def act_kind(cfg, aid):
    a = activity(cfg, aid)
    return a["kind"] if a else "libre"


def act_label(cfg, aid):
    a = activity(cfg, aid)
    return a["label"] if a else "—"


def is_work(kind):
    """¿En este tipo de actividad corre el pomodoro?"""
    return kind in WORK_KINDS


def work_bounds(cfg, date):
    """(inicio, fin) del trabajo de ese día según el horario, o None si no hay trabajo."""
    day = cfg["schedule"].get(str(date.weekday()), {})
    hours = [int(h) for h, a in day.items() if is_work(act_kind(cfg, a))]
    if not hours:
        return None
    base = dt.datetime.combine(date, dt.time())
    return base + dt.timedelta(hours=min(hours)), base + dt.timedelta(hours=max(hours) + 1)


def block_at(cfg, when):
    """(actividad, inicio, fin) del bloque continuo de la misma actividad que contiene `when`."""
    aid = cell_activity(cfg, when)
    hour0 = when.replace(minute=0, second=0, microsecond=0)
    start, end = hour0, hour0 + dt.timedelta(hours=1)
    while start.hour > 0 and cell_activity(cfg, start - dt.timedelta(hours=1)) == aid:
        start -= dt.timedelta(hours=1)
    while end.date() == hour0.date() and cell_activity(cfg, end) == aid and end.hour != 0:
        end += dt.timedelta(hours=1)
    return aid, start, end


def next_change(cfg, when):
    """(actividad, inicio) de lo siguiente que toca en el horario."""
    aid, _, end = block_at(cfg, when)
    for _ in range(24 * 7):
        nxt = cell_activity(cfg, end)
        if nxt != aid and nxt is not None:
            return nxt, end
        end += dt.timedelta(hours=1)
    return None, None
