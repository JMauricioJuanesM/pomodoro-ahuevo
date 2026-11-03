"""Sensor de presencia, actividad e intención: el pomodoro solo cuenta el trabajo efectivo.

Al terminar un descanso (o al arrancar la jornada, o si lo pausas en horario de trabajo) el pomodoro no
corre de inmediato: queda «armado» y arranca solo en cuanto te ve trabajando (una app de trabajo al
frente y usando el teclado o el mouse unos segundos). Mientras corre, si te vas o te distraes, se pausa
y ese rato no cuenta. Si tardas en arrancar, la presión sube por escalones (`focus_nudge_min`):
  1. aviso en la compu y en el teléfono;
  2. teléfono estricto (te saca de todo lo que no sea esencial) y en la compu se minimiza lo que distrae;
  3. en la compu, pantalla completa que te pide arrancar (o decir en qué estás trabajando lejos de ella);
     el teléfono se bloquea en cuanto lo usas.
El nivel viaja al teléfono dentro del estado del temporizador (`arming`, `nudge`).
Sin datos del sensor (ni la extensión ni D-Bus) todo funciona como antes.
"""
import datetime as dt
import time

from ..config import save_config
from ..core.focus import cache_key, classify, is_browser, is_call, norm_title
from ..core.schedule import act_kind, act_label, activity, cell_activity, is_work, pc_enforced
from ..core.timefmt import fmt
from ..services.system import notify
from ..ui.overlays import NudgeScreen

PRESENT_SEC = 30    # tocaste el teclado o el mouse hace menos de esto: estás en la compu
DWELL_SEC = 10      # segundos viendo algo dudoso antes de preguntarle a la IA
STATS_DAYS = 60


class FocusMixin:
    def init_focus(self):
        self.arming_since = None     # desde cuándo deberías estar trabajando y el pomodoro aún no corre
        self.nudge = 0               # escalón de presión (0–3)
        self.streak = 0.0            # segundos seguidos trabajando mientras está armado
        self.distract_secs = 0.0
        self.distract_warned = False
        self.focus_state = ""        # "work" | "neutral" | "distract" | "away" | ""
        self.focus_what = ""         # lo que tienes al frente, para mostrarlo
        self.focus_key = None
        self.focus_pass_until = 0    # dijiste que trabajas lejos de la compu y se te creyó
        self.last_focus = time.time()
        self.last_minimize = 0
        self.last_stats_save = time.time()
        self.dwell = (None, 0)

    # ---------- consultas ----------
    def focus_on(self):
        return (self.cfg["focus_sensor"] and pc_enforced(self.cfg, dt.datetime.now())
                and self.presence.fresh() is not None)

    def on_duty(self):
        """¿Deberías estar trabajando? (el pomodoro corre o está armado esperando a que empieces)"""
        return self.phase == "work" and not self.shutdown_at and (self.running or self.arming_since is not None)

    def work_due(self):
        """¿Toca trabajar según el horario? (o aceptaste seguir en horas extra)"""
        aid = cell_activity(self.cfg, dt.datetime.now())
        return is_work(act_kind(self.cfg, aid)) or self.overtime_ok_date == dt.date.today()

    def act_desc(self, aid):
        a = activity(self.cfg, aid)
        if not a:
            return "trabajo"
        return f"{aid} ({a['label']})" + (f": {a['intent']}" if a.get("intent") else "")

    def focus_stats_today(self):
        return self.cfg.setdefault("focus_stats", {}).setdefault(dt.date.today().isoformat(), {})

    def _stat(self, key, v):
        st = self.focus_stats_today()
        st[key] = round(st.get(key, 0) + v, 1)

    # ---------- armar / arrancar / pausar ----------
    def arm(self, level=0):
        """El pomodoro queda esperando a que empieces. `level` adelanta la presión (si te distrajiste)."""
        mins = self.cfg["focus_nudge_min"]
        self.arming_since = time.time() - (mins[level - 1] * 60 if level else 0)
        self.nudge = level
        self.streak = 0.0

    def disarm(self):
        self.arming_since = None
        self.nudge = 0
        self.streak = 0.0
        self.distract_secs = 0.0
        self.distract_warned = False
        if isinstance(self.overlay, NudgeScreen):
            self.close_overlay()

    def start_effective(self, auto=False):
        """Arranca el pomodoro (te vio trabajando, o pulsaste Iniciar / «Empezar ahora»)."""
        if self.phase != "work" or self.shutdown_at:
            return
        late = time.time() - self.arming_since if self.arming_since else 0
        streak = self.streak if auto else 0
        self.disarm()
        self.remaining = max(1.0, self.remaining - streak)   # lo que ya llevabas trabajando sí cuenta
        self.end_time = time.time() + self.remaining
        self.running = True
        self.auto_date = dt.date.today()
        self._stat("effective", streak)
        self._stat("starts", 1)
        if auto:
            notify("▶ Arrancó tu pomodoro", f"Te vi en {self.focus_what or 'lo tuyo'}."
                   + (f" Tardaste {fmt(late)} en arrancar." if late > 60 else ""))
        self.render()

    def auto_pause(self, reason, credit):
        """Te fuiste o te distrajiste: se pausa y ese rato se devuelve al reloj (no cuenta)."""
        now = time.time()
        credit = max(0.0, min(credit, self.phase_total - (self.end_time - now)))
        self.remaining = min(self.phase_total, self.end_time - now + credit)
        self.running = False
        aid = cell_activity(self.cfg, dt.datetime.now())
        what = act_label(self.cfg, aid)
        if reason == "away":
            self._stat("effective", -credit)
            self._stat("away", credit)
            self.arm()
            notify("⏸ Pausé el pomodoro", f"No te vi en la compu {fmt(credit)}; ese rato no cuenta. "
                                         f"Sigue solo en cuanto regreses a {what}.")
        else:
            self.arm(level=2)
            notify("⏸ Pausé el pomodoro", f"Llevabas {fmt(credit)} en {self.focus_what or 'otra cosa'}: no cuenta. "
                                         f"Sigue solo en cuanto vuelvas a {what}.", urgent=True)
        self.render()

    # ---------- cada segundo ----------
    def focus_tick(self):
        now = time.time()
        step = min(now - self.last_focus, 5)
        self.last_focus = now
        # Una actividad que solo vigila el teléfono (`lock`: "phone"/"none") no presiona en la compu.
        s = self.presence.fresh() if self.cfg["focus_sensor"] and pc_enforced(self.cfg, dt.datetime.now()) else None
        if (s is None or self.phase != "work" or self.shutdown_at
                or (self.overlay and not isinstance(self.overlay, NudgeScreen))):
            self.focus_state = self.focus_what = ""
            self.offpc_tick(None, None)
            return
        aid = cell_activity(self.cfg, dt.datetime.now())
        v = self.focus_verdict(s, aid)
        self.focus_state = v
        self.offpc_tick(s, v)
        if self.running:
            self._stat({"work": "effective", "neutral": "effective"}.get(v, v), step)
            self._focus_running(v, s, step, aid)
        elif self.arming_since is not None:
            self._stat("late", step)
            self._focus_arming(v, s, step, aid)
        if now - self.last_stats_save > 60:
            self.last_stats_save = now
            old = (dt.date.today() - dt.timedelta(days=STATS_DAYS)).isoformat()
            self.cfg["focus_stats"] = {d: x for d, x in self.cfg["focus_stats"].items() if d >= old}
            save_config(self.cfg)

    def focus_verdict(self, s, aid):
        """Qué estás haciendo: "work" | "neutral" | "distract" | "away"."""
        self.focus_key = None
        if s["locked"]:
            self.focus_what = "sesión bloqueada"
            # Con un pase (trabajo lejos de la compu) bloquear la sesión al irte es lo normal.
            return "away" if time.time() >= self.focus_pass_until else "neutral"
        if not s["window"]:   # sin la extensión solo se sabe si estás en la compu
            self.focus_what = ""
            v = "work"
        else:
            app, title = s["app"], s["title"]
            self.focus_what = norm_title(title)[:40] if is_browser(app) else app.split(".")[-1].capitalize()
            if is_call(app, title):
                return "work"
            key = self.focus_key = cache_key(aid, app, title)
            v = self.judge.learned(key) or classify(app, title) or self.judge.known(key)
            if v is None:
                if self.dwell[0] != key:
                    self.dwell = (key, time.time())
                elif self.cfg["focus_llm"] and self.on_duty() and time.time() - self.dwell[1] >= DWELL_SEC:
                    self.judge.ask(key, self.act_desc(aid), app, title)
                v = "neutral"
        limit = self.cfg["focus_idle_work_min" if v == "work" else "focus_idle_min"] * 60
        if s["idle"] >= limit and time.time() >= self.focus_pass_until:
            return "away"
        return v

    def _focus_running(self, v, s, step, aid):
        if v == "distract" and s["idle"] < PRESENT_SEC:
            self.distract_secs += step
            limit = self.cfg["focus_distract_sec"]
            if not self.distract_warned and self.distract_secs >= limit / 2:
                self.distract_warned = True
                notify(f"👀 {self.focus_what or 'Eso'} no es {act_label(self.cfg, aid)}",
                       f"En {fmt(limit - self.distract_secs)} lo minimizo y el pomodoro se pausa (ese rato no cuenta). "
                       "Si sí es trabajo, márcalo en la ventana del Pomodoro.")
            if self.distract_secs >= limit:
                self.presence.minimize_focused()
                self.auto_pause("distract", self.distract_secs)
            return
        if v == "work":
            self.distract_secs = max(0.0, self.distract_secs - step / 2)
            if not self.distract_secs:
                self.distract_warned = False
        if v == "away" and act_kind(self.cfg, aid) != "enfocado":   # en una reunión es normal estar quieto
            now = time.time()
            credit = s["idle"]
            if self.focus_pass_until and now - self.focus_pass_until < credit:
                credit = max(0.0, now - self.focus_pass_until)   # lo del pase ya contó
            self.auto_pause("away", credit)
            self.offpc_ask(now - credit)

    def _focus_arming(self, v, s, step, aid):
        if not self.work_due():
            self.disarm()
            return
        present = s["idle"] < PRESENT_SEC
        if v == "work" and present:
            self.streak += step
            if self.streak >= self.cfg["focus_start_sec"]:
                self.start_effective(auto=True)
            return
        if v != "neutral":
            self.streak = 0.0
        now = time.time()
        if v == "away":   # no llegaste (o te fuiste otra vez): quizá estás en algo de trabajo lejos de aquí
            self.offpc_ask(self.arming_since if s["locked"] else max(self.arming_since, now - s["idle"]))
        late = now - self.arming_since
        level = sum(late >= m * 60 for m in self.cfg["focus_nudge_min"])
        if level > self.nudge:
            self.nudge = level
            self.escalate(level, late, aid)
        if self.nudge >= 2 and v == "distract" and present and now - self.last_minimize > 30:
            self.last_minimize = now
            self.presence.minimize_focused()
            notify("🙅 Eso no es lo que toca", f"Ahora toca {act_label(self.cfg, aid)}. Lo minimicé.")
        if isinstance(self.overlay, NudgeScreen):
            self.overlay.update(late)
        elif self.nudge >= 3 and present:
            self.open_overlay(lambda: NudgeScreen(self, act_label(self.cfg, aid), late))

    def escalate(self, level, late, aid):
        what, mins = act_label(self.cfg, aid), int(late // 60)
        if level == 1:
            notify(f"⏰ Llevas {mins} min sin arrancar",
                   f"Toca {what}. El pomodoro empieza solo en cuanto te pongas a trabajar.")
            self.phone.notify("⏰ Ya toca trabajar", f"Llevas {mins} min de retraso: {what}.")
        elif level == 2:
            notify(f"📵 {mins} min sin arrancar: teléfono en modo estricto",
                   "Te saca de todo lo que no sea esencial, y aquí minimizo lo que distraiga.", urgent=True)
            self.phone.notify("📵 Modo estricto", f"Llevas {mins} min sin arrancar {what}. Suelta el teléfono.")
        else:
            notify(f"🛑 Ya van {mins} min", "Cuando vuelvas a la compu te voy a pedir que arranques.", urgent=True)
            self.phone.notify("🛑 Ya van " + str(mins) + " min", f"Ve a la compu: toca {what}.")

    # ---------- lo que dices tú ----------
    def mark_focus(self, verdict="work"):
        """«Esto sí es trabajo» (o «esto me distrae») para lo que tienes al frente ahora; se recuerda."""
        if not self.focus_key:
            return
        self.judge.learn(self.focus_key, verdict)
        self.distract_secs, self.distract_warned = 0.0, False
        notify("👍 Anotado", f"«{self.focus_what}» cuenta como {'trabajo' if verdict == 'work' else 'distracción'} "
                            f"en {act_label(self.cfg, cell_activity(self.cfg, dt.datetime.now()))}.")
        self.render()

    def declare_intent(self, text, done):
        """Desde la pantalla de «¿Arrancamos?»: dices en qué trabajas lejos de la compu y la IA juzga (ver
        `controller/offpc.py`). done(ok, mensaje) se llama si no se te creyó (si sí, arranca el pomodoro y la
        pantalla se cierra)."""
        self.offpc_answer(text, source="desktop", done=done)

    def focus_info(self):
        """Lo que se manda al servidor con la señal de vida (para el bot)."""
        return {"focus": {"state": self.focus_state, "what": self.focus_what, "nudge": self.nudge,
                          "arming_since": self.arming_since, "today": self.focus_stats_today(),
                          "llm_calls": self.judge.calls_today(),
                          "offpc": (self.offpc or {}).get("what") if (self.offpc or {}).get("state") == "active" else None}}
