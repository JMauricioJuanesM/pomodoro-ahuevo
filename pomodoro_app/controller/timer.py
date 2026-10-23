"""Temporizador: fases de trabajo y descanso, descansos flexibles y pantallas completas."""
import datetime as dt
import time

from ..config import save_config
from ..constants import COLORS, FLEX_PHASES, NAMES
from ..core.schedule import act_kind, act_label, activity, cell_activity, is_work, next_change
from ..core.timefmt import fmt
from ..core.tips import activity_tip, pick_tip
from ..services.shortcuts import disable_shortcuts, restore_shortcuts
from ..services.system import notify, play_sound, poweroff
from ..ui.overlays import BreakScreen


class TimerMixin:
    # ---------- pantallas completas ----------
    def open_overlay(self, factory):
        self.close_overlay()
        if self.cfg["block_shortcuts"]:
            disable_shortcuts()
        self.widget.hide()
        self.overlay = factory()

    def close_overlay(self):
        if self.overlay:
            self.overlay.close()
            self.overlay = None
        restore_shortcuts()
        if self.cfg["widget_mode"]:
            self.widget.show()

    # ---------- reglas ----------
    def duration(self, phase, overtime=None):
        ot = self.in_overtime() if overtime is None else overtime
        mins = {"work": self.cfg["work_min"], "short": self.cfg["short_min"], "long": self.cfg["long_min"]}[phase]
        if ot:
            mins = max(1, mins / 2) if phase == "work" else mins * 2
        return int(mins * 60)

    def flex_limits(self):
        """(bloqueo inicial, bloqueo final) en segundos del descanso flexible, o None."""
        if self.phase not in FLEX_PHASES or not self.cfg["long_flex"]:
            return None
        if self.cfg["long_flex_same_as_short"]:
            first = last = self.duration("short", self.overtime)
        else:
            first, last = self.cfg["long_lock_first_min"] * 60, self.cfg["long_lock_last_min"] * 60
        if first + last + 60 > self.phase_total:
            return None
        return first, last

    def focus_now(self):
        """¿La actividad actual es «enfocado» (descansos que se pueden saltar)?"""
        return (self.cfg["focus_allow_skip"]
                and act_kind(self.cfg, cell_activity(self.cfg, dt.datetime.now())) == "enfocado")

    def set_break_skip(self, focus=False):
        """Decide, al empezar un descanso, si su pantalla trae el botón de saltar."""
        self.break_focus = focus
        self.break_skippable = self.cfg["allow_skip"] or focus

    # ---------- controles ----------
    def toggle(self):
        if self.phase != "work" or self.shutdown_at or self.overlay:
            return
        if self.running:
            self.remaining = self.end_time - time.time()
            self.running = False
        else:
            self.end_time = time.time() + self.remaining
            self.running = True
            self.auto_date = dt.date.today()
        self.render()

    def reset(self):
        if self.phase != "work" or self.shutdown_at:
            return
        self.running = False
        self.phase_total = self.remaining = self.duration("work")
        self.warned = False
        self.render()

    def break_now(self):
        if self.phase == "work" and not self.shutdown_at and not self.overlay:
            self.start_break(count_pomodoro=False)

    # ---------- fases ----------
    def start_phase(self, phase, run_now=True):
        self.phase = phase
        self.phase_title, self.phase_color = NAMES[phase], COLORS[phase]
        self.overtime = self.in_overtime()
        self.phase_total = self.remaining = self.duration(phase, self.overtime)
        self.end_time = time.time() + self.remaining
        self.running = run_now
        self.warned = False
        self.break_free = False
        self.relock_warned = False
        self.render()

    def start_break(self, count_pomodoro=True):
        today = dt.date.today().isoformat()
        if count_pomodoro:
            self.done_in_cycle += 1
            self.cfg["stats"][today] = self.cfg["stats"].get(today, 0) + 1
        long_break = self.done_in_cycle >= self.cfg["cycles"]
        phase = "long" if long_break else "short"
        if long_break:
            self.done_in_cycle = 0
        self.set_break_skip(focus=self.focus_now())
        self.start_phase(phase)
        self.current_tip = pick_tip(self.cfg, phase)
        save_config(self.cfg)
        n = self.cfg["stats"].get(today, 0)
        self.break_context = (f"Llevas {n} pomodoro{'s' if n != 1 else ''} hoy."
                              + (" Ciclo completo: te ganaste este descanso largo." if long_break else ""))
        aid, start = next_change(self.cfg, dt.datetime.now())
        if aid and start and (start - dt.datetime.now()).total_seconds() < 3600:
            self.break_context += f"  ·  A las {start:%H:%M}: {act_label(self.cfg, aid)}"
        if self.cfg["sound"]:
            play_sound()
        self.show_break_screen()

    def start_sched_break(self, aid, bstart, bend, key):
        """Descanso del horario (p. ej. COMER): dura lo que dura el bloque."""
        self.sched_key = key
        a = activity(self.cfg, aid)
        self.resume_work = None
        self.done_in_cycle = 0
        self.phase, self.phase_title, self.phase_color = "sched", a["label"], a["color"]
        self.overtime = False
        self.phase_total = (bend - bstart).total_seconds()
        self.end_time = bend.timestamp()
        self.remaining = self.end_time - time.time()
        self.running = True
        self.warned = self.relock_warned = False
        self.set_break_skip()
        self.current_tip = activity_tip(self.cfg, a["label"])
        nxt, _ = next_change(self.cfg, bstart)
        self.break_context = (f"Es hora de {a['label']} hasta las {bend:%H:%M}."
                              + (f"  Después: {act_label(self.cfg, nxt)}." if nxt else ""))
        save_config(self.cfg)
        if self.cfg["sound"]:
            play_sound()
        self.phone.notify(f"🔒 {a['label']}", f"Es hora de {a['label']} hasta las {bend:%H:%M}. Deja la compu.")
        state = self.cfg.get("sched_state") or {}
        if state.get("block") == key and state.get("free") and self.flex_limits():
            self.break_free = True   # ya habías salido de este descanso antes de reiniciar la app
            self.close_overlay()
        else:
            self.break_free = False
            self.show_break_screen()

    def show_break_screen(self):
        self.open_overlay(lambda: BreakScreen(self, self.phase_title, self.phase_color, self.remaining,
                                              self.current_tip, self.break_context, self.overtime))

    def leave_break(self):
        """Descanso flexible: libera la pantalla hasta el bloqueo final."""
        lim = self.flex_limits()
        if not lim or self.remaining <= lim[1]:
            return
        self.break_free = True
        self.relock_warned = False
        if self.phase == "sched":
            self.cfg["sched_state"] = {"block": self.sched_key, "free": True}
            save_config(self.cfg)
        self.close_overlay()
        notify("Pantalla liberada", f"Se vuelve a bloquear en {fmt(self.remaining - lim[1])} "
                                    f"para tus últimos {fmt(lim[1])} de descanso.")
        self.show_main()

    def end_break(self):
        was = self.phase
        self.close_overlay()
        if self.cfg["sound"]:
            play_sound()
        self.cfg["sched_state"] = {}
        if was == "remind" and self.resume_work:
            self.phase, self.phase_title, self.phase_color = "work", NAMES["work"], COLORS["work"]
            self.remaining = self.phase_total = self.resume_work
            self.end_time = time.time() + self.remaining
            self.running = True
            self.resume_work = None
            self.render()
            msg = f"Retomas tu pomodoro: quedan {fmt(self.remaining)}."
        else:
            aid = cell_activity(self.cfg, dt.datetime.now())
            auto = self.cfg["auto_start_work"] and is_work(act_kind(self.cfg, aid))
            self.start_phase("work", run_now=auto)
            msg = (f"Ahora toca {act_label(self.cfg, aid)}: pomodoro de {fmt(self.phase_total)}." if auto
                   else "Pulsa Iniciar cuando quieras.")
        notify("Descanso terminado", msg)
        self.phone.break_over(msg)
        self.show_main()

    def skip_break(self):
        if self.phase != "work" and self.break_skippable:
            self.end_break()

    # ---------- reloj ----------
    def tick(self):
        try:
            self._tick()
        finally:
            self.after(250, self.tick)

    def _tick(self):
        now = time.time()
        if self.shutdown_at:
            left = self.shutdown_at - now
            if left <= 60 and not self.shutdown_warned:
                self.shutdown_warned = True
                notify("Se apaga en 1 minuto", "Guarda ya.", urgent=True)
            if left <= 0:
                self.shutdown_at = None
                poweroff()
            self.render()
            return

        if int(now) != self.last_second:  # lo que basta revisar una vez por segundo
            self.last_second = int(now)
            self.check_day()
            self.check_reminders()
            self.process_reminders()
            if self.active_reminder and now > self.active_reminder[1]:
                self.active_reminder = None
            if now - self.last_guard > 15:
                self.last_guard = now
                self.phone.tick(self.phase == "work" and self.running)
            self.phone_prompt()

        if self.running:
            self.remaining = self.end_time - now
            if self.phase == "work":
                if (not self.warned and self.cfg["warn_sec"] > 0
                        and self.remaining <= self.cfg["warn_sec"]):
                    self.warned = True
                    notify("El descanso empieza pronto",
                           f"En {fmt(self.remaining)} se bloquea la pantalla. Guarda lo que estés haciendo.")
                if self.remaining <= 0:
                    self.start_break()
            elif self.remaining <= 0:
                self.end_break()
            else:
                self.tick_break()
        self.render()

    def tick_break(self):
        lim = self.flex_limits()
        if self.break_free and lim:
            to_lock = self.remaining - lim[1]
            if to_lock <= 60 and not self.relock_warned:
                self.relock_warned = True
                notify("Regresa el descanso", f"En {fmt(to_lock)} se vuelve a bloquear la pantalla.")
                self.phone.notify("Regresa el descanso", f"En {fmt(to_lock)} se bloquea la compu otra vez.")
            if to_lock <= 0:
                self.break_free = False
                self.show_break_screen()
            return
        if not isinstance(self.overlay, BreakScreen):
            return
        self.overlay.update(self.remaining)
        if lim:
            elapsed = self.phase_total - self.remaining
            if elapsed < lim[0]:
                self.overlay.set_flex("wait", lim[0] - elapsed)
            elif self.remaining > lim[1] + 10:
                self.overlay.set_flex("open", lim[1])
            else:
                self.overlay.set_flex(None)
