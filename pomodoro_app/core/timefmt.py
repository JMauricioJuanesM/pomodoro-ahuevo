"""Formatos de tiempo."""
import datetime as dt
import math
import re


def fmt(secs):
    secs = max(0, int(math.ceil(secs)))
    if secs >= 3600:
        return f"{secs // 3600}:{secs % 3600 // 60:02d}:{secs % 60:02d}"
    return f"{secs // 60:02d}:{secs % 60:02d}"


def parse_hhmm(s):
    m = re.fullmatch(r"\s*(\d{1,2}):(\d{2})\s*", s or "")
    if not m or int(m.group(1)) > 23 or int(m.group(2)) > 59:
        return None
    return dt.time(int(m.group(1)), int(m.group(2)))


def hour_label(h):
    return f"{h % 12 or 12} {'AM' if h < 12 else 'PM'}"


def day_period(now=None):
    h = (now or dt.datetime.now()).hour
    if 5 <= h < 12:
        return "manana"
    if 12 <= h < 15:
        return "mediodia"
    if 15 <= h < 18:
        return "tarde"
    return "noche"
