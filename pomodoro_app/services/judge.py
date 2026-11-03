"""Juez con IA para lo que las reglas no saben decidir (páginas dudosas, apps desconocidas, lo que dices
que estás haciendo).

Usa Claude Code en modo headless (`claude -p`) con el modelo más barato, sin herramientas, sin sesión y
con un prompt de sistema mínimo (≈700 tokens por consulta). Para gastar lo menos posible:
  - solo se pregunta por algo que llevas un rato viendo (lo decide quien llama);
  - cada veredicto se recuerda en `focus_cache.json` (por actividad y título), y lo que tú corriges
    manda sobre lo que diga la IA;
  - se juntan varias preguntas en una sola llamada;
  - hay tope por hora y por día; al llegar, lo dudoso cuenta como neutral.
"""
import datetime as dt
import json
import os
import queue
import re
import shutil
import subprocess
import threading
import time

from ..paths import CONFIG_DIR

CACHE_FILE = os.path.join(CONFIG_DIR, "focus_cache.json")
WORK_DIR = os.path.join(CONFIG_DIR, "judge")      # carpeta vacía: que no cargue ningún CLAUDE.md
CACHE_DAYS = 30
BATCH = 6
RETRY_SEC = 15 * 60   # si la IA no respondió (sin cuota, sin red…), no volver a intentar antes de esto
LETTERS = {"P": "work", "N": "neutral", "D": "distract"}

SYS_WINDOWS = ("Eres el sensor de un pomodoro. Para cada ventana numerada decide si corresponde a la actividad "
               "que la persona debería estar haciendo según su horario. P = sí (trabajo, estudio o lo que diga la "
               "actividad, incluida documentación, IA, correo de trabajo o tutoriales del tema), N = neutral "
               "(no suma ni resta), D = distracción (ocio, redes, videos, compras, noticias no relacionadas). "
               "Responde solo líneas «número letra», sin nada más.")
SYS_OFFPC = ("Eres el sensor de un pomodoro. La persona se alejó de la compu en horario de trabajo y dice qué está "
             "haciendo. Decide: 1) si es una forma legítima de hacer la actividad de su horario (P) o una excusa, "
             "ocio u otra cosa (D); 2) dónde se hace: pc (se puede y debería hacer en la compu), phone (necesita "
             "el teléfono: llamada, mensajes de trabajo, autenticar, fotos) o none (lejos de las pantallas: "
             "imprimir, firmar, ir a una oficina, leer en papel, pensar con libreta); 3) cuántos minutos es "
             "razonable (5–60); 4) qué apps del teléfono se valen, de: messaging, work, maps, finance, reading, "
             "education, shopping, audio (vacío si ninguna). Los pasos caminados ayudan a ver si cuadra. "
             "Responde solo una línea «P none 20 maps,work» o «D».")
SYS_INTENT = ("Eres el sensor de un pomodoro. La persona dice qué está haciendo lejos de la pantalla. Decide si "
              "es una forma legítima de hacer la actividad de su horario (P) o una excusa / otra cosa (D). "
              "Responde solo la letra.")


def _today():
    return dt.date.today().isoformat()


class Judge:
    def __init__(self, app):
        self.app = app
        self.q = queue.Queue()
        self.lock = threading.Lock()
        self.inflight = set()
        self.hour_calls = []
        self.failed_until = 0
        self.data = self._load()
        self.available = bool(shutil.which(self._bin()))
        threading.Thread(target=self._loop, daemon=True).start()

    # ---------- caché ----------
    @staticmethod
    def _load():
        try:
            with open(CACHE_FILE) as f:
                d = json.load(f)
        except (OSError, ValueError):
            d = {}
        d.setdefault("verdicts", {})   # llave → {"v": veredicto, "ts"}
        d.setdefault("learned", {})    # llave → veredicto (lo que corregiste tú)
        d.setdefault("calls", {})      # día → llamadas a la IA
        d.setdefault("offpc", {})      # actividad|frase → {"r": veredicto fuera de la compu, "ts"}
        old = time.time() - CACHE_DAYS * 86400
        d["verdicts"] = {k: v for k, v in d["verdicts"].items() if v.get("ts", 0) > old}
        d["offpc"] = {k: v for k, v in d["offpc"].items() if v.get("ts", 0) > old}
        d["calls"] = {k: v for k, v in d["calls"].items() if k >= (dt.date.today() - dt.timedelta(days=14)).isoformat()}
        return d

    def _save(self):
        os.makedirs(CONFIG_DIR, exist_ok=True)
        with self.lock:
            text = json.dumps(self.data, ensure_ascii=False)
        with open(CACHE_FILE + ".tmp", "w") as f:
            f.write(text)
        os.replace(CACHE_FILE + ".tmp", CACHE_FILE)

    def learned(self, key):
        """Lo que corregiste tú para esto (manda sobre las reglas y la IA), o None."""
        with self.lock:
            return self.data["learned"].get(key)

    def known(self, key):
        """Lo que ya dijo la IA para esto, o None."""
        with self.lock:
            v = self.data["verdicts"].get(key)
            return v["v"] if v else None

    def learn(self, key, verdict):
        """Tú corriges: «esto sí es trabajo» / «esto me distrae». Manda sobre la IA."""
        with self.lock:
            self.data["learned"][key] = verdict
        self._save()

    def calls_today(self):
        return self.data["calls"].get(_today(), 0)

    # ---------- preguntas ----------
    def _budget_ok(self):
        cfg = self.app.cfg
        now = time.time()
        self.hour_calls = [t for t in self.hour_calls if now - t < 3600]
        return (self.available and now >= self.failed_until and len(self.hour_calls) < cfg["focus_llm_per_hour"]
                and self.calls_today() < cfg["focus_llm_per_day"])

    def ask(self, key, activity, app, title):
        """Encola la duda; la respuesta queda en la caché (se consulta con `known`)."""
        if key in self.inflight or self.known(key) or not self._budget_ok():
            return
        self.inflight.add(key)
        self.q.put(("window", key, activity, app, title))

    def ask_intent(self, activity, statement, callback):
        """¿Lo que dices que haces cuenta como la actividad? callback(True/False/None) en el hilo de la interfaz."""
        if not self._budget_ok():
            self.app.after(0, lambda: callback(None))
            return
        self.q.put(("intent", activity, statement, callback))

    def offpc_known(self, key):
        with self.lock:
            v = self.data["offpc"].get(key)
            return v["r"] if v else None

    def ask_offpc(self, key, activity, statement, away_min, steps, callback):
        """¿Lo que haces lejos de la compu cuenta? callback(dict | None) en el hilo de la interfaz, con
        {"ok", "where" (pc|phone|none), "minutes", "allow" [categorías]}. None: sin IA disponible."""
        cached = self.offpc_known(key)
        if cached:
            self.app.after(0, lambda: callback(dict(cached)))
            return
        if not self._budget_ok():
            self.app.after(0, lambda: callback(None))
            return
        self.q.put(("offpc", key, activity, statement, away_min, steps, callback))

    # ---------- hilo aparte ----------
    @staticmethod
    def _bin():
        """El ejecutable de Claude Code. Al arrancar con la sesión de GNOME el PATH no incluye nvm, así que
        también se busca donde suele instalarse."""
        if os.environ.get("POMODORO_JUDGE_BIN"):
            return os.environ["POMODORO_JUDGE_BIN"]
        found = shutil.which("claude")
        if found:
            return found
        home = os.path.expanduser("~")
        nvm = os.path.join(home, ".nvm", "versions", "node")
        candidates = [os.path.join(home, ".claude", "local", "claude"), os.path.join(home, ".local", "bin", "claude")]
        try:
            candidates += [os.path.join(nvm, v, "bin", "claude") for v in sorted(os.listdir(nvm), reverse=True)]
        except OSError:
            pass
        return next((c for c in candidates if os.access(c, os.X_OK)), "claude")

    def _call(self, system, prompt):
        self.hour_calls.append(time.time())
        with self.lock:
            self.data["calls"][_today()] = self.calls_today() + 1
        os.makedirs(WORK_DIR, exist_ok=True)
        exe = self._bin()
        env = dict(os.environ, PATH=os.path.dirname(exe) + os.pathsep + os.environ.get("PATH", ""))
        cmd = [exe, "-p", "--model", self.app.cfg["focus_llm_model"], "--tools", "",
               "--disable-slash-commands", "--no-session-persistence", "--setting-sources", "",
               "--system-prompt", system, "--output-format", "text"]
        try:
            r = subprocess.run(cmd, input=prompt, capture_output=True, text=True, timeout=45, cwd=WORK_DIR, env=env)
            out = r.stdout.strip() if r.returncode == 0 else ""
        except (OSError, subprocess.TimeoutExpired):
            out = ""
        if not re.search(r"\b[PND]\b", out):
            self.failed_until = time.time() + RETRY_SEC
        return out

    def _loop(self):
        while True:
            first = self.q.get()
            if first[0] == "intent":
                self._intent(*first[1:])
                continue
            if first[0] == "offpc":
                self._offpc(*first[1:])
                continue
            batch = [first]
            time.sleep(3)   # juntar lo que llegue casi al mismo tiempo
            while len(batch) < BATCH:
                try:
                    item = self.q.get_nowait()
                except queue.Empty:
                    break
                if item[0] in ("intent", "offpc"):
                    self.q.put(item)
                    break
                batch.append(item)
            try:
                self._windows(batch)
            finally:
                for item in batch:
                    self.inflight.discard(item[1])
                self._save()

    def _windows(self, batch):
        lines = [f"{i}. Actividad: {act} · App: {app} · Ventana: {title[:120]}"
                 for i, (_, _, act, app, title) in enumerate(batch, 1)]
        out = self._call(SYS_WINDOWS, "\n".join(lines))
        for m in re.finditer(r"(\d+)\W+([PND])\b", out):
            i = int(m.group(1)) - 1
            if 0 <= i < len(batch):
                with self.lock:
                    self.data["verdicts"][batch[i][1]] = {"v": LETTERS[m.group(2)], "ts": time.time()}

    def _intent(self, activity, statement, callback):
        out = self._call(SYS_INTENT, f"Actividad: {activity}\nDice: {statement[:200]}")
        self._save()
        verdict = True if out.startswith("P") else False if out.startswith("D") else None
        self.app.after(0, lambda: callback(verdict))

    def _offpc(self, key, activity, statement, away_min, steps, callback):
        prompt = f"Actividad: {activity}\nLleva fuera: {away_min} min\nDice: {statement[:200]}"
        if steps is not None:
            prompt += f"\nPasos que contó el teléfono mientras: {steps}"
        out = self._call(SYS_OFFPC, prompt)
        r = parse_offpc(out)
        if r and steps is None:   # con pasos cada caso es distinto; sin ellos, la frase basta para recordar
            with self.lock:
                self.data["offpc"][key] = {"r": r, "ts": time.time()}
        self._save()
        self.app.after(0, lambda: callback(r))


CATS = ("messaging", "work", "maps", "finance", "reading", "education", "shopping", "audio")


def parse_offpc(out):
    """«P none 20 maps,work» → dict; «D» → {"ok": False}; otra cosa → None."""
    m = re.match(r"\s*([PD])\b\s*(pc|phone|none)?\s*(\d+)?\s*([a-z, ]*)", out or "")
    if not m:
        return None
    if m.group(1) == "D":
        return {"ok": False, "where": None, "minutes": 0, "allow": []}
    allow = [c for c in re.split(r"[, ]+", m.group(4) or "") if c in CATS]
    minutes = max(5, min(60, int(m.group(3) or 15)))
    return {"ok": True, "where": m.group(2) or "none", "minutes": minutes, "allow": allow}
