"""Servidor de sincronización del Pomodoro (un solo usuario).

Guarda en un JSON la configuración que comparten la compu y el teléfono, el estado del
temporizador y las estadísticas. Los clientes se enteran de los cambios al instante con
`GET /api/wait` (long-poll: responde en cuanto cambia algo o tras `timeout` segundos).
"""
import asyncio
import json
import os
import secrets
import time

from fastapi import Depends, FastAPI, Header, HTTPException, Request

DATA = os.environ.get("POMODORO_DATA", "/data/state.json")
TOKEN = os.environ["POMODORO_TOKEN"]

app = FastAPI(title="Pomodoro sync", docs_url=None, redoc_url=None)
changed = asyncio.Condition()


def _load():
    try:
        with open(DATA) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {"rev": 0, "config": None, "config_rev": 0, "config_source": None,
                "timer": None, "timer_rev": 0, "timer_source": None,
                "stats": {}, "phone_stats": {}, "devices": {}}


state = _load()


def _save():
    os.makedirs(os.path.dirname(DATA), exist_ok=True)
    with open(DATA + ".tmp", "w") as f:
        json.dump(state, f, ensure_ascii=False)
    os.replace(DATA + ".tmp", DATA)


def auth(authorization: str = Header("")):
    if not secrets.compare_digest(authorization, f"Bearer {TOKEN}"):
        raise HTTPException(401, "token inválido")


def seen(source, info=None):
    dev = state["devices"].setdefault(source, {})
    dev["seen"] = time.time()
    if info:
        dev["info"] = info


def snapshot():
    return dict(state, server_time=time.time())


def merge_max(target, incoming):
    """Estadísticas por día: nos quedamos con el valor más alto que haya visto cualquier cliente."""
    for day, v in (incoming or {}).items():
        if isinstance(v, (int, float)) and v > target.get(day, 0):
            target[day] = v


async def bump():
    state["rev"] += 1
    _save()
    async with changed:
        changed.notify_all()


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


@app.post("/api/heartbeat", dependencies=[Depends(auth)])
async def heartbeat(request: Request):
    """Señal de vida con datos del dispositivo. No despierta a los demás (no cambia `rev`)."""
    body = await request.json()
    seen(body["source"], body.get("info"))
    merge_max(state["stats"], body.get("stats"))
    merge_max(state["phone_stats"], body.get("phone_stats"))
    _save()
    return {"rev": state["rev"]}
