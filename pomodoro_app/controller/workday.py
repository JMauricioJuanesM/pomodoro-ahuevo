"""Jornada: arranque automático, descansos programados del horario, fin del día y apagado."""
import datetime as dt
import time

from ..core.schedule import act_kind, act_label, block_at, is_work, work_bounds
from ..core.timefmt import fmt
from ..services.system import notify, play_sound, poweroff
from ..ui.overlays import EndDayScreen


class WorkdayMixin:
    def bounds(self):
        return work_bounds(self.cfg, dt.date.today())

    def in_overtime(self):
        if not self.cfg["overtime_mode"]:
            return False
        now = dt.datetime.now()
        b = self.bounds()
        return now.time() < dt.time(5, 0) or b is None or now >= b[1]

    def before_start(self):
        b, now = self.bounds(), dt.datetime.now()
        return bool(b) and now.time() >= dt.time(5, 0) and now < b[0]

    def check_day(self):
        today, now = dt.date.today(), dt.datetime.now()
        idle = self.phase == "work" and not self.running and not self.overlay
        b = self.bounds()
        # Descanso programado del horario (p. ej. la comida).
        aid, bstart, bend = block_at(self.cfg, now)
        if aid and act_kind(self.cfg, aid) == "descanso":
            key = f"{bstart:%Y-%m-%d %H}"
            if self.sched_key != key:
                self.start_sched_break(aid, bstart, bend, key)
                return
        # Arranque automático de la jornada (aunque prendas la compu tarde).
        if (self.cfg["autostart"] and self.auto_date != today and idle and b and b[0] <= now < b[1]
                and is_work(act_kind(self.cfg, aid)) and self.sync.ready()):
            self.auto_date = today
            self.start_phase("work")
            notify("Arrancó tu jornada", f"Ahora toca {act_label(self.cfg, aid)}. Primer pomodoro de "
                                         f"{fmt(self.phase_total)}. ¡A darle!")
            self.show_main()
        # Fin de la jornada.
        if self.in_overtime() and self.overtime_ok_date != today and self.phase == "work" and not self.overlay:
            self.auto_date = today
            self.show_endday()
        # Recordatorios pesados en horas extra.
        if self.in_overtime() and self.running and time.time() - self.last_nag > 20 * 60:
            self.last_nag = time.time()
            mins = int((now - b[1]).total_seconds() // 60) if b and now >= b[1] else 0
            extra = f"Llevas {mins} min de horas extra." if mins > 0 else "Estás trabajando fuera de horario."
            notify("Tu jornada ya terminó", f"{extra} Guarda tu trabajo y apaga la compu.", urgent=True)
            self.phone.notify("Tu jornada ya terminó", extra)

    def show_endday(self):
        self.running = False
        if self.cfg["sound"]:
            play_sound()
        b = self.bounds()
        self.open_overlay(lambda: EndDayScreen(self, b[1] if b else None))

    def continue_overtime(self):
        self.overtime_ok_date = dt.date.today()
        self.close_overlay()
        self.last_nag = time.time()
        self.start_phase("work")
        notify("Horas extra", f"Pomodoro de {fmt(self.phase_total)}; el siguiente descanso dura el doble.")

    def shutdown_now(self):
        self.close_overlay()
        poweroff()

    def shutdown_soon(self):
        self.close_overlay()
        self.running = False
        self.shutdown_total = self.cfg["shutdown_grace_min"] * 60
        self.shutdown_at = time.time() + self.shutdown_total
        self.shutdown_warned = False
        notify("La compu se apaga en " + fmt(self.shutdown_total), "Guarda tu trabajo ahora.", urgent=True)
        self.show_main()
