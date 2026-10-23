"""Recordatorios: detectar cuándo toca cada uno y mostrarlo (aviso o descanso con bloqueo)."""
import datetime as dt
import time

from ..config import save_config
from ..constants import COLORS
from ..core.schedule import act_label, next_change
from ..core.timefmt import parse_hhmm
from ..services.system import notify
from ..ui.overlays import MessageScreen


class RemindersMixin:
    def check_reminders(self):
        now = dt.datetime.now()
        today = now.date().isoformat()
        fired = [k for k in self.cfg["reminders_fired"] if today in k]
        changed = False
        for r in self.cfg["reminders"]:
            if not r.get("enabled", True):
                continue
            if r["type"] == "once" and r.get("date") != today:
                continue
            if r["type"] != "once" and now.weekday() not in r.get("days", []):
                continue
            t = parse_hhmm(r["time"])
            if not t:
                continue
            delta = (now - dt.datetime.combine(now.date(), t)).total_seconds()
            key = f"{r['id']}|{today} {r['time']}"
            if 0 <= delta < 120 and key not in fired:
                fired.append(key)
                if r["type"] == "once":
                    r["enabled"] = False
                self.pending_reminders.append(dict(r))
                notify("⏰ " + r["text"], f"Recordatorio de las {r['time']}")
                self.phone.notify("⏰ " + r["text"], f"Recordatorio de las {r['time']}")
                changed = True
        if changed or len(fired) != len(self.cfg["reminders_fired"]):
            self.cfg["reminders_fired"] = fired
            save_config(self.cfg)

    def process_reminders(self):
        if not self.pending_reminders or self.overlay or self.shutdown_at:
            return
        r = self.pending_reminders.pop(0)
        aid, start = next_change(self.cfg, dt.datetime.now())
        nxt = f"Luego en tu horario: {act_label(self.cfg, aid)} a las {start:%H:%M}" if aid else ""
        if r["lock"] and r["duration"] and self.phase == "work":
            self.start_remind_break(r, nxt)
            return
        if r["duration"] and not r["lock"]:
            self.active_reminder = (r["text"], time.time() + r["duration"] * 60)
        sub = f"Durante {r['duration']} min. {nxt}" if r["duration"] else nxt
        self.open_overlay(lambda: MessageScreen(self, r["text"], sub, self.cfg["reminder_msg_sec"],
                                                closable=not r["lock"]))

    def start_remind_break(self, r, context):
        if self.running:
            self.resume_work = self.end_time - time.time()
        self.phase, self.phase_title, self.phase_color = "remind", r["text"], COLORS["remind"]
        self.overtime = False
        self.phase_total = self.remaining = r["duration"] * 60
        self.end_time = time.time() + self.remaining
        self.running = True
        self.break_free = self.relock_warned = False
        self.set_break_skip()
        self.current_tip = "Recordatorio: " + r["text"]
        self.break_context = context
        self.show_break_screen()
