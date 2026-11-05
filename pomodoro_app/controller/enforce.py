"""Escalera de castigos: lo mismo para contenido de riesgo (vigía + NudeNet) y para distraerte en el trabajo.

Cada vez que algo se detecta (y sigue al frente) se sube un escalón, pero solo si ya pasó la espera del
escalón actual, para que alcances a leer el aviso y hacer caso:
  0. aviso (diciendo qué sigue y en cuánto);
  1. minimizar la ventana;
  2. cerrar la pestaña (en un navegador; en otra app, la ventana);
  3. cerrar la app entera y bloquear la sesión;
  4. apagar la compu, con cuenta regresiva a pantalla completa.
Si no reincides en `ladder_decay_min`, baja un escalón (y otro, y otro…): volver a lo mismo al rato sigue
donde te quedaste. El contenido explícito entra directo al escalón 2. Las distracciones esperan más entre
escalones y no pasan de `ladder_distract_max`.
"""
import time

from ..core.timefmt import fmt
from ..services.system import lock_session, notify, poweroff
from ..ui.overlays import LadderShutdownScreen

STEP_NAMES = ["un aviso", "minimizarlo", "cerrar la pestaña", "cerrar la app y bloquear la sesión", "apagar la compu"]
TOP = len(STEP_NAMES) - 1


class Ladder:
    """Estado de una escalera (pura lógica; sin efectos)."""

    def __init__(self, gaps, decay, top=TOP):
        self.gaps, self.decay, self.top = gaps, decay, top
        self.step = -1          # -1 = nada pendiente
        self.step_ts = 0.0      # cuándo se subió al escalón actual
        self.seen_ts = 0.0      # última vez que se detectó

    def cool(self, now):
        if self.step >= 0 and self.seen_ts and self.decay > 0:
            drops = int((now - self.seen_ts) // self.decay)
            if drops:
                self.step = max(-1, self.step - drops)
                self.seen_ts += drops * self.decay   # el siguiente escalón baja otro rato después

    def hit(self, now, start=0):
        """Se detectó otra vez. Regresa el escalón a ejecutar ahora, o None si toca esperar."""
        self.cool(now)
        self.seen_ts = now
        start = min(start, self.top)
        if self.step < start:
            self.step, self.step_ts = start, now
            return self.step
        if self.step < self.top and now - self.step_ts >= self.wait():
            self.step += 1
            self.step_ts = now
            return self.step
        return None

    def wait(self):
        """Segundos que hay que seguir para subir del escalón actual."""
        return self.gaps[min(max(self.step, 0), len(self.gaps) - 1)]

    def next_in(self, now):
        return max(0, self.step_ts + self.wait() - now)

    def reset(self):
        self.step, self.step_ts, self.seen_ts = -1, 0.0, 0.0


class EnforceMixin:
    def init_enforce(self):
        c = self.cfg
        self.ladders = {
            "content": Ladder(c["ladder_content_gap_sec"], c["ladder_decay_min"] * 60),
            "distract": Ladder(c["ladder_distract_gap_sec"], c["ladder_decay_min"] * 60,
                               min(TOP, c["ladder_distract_max"])),
        }

    def ladder_hit(self, kind, what, start=0, severity=""):
        """Algo de `kind` ("content" | "distract") sigue al frente. Ejecuta el escalón si toca; regresa cuál."""
        lad = self.ladders[kind]
        now = time.time()
        step = lad.hit(now, start)
        if step is None:
            return None
        nxt = (f" Si sigues, en {fmt(lad.wait())} voy a {STEP_NAMES[step + 1]}." if step < lad.top else "")
        if kind == "content":
            head = f"{'🚫 Contenido explícito' if severity == 'explicit' else '👀 Contenido sugerente'} en {what}"
            body = ["Sal de ahí ahora. Respira y levántate.", "Lo minimicé.", "Cerré la pestaña.",
                    "Cerré la app y bloqueé la sesión. Sal del cuarto un rato.", ""][step]
        else:
            head = f"🙅 {what} no es lo que toca"
            body = ["Regresa a lo tuyo. Si sí es trabajo, márcalo en la ventana del Pomodoro.",
                    "Lo minimicé y pausé el pomodoro (ese rato no cuenta).", "Cerré la pestaña.",
                    "Cerré la app y bloqueé la sesión.", ""][step]
        if step == 1:
            self.presence.act("minimize")
        elif step == 2:
            self.presence.act("close-tab")
        elif step == 3:
            self.presence.act("close-app")
            self.after(2500, lock_session)   # primero se cierra (la extensión no actúa con la sesión bloqueada)
        if step == TOP:
            self.ladder_shutdown(head)
        else:
            notify(head, body + nxt, urgent=step > 0 or kind == "content")
        return step

    def ladder_shutdown(self, why):
        secs = self.cfg["ladder_shutdown_sec"]
        notify("⏻ La compu se apaga en " + fmt(secs), why + ". Ya fueron demasiados avisos.", urgent=True)
        for lad in self.ladders.values():
            lad.reset()
        self.open_overlay(lambda: LadderShutdownScreen(self, why, secs, self.ladder_poweroff))

    def ladder_poweroff(self):
        self.close_overlay()
        poweroff()

    def ladder_info(self):
        """Para la ventana principal / el bot: escalón actual de cada escalera."""
        now = time.time()
        for lad in self.ladders.values():
            lad.cool(now)
        return {k: lad.step for k, lad in self.ladders.items()}
