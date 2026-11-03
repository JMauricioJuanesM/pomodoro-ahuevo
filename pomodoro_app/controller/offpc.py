"""Fuera de la compu: trabajo que se hace lejos de la pantalla también cuenta, si de verdad es trabajo.

Cuando el sensor de presencia ve que te fuiste en horario de trabajo, en vez de solo pausar y presionar,
te pregunta «¿qué estás haciendo?» en el teléfono (va en el temporizador: `timer.offpc`). Contestas con
un toque (lo que ya hiciste antes lejos de la compu en esta actividad) o escribiendo, desde el teléfono,
la web o el bot (`POST /api/offpc` en el hub), o en la compu al volver.

La IA (`Judge.ask_offpc`, una llamada barata y recordada por frase) decide si cuenta, dónde se hace y
cuánto debería tardar:
  - `pc`: eso se hace en la compu → no hay pase;
  - `phone`: necesita el teléfono (una llamada, autenticar…) → solo se valen esas apps;
  - `none`: lejos de las pantallas (imprimir, firmar, una oficina, leer en papel) → en el teléfono solo lo
    esencial; si lo usas para otra cosa, se acaba el pase (`phone_used`) y desde ahí no cuenta.
Si cuenta, el pomodoro corre (y se abona lo que ya llevabas fuera); termina solo cuando vuelves a trabajar
en la compu o al vencer el tiempo (entonces vuelve a preguntar «¿sigues?»). Si se te olvidó contestar, al
volver te pregunta qué hiciste y, si cuenta, te devuelve ese rato.

Para que no se vuelva trampa: tope diario de minutos fuera (`offpc_max_min`) y todo queda en el diario,
que lee la IA nocturna.
"""
import datetime as dt
import time
import uuid

from ..config import save_config
from ..core.schedule import act_label, cell_activity
from ..core.timefmt import fmt
from ..services.system import notify

RETRO_MIN_SEC = 5 * 60   # al volver, solo se pregunta qué hiciste si te fuiste al menos esto
ASK_TTL = 45 * 60        # una pregunta sin contestar se olvida tras esto
RETRO_TTL = 10 * 60      # …o tras esto desde que volviste
PRESENT_SEC = 30
AWAY = "🚶 Me fui / descanso"
DONE = "✅ Ya acabé"
DEFAULT_OPTIONS = ("📞 Llamada de trabajo", "🖨️ Imprimir o firmar documentos", "📖 Leer o anotar en papel")


def _norm(text):
    return " ".join((text or "").lower().split())[:120]


class OffpcMixin:
    def init_offpc(self):
        # {"id", "state": "asking" | "active", "since", "back", "question", "options", "what", "where",
        #  "allow", "until", "started", "pending"}
        self.offpc = None
        self.offpc_last = None        # último veredicto {"id", "ok", "msg"}: el teléfono lo avisa
        self.offpc_done_msgs = None   # ids de mensajes del hub ya procesados (None: aún no llega el primero)
        self.offpc_dialog = None
        self.offpc_streak = 0.0

    # ---------- consultas ----------
    def offpc_left(self):
        """Segundos que te quedan hoy de trabajo fuera de la compu."""
        used = self.focus_stats_today().get("offpc", 0)
        return max(0.0, self.cfg.get("offpc_max_min", 90) * 60 - used)

    def offpc_options(self, aid):
        """Lo que más has hecho lejos de la compu en esta actividad (y si no, ejemplos)."""
        learned = (self.cfg.get("offpc_learned") or {}).get(aid or "-") or {}
        top = [t for t, _ in sorted(learned.items(), key=lambda x: -x[1]["n"])][:3]
        top = [learned[t]["text"] for t in top]
        return top + [o for o in DEFAULT_OPTIONS if o not in top][:max(0, 3 - len(top))]

    def offpc_public(self):
        """Lo que viaja al teléfono dentro del temporizador."""
        o = self.offpc
        pub = {k: o.get(k) for k in ("id", "state", "question", "options", "what", "where", "allow", "until",
                                    "since", "pending")} if o else None
        if pub:
            pub["back"] = bool(o.get("back"))
            pub["until"] = round(pub["until"]) if pub["until"] else None
            pub["since"] = round(pub["since"]) if pub["since"] else None
        return {"offpc": pub, "offpc_last": self.offpc_last}

    # ---------- preguntar ----------
    def offpc_ask(self, since, question=None, options=None):
        """Te fuiste en horario de trabajo: el teléfono pregunta qué estás haciendo."""
        if (self.offpc or not self.cfg.get("offpc", True) or self.phase != "work" or self.shutdown_at
                or not self.work_due()):
            return
        aid = cell_activity(self.cfg, dt.datetime.now())
        self.offpc = {"id": uuid.uuid4().hex[:8], "state": "asking", "since": since, "back": None,
                      "question": question or "¿Qué estás haciendo lejos de la compu?",
                      "options": list(options or self.offpc_options(aid)), "what": "", "where": None,
                      "allow": [], "until": None, "started": None, "pending": False}
        self.render()

    def offpc_tick(self, s, v):
        """Cada segundo, desde el sensor (`s`: presencia, `v`: veredicto de lo que tienes al frente)."""
        o = self.offpc
        if not o:
            return
        now = time.time()
        if self.phase != "work" or self.shutdown_at:
            self.offpc_end("phase") if o["state"] == "active" else self._offpc_drop()
            return
        present = s is not None and not s["locked"] and s["idle"] < PRESENT_SEC
        if o["state"] == "asking":
            if o["pending"]:
                return
            if present and not o["back"]:
                o["back"] = now
                if now - o["since"] < RETRO_MIN_SEC or self.offpc_left() <= 0:
                    self._offpc_drop()   # fue un ratito (o ya no hay minutos): no vale la pena preguntar
                else:
                    self._offpc_open_dialog()
            elif (o["back"] and now - o["back"] > RETRO_TTL) or now - o["since"] > ASK_TTL:
                self._offpc_drop()
            return
        # Activo: termina cuando vuelves a trabajar en la compu, si te distraes en ella o al vencer.
        if present and v == "work":
            self.offpc_streak += 1
            if self.offpc_streak >= self.cfg["focus_start_sec"]:
                self.offpc_end("back")
        elif present and v == "distract":
            self.offpc_end("distract")
        else:
            self.offpc_streak = 0.0
        if self.offpc and now >= o["until"]:
            what = o["what"]
            self.offpc_end("timeout")
            self.offpc_ask(now, question=f"Se acabó el tiempo de «{what}». ¿Sigues?", options=[what, DONE])

    # ---------- contestar ----------
    def offpc_messages(self, msgs):
        """Mensajes del hub (teléfono, web, bot). Los que ya estaban al arrancar no se reprocesan."""
        msgs = msgs or []
        if self.offpc_done_msgs is None:
            self.offpc_done_msgs = {m["id"] for m in msgs}
            return
        for m in msgs:
            if m["id"] in self.offpc_done_msgs:
                continue
            self.offpc_done_msgs.add(m["id"])
            if m["kind"] == "answer":
                self.offpc_answer(m.get("text") or "", steps=m.get("steps"), source=m.get("source") or "?")
            elif m["kind"] == "phone_used":
                self.offpc_end("phone_used", detail=m.get("text") or "")
            elif m["kind"] == "end":
                self.offpc_end("done")

    def offpc_answer(self, text, steps=None, source="desktop", done=None):
        """Lo que dices que estás haciendo (o hiciste) lejos de la compu. `done(ok, msg)` si no se te creyó."""
        text = (text or "").strip()
        o = self.offpc
        if text == AWAY:
            self._offpc_drop()
            self._offpc_verdict(False, "Va. Ese rato es descanso: no cuenta.")
            return
        if text == DONE:
            self.offpc_end("done") if o and o["state"] == "active" else self._offpc_drop()
            return
        if len(text) < 4:
            done and done(False, "Escribe qué estás haciendo.")
            return
        if self.phase != "work" or self.shutdown_at or not self.work_due():
            done and done(False, "Ahora no toca trabajar.")
            return
        if self.offpc_left() <= 0:
            self._offpc_drop()
            msg = f"Ya usaste tus {self.cfg.get('offpc_max_min', 90)} min de hoy fuera de la compu."
            self._offpc_verdict(False, msg)
            done and done(False, msg)
            return
        if not o or o["state"] == "active":
            if o:
                self.offpc_end("replaced")
            self.offpc_ask(time.time())
            o = self.offpc
            if not o:
                return
        aid = cell_activity(self.cfg, dt.datetime.now())
        o["pending"], o["what"] = True, text
        away_min = int((time.time() - o["since"]) // 60)
        oid = o["id"]
        cb = lambda r: self._offpc_judged(oid, text, r, source, done)  # noqa: E731
        if self.cfg["focus_llm"]:
            self.judge.ask_offpc(f"{aid or '-'}|{_norm(text)}", self.act_desc(aid), text, away_min, steps, cb)
        else:
            cb(None)
        self.render()

    def _offpc_judged(self, oid, text, r, source, done):
        o = self.offpc
        if not o or o["id"] != oid:
            return
        o["pending"] = False
        aid = cell_activity(self.cfg, dt.datetime.now())
        what = act_label(self.cfg, aid)
        if r is None:   # sin IA disponible: te creo, pero poco rato
            r = {"ok": True, "where": "none", "minutes": 10, "allow": []}
        if not r["ok"] or r["where"] == "pc":
            msg = (f"Eso se hace en la compu: regresa y el pomodoro sigue solo." if r["ok"]
                   else f"«{text}» no suena a {what}. Ese rato no cuenta.")
            self._offpc_journal(f"Fuera de la compu, no contó ({source}): «{text}» en {what}")
            self._offpc_drop()
            self._offpc_verdict(False, msg)
            done and done(False, msg)
            return
        self._offpc_learn(aid, text)
        now, left = time.time(), self.offpc_left()
        if o["back"]:   # ya volviste: se te devuelve lo que estuviste fuera
            credit = min(o["back"] - o["since"], (r["minutes"] + 10) * 60, left)
            self._offpc_drop()
            self.credit_work(credit)
            from ..ui.overlays import NudgeScreen
            if isinstance(self.overlay, NudgeScreen):
                self.start_effective()
            msg = f"Cuenta: te devolví {fmt(credit)} de «{text}»."
            self._offpc_journal(f"Fuera de la compu, contó {fmt(credit)} al volver ({source}): «{text}» en {what}")
            self._offpc_verdict(True, msg)
            return
        credit = min(now - o["since"], left)
        dur = max(60.0, min(r["minutes"] * 60, left - credit))
        o.update(state="active", where=r["where"], allow=r["allow"], until=now + dur, started=now)
        self.offpc_streak = 0.0
        self.focus_pass_until = o["until"]
        if not self.running:
            self.start_effective()
        self.credit_work(credit)
        rule = {"phone": "En el teléfono solo lo que necesitas para eso.",
                "none": "Suelta el teléfono: si lo usas para otra cosa, se acaba."}.get(r["where"], "")
        msg = f"Cuenta como trabajo por {fmt(dur)}: «{text}». {rule}".strip()
        self._offpc_journal(f"Fuera de la compu ({source}, {r['where']}, {fmt(dur)}): «{text}» en {what}")
        self._offpc_verdict(True, msg)

    # ---------- terminar ----------
    def offpc_end(self, reason, detail=""):
        o = self.offpc
        if not o:
            return
        if o["state"] != "active":
            self._offpc_drop()
            return
        now = time.time()
        used = max(0.0, min(now, o["until"]) - o["started"])
        self._stat("offpc", used)
        self.offpc = None
        self.offpc_streak = 0.0
        # Desde aquí el sensor vuelve a contar normal; lo de antes (ya abonado) no se pierde.
        self.focus_pass_until = min(self.focus_pass_until, now)
        if reason == "phone_used":
            msg = f"Usaste el teléfono{' en ' + detail if detail else ''}: se acabó «{o['what']}». Desde ahora no cuenta."
            self._offpc_journal(f"Se acabó el pase fuera de la compu por usar el teléfono: «{o['what']}»")
            self._offpc_verdict(False, msg)
        elif reason == "done":
            self._offpc_verdict(True, f"Listo «{o['what']}» ({fmt(used)}). Regresa a la compu.")
        self.render()

    def _offpc_drop(self):
        self.offpc = None
        self._offpc_close_dialog()
        self.render()

    # ---------- piezas ----------
    def credit_work(self, secs):
        """Abona trabajo hecho lejos de la compu: el pomodoro avanza y cuenta como efectivo."""
        if secs <= 0 or self.phase != "work":
            return
        if self.running:
            self.end_time = max(time.time() + 1, self.end_time - secs)
            self.remaining = self.end_time - time.time()
        else:
            self.remaining = max(1.0, self.remaining - secs)
        st = self.focus_stats_today()
        self._stat("away", -min(secs, st.get("away", 0)))
        self._stat("effective", secs)
        self._stat("offpc", secs)
        self.render()

    def _offpc_verdict(self, ok, msg):
        self.offpc_last = {"id": uuid.uuid4().hex[:8], "ok": ok, "msg": msg}
        notify("🚶 " + ("Cuenta como trabajo" if ok else "Fuera de la compu"), msg, urgent=not ok)
        if self.offpc_dialog:
            self.offpc_dialog.show_result(msg)

    def _offpc_learn(self, aid, text):
        learned = self.cfg.setdefault("offpc_learned", {}).setdefault(aid or "-", {})
        k = _norm(text)
        learned[k] = {"text": text[:60], "n": learned.get(k, {}).get("n", 0) + 1}
        if len(learned) > 15:   # que no crezca sin fin: se queda lo más usado
            for k in sorted(learned, key=lambda x: learned[x]["n"])[:len(learned) - 15]:
                del learned[k]
        save_config(self.cfg)

    def _offpc_journal(self, text):
        self.sync.send("POST", "/api/journal", {"type": "nota", "text": text[:500], "source": "desktop", "auto": True,
                                                "trigger": "offpc"})

    def _offpc_open_dialog(self):
        if self.offpc_dialog or self.overlay:   # con «¿Arrancamos?» abierta, se contesta ahí
            return
        from ..ui.offpc import AwayDialog
        o = self.offpc
        self.offpc_dialog = AwayDialog(self, fmt(o["back"] - o["since"]), o["options"] + [AWAY])

    def _offpc_close_dialog(self):
        if self.offpc_dialog:
            self.offpc_dialog.close()
            self.offpc_dialog = None
