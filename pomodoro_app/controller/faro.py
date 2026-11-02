"""Faro en la compu: frases en el descanso, preguntas del sistema y recordatorios tajantes de tareas.

Las tareas vienen de TickTick a través del hub (`sync.tasks`); cuando vence una con hora (o a las
`tasks_allday_hour` las de todo el día) sale una pantalla completa que pide decidir: hecha, hacerla ahora o
posponer (a la tercera, con razón). Lo que decidas aquí vale también en el teléfono, y al revés.
Las preguntas (p. ej. «¿qué mejorarías?») salen en la pantalla del descanso o, fuera del trabajo, solas.
"""
import datetime as dt
import time

from ..core import mind
from ..core.schedule import pc_enforced
from ..ui.overlays import AskScreen, NudgeScreen, TaskScreen

TT_FORMATS = ("%Y-%m-%dT%H:%M:%S.%f%z", "%Y-%m-%dT%H:%M:%S%z")


def parse_due(s):
    for f in TT_FORMATS:
        try:
            return dt.datetime.strptime(s, f).astimezone()
        except (TypeError, ValueError):
            continue
    return None


class FaroMixin:
    def init_faro(self):
        self.mind_recent = []
        self.task_snooze_local = {}   # id → hasta (epoch), para no esperar el eco del hub
        self.ask_later = {}           # id → hasta
        self.last_faro = 0

    def mind_pick(self, moment):
        m = mind.pick(self.sync.mind, moment, self.mind_recent)
        if m:
            self.mind_recent = (self.mind_recent + [m.get("id")])[-6:]
        return m

    def mind_react(self, mid, value):
        self.sync.send("POST", "/api/mind/react", {"id": mid, "value": value, "source": "desktop"})

    # ---------- tareas ----------
    def tasks(self):
        return (self.sync.tasks or {}).get("items") or []

    def task_due_now(self):
        if not self.cfg.get("tasks_firm", True):
            return None
        now = time.time()
        snoozes = (self.sync.tasks or {}).get("snoozes") or {}
        today = dt.date.today()
        allday_at = dt.time(int(self.cfg.get("tasks_allday_hour", 11)))
        due = []
        for t in self.tasks():
            d = parse_due(t.get("due"))
            if not d:
                continue
            until = max((snoozes.get(t["id"]) or {}).get("until", 0), self.task_snooze_local.get(t["id"], 0))
            if until > now:
                continue
            if t.get("all_day"):
                if d.date() == today and dt.datetime.now().time() >= allday_at:
                    due.append((d, t))
            elif now - 3 * 86400 <= d.timestamp() <= now:
                due.append((d, t))
        return min(due, key=lambda x: x[0])[1] if due else None

    def task_done(self, t):
        self.task_snooze_local[t["id"]] = time.time() + 10 * 86400
        self.sync.send("POST", f"/api/tasks/{t['id']}/complete", {"project_id": t.get("project_id"), "source": "desktop"})
        self.close_faro_overlay()

    def task_snooze(self, t, minutes, reason="", counts=True):
        self.task_snooze_local[t["id"]] = time.time() + minutes * 60
        self.sync.send("POST", f"/api/tasks/{t['id']}/snooze",
                       {"minutes": minutes, "reason": reason, "count": counts, "source": "desktop"})
        self.close_faro_overlay()

    def task_focus(self, t):
        self.sync.send("PUT", "/api/tasks/current", {"task": {"id": t["id"], "title": t["title"]}})
        if self.sync.remote is not None:
            self.sync.remote["current_task"] = {"id": t["id"], "title": t["title"]}
        self.task_snooze(t, 60, counts=False)

    def task_snoozes(self, t):
        return ((self.sync.tasks or {}).get("snoozes") or {}).get(t["id"], {}).get("count", 0)

    # ---------- preguntas ----------
    def answer_ask(self, ask, text):
        self.ask_later[ask["id"]] = time.time() + 10 * 86400
        self.sync.send("POST", f"/api/asks/{ask['id']}/answer", {"text": text, "source": "desktop"})
        for a in (self.sync.remote or {}).get("asks") or []:
            if a["id"] == ask["id"]:
                a["answer"] = {"text": text}

    def ask_postpone(self, ask):
        self.ask_later[ask["id"]] = time.time() + 90 * 60
        self.close_faro_overlay()

    def pending_ask(self):
        now = time.time()
        return next((a for a in self.sync.asks() if self.ask_later.get(a["id"], 0) < now), None)

    def close_faro_overlay(self):
        if isinstance(self.overlay, (AskScreen, TaskScreen)):
            self.close_overlay()

    # ---------- cada segundo ----------
    def faro_tick(self):
        now = time.time()
        if now - self.last_faro < 5 or self.shutdown_at:
            return
        self.last_faro = now
        busy = self.overlay is not None and not isinstance(self.overlay, NudgeScreen)
        if busy or not pc_enforced(self.cfg, dt.datetime.now()):
            return
        t = self.task_due_now()
        if t:
            self.open_overlay(lambda: TaskScreen(self, t, self.task_snoozes(t)))
            return
        # Las preguntas no interrumpen un pomodoro: salen en el descanso o fuera del trabajo.
        if self.phase == "work" and not self.on_duty() and not self.running:
            ask = self.pending_ask()
            if ask:
                self.open_overlay(lambda: AskScreen(self, ask))
