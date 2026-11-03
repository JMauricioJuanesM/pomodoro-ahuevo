"""Hub de Faro: servidor de sincronización del Pomodoro, del Panel ADB y del bot (un solo usuario).

Guarda en un JSON la configuración que comparten la compu y el teléfono, el estado del
temporizador, las estadísticas y los bloqueos del teléfono (lo que el Panel ADB bloqueó y los
desbloqueos temporales con su hora de vencimiento, para que el teléfono los cumpla aunque la compu
esté apagada).

También analiza capturas de pantalla para detectar desnudez (`screen.py`, en memoria, sin guardarlas)
y guarda las alertas que resultan, para que el bot de Telegram te escriba. Los clientes se enteran de los cambios al instante con
`GET /api/wait` (long-poll: responde en cuanto cambia algo o tras `timeout` segundos).

Además (Faro):
  - diario: recaídas, urgencias, victorias, notas y sugerencias, desde la app, la web o el bot;
  - buzón: lo que antes eran mensajes largos del bot; a Telegram solo llega una línea con el enlace;
  - mente: frases y creencias que se muestran al desbloquear el teléfono y en los descansos;
  - preguntas: lo que el sistema te pregunta (p. ej. qué mejorarías) en una pantalla completa;
  - tareas: espejo de TickTick (se consulta cada pocos minutos) con completar y posponer;
  - políticas: qué apps se permiten en cada actividad del horario (las deduce la IA cada noche);
  - versiones: el APK más nuevo, que el teléfono se instala solo, y las pruebas que reporta.

Lo pesado (diario, buzón, mente, tareas) no viaja en `/api/wait`: ahí van solo sus `*_rev`, y cada
cliente pide lo que cambió.
"""
import asyncio
import contextlib
import json
import os
import secrets
import time
import urllib.request
import uuid

from fastapi import Cookie, Depends, FastAPI, Form, Header, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse

DATA = os.environ.get("POMODORO_DATA", "/data/state.json")
TOKEN = os.environ["POMODORO_TOKEN"]
APK = os.path.join(os.path.dirname(DATA), "apk", "Faro.apk")
WEB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")
TICKTICK_AUTH = os.environ.get("TICKTICK_AUTH", "/ticktick/ticktick_auth.json")
TICKTICK_API = "https://api.ticktick.com/open/v1"
# Huella del certificado con que se firma el APK (para que los enlaces /app abran la app).
APK_CERT = os.environ.get("APK_CERT_SHA256", "")

changed = asyncio.Condition()


@contextlib.asynccontextmanager
async def lifespan(_app):
    loop = asyncio.create_task(_tasks_loop())
    yield
    loop.cancel()


app = FastAPI(title="Faro hub", docs_url=None, redoc_url=None, lifespan=lifespan)

# Lo que no viaja en el long-poll (cada uno con su `<clave>_rev` en el estado).
HEAVY = ("journal", "inbox", "mind", "tasks", "app_categories", "apps_seen", "nightly")
# Alertas que cuentan como urgencia detectada (no como recaída: eso solo lo dices tú).
AUTO_JOURNAL = {"explicit": "urgencia", "suggestive": "urgencia", "url": "urgencia", "adult_app": "urgencia",
                "private": "urgencia", "skip": "nota", "self_defense": "urgencia"}
JOURNAL_TYPES = {"recaida", "urgencia", "victoria", "nota", "sugerencia", "animo", "reaccion"}


def _load():
    try:
        with open(DATA) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {"rev": 0, "config": None, "config_rev": 0, "config_source": None,
                "timer": None, "timer_rev": 0, "timer_source": None,
                "stats": {}, "phone_stats": {}, "devices": {}}


state = _load()
state.setdefault("blocks", None)
state.setdefault("blocks_rev", 0)
state.setdefault("blocks_source", None)
state.setdefault("alerts", [])
for _k, _v in {"journal": [], "inbox": [], "mind": {}, "tasks": {"items": [], "updated": 0},
               "asks": [], "policies": {}, "app_categories": {}, "apps_seen": {}, "release": None,
               "selftests": {}, "snoozes": {}, "current_task": None, "nightly": {}}.items():
    state.setdefault(_k, _v)
    if _k in HEAVY:
        state.setdefault(_k + "_rev", 0)


def _save():
    os.makedirs(os.path.dirname(DATA), exist_ok=True)
    with open(DATA + ".tmp", "w") as f:
        json.dump(state, f, ensure_ascii=False)
    os.replace(DATA + ".tmp", DATA)


async def _raw_body(request: Request) -> bytes:
    return await request.body()


def auth(authorization: str = Header(""), faro: str = Cookie("")):
    """Token en la cabecera (apps, bot) o en la cookie `faro` (la web)."""
    if not (secrets.compare_digest(authorization, f"Bearer {TOKEN}") or secrets.compare_digest(faro, TOKEN)):
        raise HTTPException(401, "token inválido")


def seen(source, info=None):
    dev = state["devices"].setdefault(source, {})
    dev["seen"] = time.time()
    if info:
        dev["info"] = info


def snapshot():
    snap = {k: v for k, v in state.items() if k not in HEAVY}
    snap["inbox_unread"] = sum(1 for e in state["inbox"] if not e.get("read"))
    snap["server_time"] = time.time()
    return snap


def merge_max(target, incoming):
    """Estadísticas por día: nos quedamos con el valor más alto que haya visto cualquier cliente."""
    for day, v in (incoming or {}).items():
        if isinstance(v, (int, float)) and v > target.get(day, 0):
            target[day] = v


async def bump(*heavy):
    """Algo cambió: sube `rev` (y el `*_rev` de lo pesado que se tocó) y despierta a los que esperan."""
    state["rev"] += 1
    for k in heavy:
        state[k + "_rev"] = state["rev"]
    _save()
    async with changed:
        changed.notify_all()


def _id():
    return uuid.uuid4().hex[:12]


@app.get("/health")
def health():
    return {"ok": True}


@app.get("/api/state", dependencies=[Depends(auth)])
def get_state(source: str = ""):
    if source:
        seen(source)
    return snapshot()


@app.get("/api/wait", dependencies=[Depends(auth)])
async def wait(rev: int = 0, source: str = "", timeout: float = 40):
    if source:
        seen(source)
    if state["rev"] == rev:
        try:
            async with changed:
                await asyncio.wait_for(changed.wait_for(lambda: state["rev"] != rev), min(timeout, 55))
        except asyncio.TimeoutError:
            pass
    # "Visto" solo al empezar a esperar: si el cliente se apagó a media espera, no cuenta como vivo.
    return snapshot()


@app.put("/api/config", dependencies=[Depends(auth)])
async def put_config(request: Request):
    body = await request.json()
    state["config"] = body["config"]
    state["config_rev"] = state["rev"] + 1
    state["config_source"] = body.get("source")
    await bump()
    return {"rev": state["rev"]}


@app.put("/api/timer", dependencies=[Depends(auth)])
async def put_timer(request: Request):
    body = await request.json()
    timer = body["timer"]
    timer["updated"] = time.time()
    state["timer"] = timer
    state["timer_rev"] = state["rev"] + 1
    state["timer_source"] = body.get("source")
    st = timer.get("stats") or {}
    if st.get("day"):
        merge_max(state["stats"], {st["day"]: st.get("count", 0)})
    await bump()
    return {"rev": state["rev"]}


@app.put("/api/blocks", dependencies=[Depends(auth)])
async def put_blocks(request: Request):
    body = await request.json()
    blocks = body["blocks"]
    blocks["updated"] = time.time()
    state["blocks"] = blocks
    state["blocks_rev"] = state["rev"] + 1
    state["blocks_source"] = body.get("source")
    await bump()
    return {"rev": state["rev"]}


@app.post("/api/screen", dependencies=[Depends(auth)])
def check_screen(body: bytes = Depends(_raw_body)):
    """Una captura reducida (JPEG) → nivel de desnudez. Corre en el pool de hilos: no frena el long-poll."""
    from screen import classify
    if not body or len(body) > 2_000_000:
        raise HTTPException(400, "imagen vacía o demasiado grande")
    return classify(body)


@app.post("/api/alert", dependencies=[Depends(auth)])
async def put_alert(request: Request):
    """Se detectó contenido: queda registrado y despierta a los demás (el bot te escribe)."""
    body = await request.json()
    alert = {"ts": time.time(), "source": body.get("source"), "level": body.get("level"),
             "app": body.get("app"), "app_name": body.get("app_name"), "action": body.get("action"),
             "reason": (body.get("reason") or "")[:300]}
    state["alerts"] = (state["alerts"] + [alert])[-200:]
    # Lo que se detecta solo también va al diario (marcado «auto»), para que el ciclo nocturno lo cruce
    # con lo que tú anotas.
    kind = AUTO_JOURNAL.get(alert["level"])
    if kind:
        text = f"Detectado en {alert['app_name'] or alert['app'] or '?'} ({alert['source']}): {alert['level']}"
        if alert["reason"]:
            text += f" · {alert['reason']}"
        entry = {"id": _id(), "ts": alert["ts"], "type": kind, "text": text[:2000], "source": alert["source"] or "?",
                 "auto": True, "level": None, "trigger": alert["level"], "ext_id": None}
        state["journal"] = (state["journal"] + [entry])[-3000:]
        await bump("journal")
    else:
        await bump()
    return {"rev": state["rev"]}


@app.post("/api/heartbeat", dependencies=[Depends(auth)])
async def heartbeat(request: Request):
    """Señal de vida con datos del dispositivo. No despierta a los demás (no cambia `rev`)."""
    body = await request.json()
    seen(body["source"], body.get("info"))
    merge_max(state["stats"], body.get("stats"))
    merge_max(state["phone_stats"], body.get("phone_stats"))
    _save()
    return {"rev": state["rev"]}


# ---------- diario (recaídas, urgencias, victorias, notas, sugerencias) ----------

@app.get("/api/journal", dependencies=[Depends(auth)])
def get_journal(since: float = 0, limit: int = 500):
    items = [e for e in state["journal"] if e["ts"] > since]
    return {"rev": state["journal_rev"], "items": items[-limit:]}


@app.post("/api/journal", dependencies=[Depends(auth)])
async def add_journal(request: Request):
    """Una entrada. `ext_id` (p. ej. el id del bot) evita duplicados si se reintenta."""
    body = await request.json()
    kind = body.get("type")
    if kind not in JOURNAL_TYPES:
        raise HTTPException(400, f"tipo inválido: {kind}")
    ext = body.get("ext_id")
    if ext and any(e.get("ext_id") == ext for e in state["journal"]):
        return {"rev": state["rev"], "duplicate": True}
    entry = {"id": _id(), "ts": float(body.get("ts") or time.time()), "type": kind,
             "text": (body.get("text") or "")[:2000], "source": body.get("source") or "?",
             "auto": bool(body.get("auto")), "level": body.get("level"),
             "trigger": (body.get("trigger") or "")[:200] or None, "ext_id": ext}
    state["journal"] = sorted(state["journal"] + [entry], key=lambda e: e["ts"])[-3000:]
    await bump("journal")
    return {"rev": state["rev"], "id": entry["id"]}


@app.delete("/api/journal/{eid}", dependencies=[Depends(auth)])
async def del_journal(eid: str):
    state["journal"] = [e for e in state["journal"] if e["id"] != eid]
    await bump("journal")
    return {"rev": state["rev"]}


# ---------- buzón (mensajes del bot y del ciclo nocturno) ----------

@app.get("/api/inbox", dependencies=[Depends(auth)])
def get_inbox(limit: int = 100):
    return {"rev": state["inbox_rev"], "items": state["inbox"][-limit:][::-1]}


@app.post("/api/inbox", dependencies=[Depends(auth)])
async def add_inbox(request: Request):
    """El mensaje completo se queda aquí; regresa el enlace para mandar solo una línea por Telegram."""
    body = await request.json()
    item = {"id": _id(), "ts": time.time(), "title": (body.get("title") or "")[:120],
            "short": (body.get("short") or "")[:280], "body": (body.get("body") or "")[:20000],
            "kind": body.get("kind") or "info", "source": body.get("source") or "bot", "read": False}
    state["inbox"] = (state["inbox"] + [item])[-400:]
    await bump("inbox")
    return {"rev": state["rev"], "id": item["id"], "url": f"/app#inbox/{item['id']}"}


@app.post("/api/inbox/{iid}/read", dependencies=[Depends(auth)])
async def read_inbox(iid: str):
    for e in state["inbox"]:
        if e["id"] == iid or iid == "all":
            e["read"] = True
    await bump("inbox")
    return {"rev": state["rev"]}


# ---------- mente (frases que se muestran al desbloquear y en los descansos) ----------

@app.get("/api/mind", dependencies=[Depends(auth)])
def get_mind():
    return {"rev": state["mind_rev"], "mind": state["mind"]}


@app.put("/api/mind", dependencies=[Depends(auth)])
async def put_mind(request: Request):
    body = await request.json()
    mind = body["mind"]
    mind["updated"] = time.time()
    state["mind"] = mind
    await bump("mind")
    return {"rev": state["rev"]}


@app.post("/api/mind/react", dependencies=[Depends(auth)])
async def react_mind(request: Request):
    """👍/👎 a una frase: suma a su peso y queda en el diario para el ciclo nocturno."""
    body = await request.json()
    mid, val = body.get("id"), int(body.get("value") or 0)
    for m in state["mind"].get("messages", []):
        if m.get("id") == mid:
            m["score"] = m.get("score", 0) + val
            m["shown"] = m.get("shown", 0)
            entry = {"id": _id(), "ts": time.time(), "type": "reaccion", "text": f"{val:+d} {m.get('text', '')[:200]}",
                     "source": body.get("source") or "?", "auto": True, "level": val, "trigger": mid, "ext_id": None}
            state["journal"] = (state["journal"] + [entry])[-3000:]
    await bump("mind", "journal")
    return {"rev": state["rev"]}


# ---------- preguntas (pantalla completa que pide tu opinión) ----------

@app.post("/api/asks", dependencies=[Depends(auth)])
async def add_ask(request: Request):
    body = await request.json()
    ask = {"id": _id(), "ts": time.time(), "question": body["question"][:500],
           "kind": body.get("kind") or "sugerencia", "options": body.get("options") or [],
           "not_before": body.get("not_before") or 0, "expires": body.get("expires") or time.time() + 3 * 86400,
           "devices": body.get("devices") or ["phone", "desktop"], "answer": None}
    state["asks"] = [a for a in state["asks"] if a["answer"] is None and a["expires"] > time.time()][-20:] + [ask]
    await bump()
    return {"rev": state["rev"], "id": ask["id"]}


@app.post("/api/asks/{aid}/answer", dependencies=[Depends(auth)])
async def answer_ask(aid: str, request: Request):
    body = await request.json()
    text = (body.get("text") or "").strip()
    for a in state["asks"]:
        if a["id"] == aid and a["answer"] is None:
            a["answer"] = {"text": text[:2000], "ts": time.time(), "source": body.get("source")}
            if text:
                entry = {"id": _id(), "ts": time.time(), "type": "sugerencia",
                         "text": f"P: {a['question']}\nR: {text}"[:2000], "source": body.get("source") or "?",
                         "auto": False, "level": None, "trigger": a["kind"], "ext_id": None}
                state["journal"] = (state["journal"] + [entry])[-3000:]
    # Las contestadas se quedan un día (para que los demás dispositivos dejen de mostrarla).
    state["asks"] = [a for a in state["asks"] if a["answer"] is None or a["answer"]["ts"] > time.time() - 86400]
    await bump("journal")
    return {"rev": state["rev"]}


# ---------- políticas por actividad y categorías de apps (las escribe el ciclo nocturno) ----------

@app.put("/api/policies", dependencies=[Depends(auth)])
async def put_policies(request: Request):
    body = await request.json()
    state["policies"] = body["policies"]
    await bump()
    return {"rev": state["rev"]}


@app.get("/api/apps", dependencies=[Depends(auth)])
def get_apps():
    return {"categories": state["app_categories"], "seen": state["apps_seen"]}


@app.post("/api/apps", dependencies=[Depends(auth)])
async def report_apps(request: Request):
    """El teléfono manda sus apps instaladas (paquete → nombre) para que la IA las clasifique de noche."""
    body = await request.json()
    state["apps_seen"].update({k: str(v)[:80] for k, v in (body.get("apps") or {}).items()})
    _save()
    return {"rev": state["rev"]}


@app.put("/api/apps/categories", dependencies=[Depends(auth)])
async def put_app_categories(request: Request):
    body = await request.json()
    state["app_categories"].update(body["categories"])
    # Viaja en el long-poll como `app_categories_rev`; además va completo en la config del teléfono.
    await bump("app_categories")
    return {"rev": state["rev"]}


@app.get("/api/apps/categories", dependencies=[Depends(auth)])
def get_app_categories():
    return {"rev": state["app_categories_rev"], "categories": state["app_categories"]}


# ---------- tareas (espejo de TickTick) ----------

def _ticktick_token():
    try:
        with open(TICKTICK_AUTH) as f:
            return json.load(f).get("access_token")
    except (OSError, ValueError):
        return None


def _tt(method, path, body=None):
    token = _ticktick_token()
    if not token:
        raise HTTPException(503, "TickTick no está vinculado (falta el token del bot)")
    req = urllib.request.Request(TICKTICK_API + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        raw = r.read()
    return json.loads(raw) if raw else None


def _fetch_tasks():
    """Todas las tareas abiertas (bandeja de entrada y proyectos abiertos), lo justo para las apps."""
    projects = [p for p in (_tt("GET", "/project") or []) if not p.get("closed")]
    items = []
    for pid, pname in [("inbox", "Bandeja")] + [(p["id"], p["name"]) for p in projects]:
        try:
            data = _tt("GET", f"/project/{pid}/data") or {}
        except Exception:
            continue
        for t in data.get("tasks") or []:
            if t.get("status", 0) != 0:
                continue
            items.append({"id": t["id"], "project_id": t.get("projectId") or pid, "project": pname,
                          "title": t.get("title", ""), "due": t.get("dueDate"), "start": t.get("startDate"),
                          "all_day": bool(t.get("isAllDay")), "priority": t.get("priority", 0),
                          "tz": t.get("timeZone"), "content": (t.get("content") or "")[:500]})
    return items


async def refresh_tasks():
    try:
        items = await asyncio.to_thread(_fetch_tasks)
    except HTTPException:
        return False
    except Exception as e:  # red, token vencido…: se queda lo último que hubo
        state["tasks"]["error"] = str(e)[:200]
        return False
    old = {(t["id"], t["title"], t["due"]) for t in state["tasks"].get("items", [])}
    new = {(t["id"], t["title"], t["due"]) for t in items}
    state["tasks"] = {"items": items, "updated": time.time(), "error": None}
    live = {t["id"] for t in items}
    state["snoozes"] = {k: v for k, v in state["snoozes"].items() if k in live and v["until"] > time.time()}
    if old != new:
        await bump("tasks")
    else:
        _save()
    return True


async def _tasks_loop():
    while True:
        await refresh_tasks()
        await asyncio.sleep(300)


@app.get("/api/tasks", dependencies=[Depends(auth)])
def get_tasks():
    return {"rev": state["tasks_rev"], **state["tasks"], "snoozes": state["snoozes"],
            "current": state["current_task"]}


@app.post("/api/tasks/refresh", dependencies=[Depends(auth)])
async def tasks_refresh():
    return {"ok": await refresh_tasks(), "rev": state["rev"]}


def _task(tid):
    return next((t for t in state["tasks"]["items"] if t["id"] == tid), None)


@app.post("/api/tasks/{tid}/complete", dependencies=[Depends(auth)])
async def complete_task(tid: str, request: Request):
    body = await request.json()
    t = _task(tid)
    pid = body.get("project_id") or (t and t["project_id"])
    if not pid:
        raise HTTPException(404, "no conozco esa tarea")
    await asyncio.to_thread(_tt, "POST", f"/project/{pid}/task/{tid}/complete")
    state["tasks"]["items"] = [x for x in state["tasks"]["items"] if x["id"] != tid]
    if (state["current_task"] or {}).get("id") == tid:
        state["current_task"] = None
    state["snoozes"].pop(tid, None)
    entry = {"id": _id(), "ts": time.time(), "type": "nota", "text": f"✅ {t['title'] if t else tid}",
             "source": body.get("source") or "?", "auto": True, "level": None, "trigger": "tarea", "ext_id": None}
    state["journal"] = (state["journal"] + [entry])[-3000:]
    await bump("tasks", "journal")
    return {"rev": state["rev"]}


@app.post("/api/tasks/{tid}/snooze", dependencies=[Depends(auth)])
async def snooze_task(tid: str, request: Request):
    """Posponer el aviso (no cambia la tarea en TickTick). Cada vez cuenta: a la tercera pide razón."""
    body = await request.json()
    prev = state["snoozes"].get(tid) or {"count": 0}
    # «La hago ahora» también la quita de en medio un rato, pero no cuenta como posponerla.
    state["snoozes"][tid] = {"until": time.time() + 60 * float(body.get("minutes") or 30),
                             "count": prev["count"] + (1 if body.get("count", True) else 0),
                             "reason": (body.get("reason") or "")[:300]}
    if body.get("reason"):
        t = _task(tid)
        entry = {"id": _id(), "ts": time.time(), "type": "nota",
                 "text": f"⏰ Pospuse «{t['title'] if t else tid}»: {body['reason']}"[:2000],
                 "source": body.get("source") or "?", "auto": True, "level": None, "trigger": "tarea", "ext_id": None}
        state["journal"] = (state["journal"] + [entry])[-3000:]
    await bump("tasks", "journal")
    return {"rev": state["rev"]}


@app.put("/api/tasks/current", dependencies=[Depends(auth)])
async def set_current_task(request: Request):
    """La tarea en la que trabajas ahora (sale en el pomodoro de la compu y del teléfono)."""
    body = await request.json()
    state["current_task"] = body.get("task")
    await bump()
    return {"rev": state["rev"]}


@app.post("/api/tasks", dependencies=[Depends(auth)])
async def create_task(request: Request):
    body = await request.json()
    task = {"title": body["title"][:300]}
    if body.get("project_id"):
        task["projectId"] = body["project_id"]
    if body.get("due"):
        task["dueDate"] = body["due"]
    created = await asyncio.to_thread(_tt, "POST", "/task", task)
    await refresh_tasks()
    return {"rev": state["rev"], "id": (created or {}).get("id")}


# ---------- versiones de la app y pruebas automáticas ----------

@app.put("/api/release", dependencies=[Depends(auth)])
async def put_release(request: Request):
    """Lo llama el compilador del VPS al dejar un APK nuevo en data/apk/ (ya probado)."""
    body = await request.json()
    rel = {k: body.get(k) for k in ("version_code", "version_name", "sha256", "notes", "commit", "test_fail")}
    rel["ts"] = time.time()
    rel["previous"] = {k: v for k, v in (state["release"] or {}).items() if k != "previous"} or None
    state["release"] = rel
    await bump()
    return {"rev": state["rev"]}


@app.get("/api/apk", dependencies=[Depends(auth)])
def get_apk():
    if not os.path.exists(APK):
        raise HTTPException(404, "no hay APK")
    return FileResponse(APK, media_type="application/vnd.android.package-archive", filename="Faro.apk")


@app.post("/api/selftest", dependencies=[Depends(auth)])
async def selftest(request: Request):
    """Resultado de las pruebas que corre cada dispositivo al arrancar o actualizarse."""
    body = await request.json()
    src = body.get("source") or "?"
    res = {"ts": time.time(), "version": body.get("version"), "ok": bool(body.get("ok")),
           "checks": body.get("checks") or {}}
    state["selftests"][src] = res
    if not res["ok"]:
        failed = ", ".join(k for k, v in res["checks"].items() if not v) or "?"
        state["alerts"] = (state["alerts"] + [{"ts": time.time(), "source": src, "level": "selftest",
                                               "app": "faro", "app_name": f"Faro {res['version']}",
                                               "action": "selftest", "reason": f"Fallaron: {failed}"}])[-200:]
    await bump()
    return {"rev": state["rev"]}


# ---------- ciclo nocturno ----------

@app.get("/api/digest", dependencies=[Depends(auth)])
def digest(days: int = 14):
    """Todo lo necesario para que el ciclo nocturno (o el bot) razone sobre los últimos días."""
    since = time.time() - days * 86400
    cfg = state["config"] or {}
    return {
        "server_time": time.time(),
        "journal": [e for e in state["journal"] if e["ts"] > since],
        "alerts": [a for a in state["alerts"] if a["ts"] > since],
        "stats": state["stats"], "phone_stats": state["phone_stats"],
        "focus_stats": cfg.get("focus_stats", {}),
        "activities": cfg.get("activities", {}), "schedule": cfg.get("schedule", {}),
        "policies": state["policies"], "mind": state["mind"],
        "asks": state["asks"], "tasks": state["tasks"], "snoozes": state["snoozes"],
        "apps_unclassified": {k: v for k, v in state["apps_seen"].items() if k not in state["app_categories"]},
        "devices": state["devices"], "selftests": state["selftests"], "release": state["release"],
        "nightly": state["nightly"],
    }


@app.put("/api/nightly", dependencies=[Depends(auth)])
async def put_nightly(request: Request):
    body = await request.json()
    state["nightly"] = {**body, "ts": time.time()}
    await bump("nightly")
    return {"rev": state["rev"]}


@app.get("/api/nightly", dependencies=[Depends(auth)])
def get_nightly():
    return state["nightly"]


# ---------- web (/app): diario, buzón, tareas y mente desde cualquier navegador ----------

@app.get("/app", response_class=HTMLResponse)
def web_app(faro: str = Cookie("")):
    if not secrets.compare_digest(faro, TOKEN):
        return HTMLResponse(open(os.path.join(WEB, "login.html"), encoding="utf-8").read())
    return HTMLResponse(open(os.path.join(WEB, "index.html"), encoding="utf-8").read())


@app.post("/app/login")
def web_login(token: str = Form("")):
    resp = RedirectResponse("/app", status_code=303)
    if secrets.compare_digest(token.strip(), TOKEN):
        resp.set_cookie("faro", TOKEN, max_age=400 * 86400, httponly=True, secure=True, samesite="lax")
    return resp


@app.get("/.well-known/assetlinks.json")
def assetlinks():
    if not APK_CERT:
        return []
    return [{"relation": ["delegate_permission/common.handle_all_urls"],
             "target": {"namespace": "android_app", "package_name": "com.mauriciojuanes.pomodoro",
                        "sha256_cert_fingerprints": [APK_CERT]}}]
