#!/usr/bin/env python3
"""Ciclo nocturno de Faro (corre en el VPS, aunque la compu esté apagada).

    faro_nightly.py reflect   01:30 · lee el diario, alertas, estadísticas, reacciones y respuestas; con Claude
                              reescribe las frases y creencias, deduce qué apps se valen en cada actividad,
                              clasifica apps nuevas, deja preguntas para mañana y un reporte en el buzón.
    faro_nightly.py watch     cada 15 min · si una versión nueva del teléfono reporta pruebas fallidas,
                              publica la anterior con un número mayor (rollback) y avisa.
    faro_nightly.py ask       cada día a media tarde · si nadie ha preguntado nada hoy, pregunta por sugerencias.

Solo cambia contenido y políticas (no lo que protege contra recaídas: listas de contenido, bloqueos, DNS).
Los cambios de código los hace `improve.sh`, que corre después y pasa por pruebas antes de publicar.
Todo queda en /opt/faro/logs.
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

HUB = os.environ.get("FARO_HUB", "http://127.0.0.1:8090")
ENV = "/opt/pomodoro-sync/.env"
CLAUDE = os.environ.get("CLAUDE_BIN", "/root/.local/bin/claude")
MODEL = os.environ.get("FARO_MODEL", "opus")
LOGS = "/opt/faro/logs"
# Permitir "messaging" quita el tope de WhatsApp: solo en actividades de chat o reuniones.
CHAT_RE = re.compile(r"chat|💬|reuni|daily|junta|llamada|meet|📞", re.I)
CATEGORIES = ["finance", "messaging", "maps", "work", "education", "reading", "shopping", "audio", "social", "video",
              "game", "other", "essential"]


def token():
    for line in open(ENV):
        if line.startswith("POMODORO_TOKEN="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("sin POMODORO_TOKEN")


def hub(method, path, body=None):
    req = urllib.request.Request(HUB + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": "Bearer " + token(), "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def log(name, text):
    os.makedirs(LOGS, exist_ok=True)
    with open(os.path.join(LOGS, f"{time.strftime('%Y-%m-%d')}-{name}.log"), "a") as f:
        f.write(f"[{time.strftime('%H:%M:%S')}] {text}\n")


def claude(prompt, name):
    """Claude sin herramientas, solo texto. Regresa el primer objeto JSON de la respuesta."""
    log(name, f"prompt: {len(prompt)} caracteres")
    out = subprocess.run([CLAUDE, "-p", "--model", MODEL, "--tools", "", "--output-format", "text"],
                         input=prompt, capture_output=True, text=True, timeout=900)
    log(name, f"salida ({out.returncode}): {out.stdout[:4000]}\n{out.stderr[-2000:]}")
    m = re.search(r"\{.*\}", out.stdout, re.S)
    if not m:
        raise RuntimeError("Claude no regresó JSON")
    return json.loads(m.group(0))


def compact_digest(d):
    """Lo justo para el prompt: sin repetir lo que no aporta."""
    j = d["journal"]
    return {
        "ahora": time.strftime("%Y-%m-%d %H:%M (%A)"),
        "diario": [{"cuando": time.strftime("%m-%d %H:%M", time.localtime(e["ts"])), "tipo": e["type"],
                    "auto": e.get("auto"), "texto": (e.get("text") or "")[:300], "disparador": e.get("trigger")}
                   for e in j[-250:]],
        "alertas": [{"cuando": time.strftime("%m-%d %H:%M", time.localtime(a["ts"])), "nivel": a.get("level"),
                     "app": a.get("app_name") or a.get("app"), "accion": a.get("action"), "razon": a.get("reason")}
                    for a in d["alerts"][-80:]],
        "pomodoros_por_dia": dict(list(d["stats"].items())[-14:]),
        "segundos_de_telefono_trabajando": dict(list(d["phone_stats"].items())[-14:]),
        "trabajo_efectivo_compu": dict(list(d["focus_stats"].items())[-14:]),
        "actividades": {k: {"nombre": v.get("label"), "tipo": v.get("kind"), "donde": v.get("lock", "both"),
                            "intencion": v.get("intent"), "apps_permitidas_por_el_usuario": v.get("allow")}
                        for k, v in d["activities"].items()},
        "politicas_actuales": d["policies"],
        "frases_actuales": [{"id": m.get("id"), "texto": m.get("text"), "cuando": m.get("when"), "puntos": m.get("score", 0)}
                            for m in (d["mind"].get("messages") or [])],
        "creencias_actuales": d["mind"].get("beliefs") or [],
        "preguntas_y_respuestas": [{"pregunta": a["question"], "respuesta": (a.get("answer") or {}).get("text")}
                                   for a in d["asks"]],
        "tareas_ticktick": [{"titulo": t["title"], "vence": t.get("due"), "proyecto": t.get("project")}
                            for t in (d["tasks"].get("items") or [])][:60],
        "tareas_pospuestas": d["snoozes"],
        "apps_sin_clasificar": d["apps_unclassified"],
        "pruebas_de_dispositivos": d["selftests"],
        "reporte_anterior": (d.get("nightly") or {}).get("summary"),
    }


PROMPT = """Eres el ciclo nocturno de Faro, el sistema personal de enfoque y bienestar de Mauricio (México, español
informal, le gusta directo y sin rodeos). Faro tiene: pomodoro en compu y teléfono con horario semanal, bloqueos
anti-distracción, vigilancia anti-pornografía (está dejando la pornografía y la masturbación compulsiva: PMO),
diario de recaídas/urgencias/victorias, frases que se muestran al desbloquear el teléfono y en los descansos
(para reforzar creencias que le sirven y desarmar las que no), preguntas que le hace el sistema, y sus tareas de TickTick.

Con los datos de abajo (últimos 14 días), razona sobre patrones: horarios y disparadores de urgencias y recaídas
(anotadas o detectadas solas), días buenos vs malos, cuándo se distrae, qué frases le sirven (puntos > 0) o no,
qué tareas pospone, y lo que él mismo sugirió. Cruza los datos. Si hubo una recaída o una urgencia fuerte en las
últimas 48 h, prioriza frases de autocompasión, plan concreto y reencuadre (sin culpa, sin sermón).

Regresa SOLO un objeto JSON (sin texto antes ni después) con esta forma:
{{
  "mind": {{
    "beliefs": [{{"id": "b1", "negative": "creencia que le estorba (en primera persona)", "positive": "la que la reemplaza"}}],
    "messages": [{{"id": "m-…", "text": "frase corta (≤ 90 caracteres), en segunda persona o primera, concreta",
                   "sub": "línea opcional ≤ 120 caracteres", "when": ["unlock"|"break"|"work"|"morning"|"night"|"urge"|"relapse"],
                   "weight": 1.0, "anim": "fade"|"rise"|"breath"}}],
    "unlock_every_min": 20
  }},
  "policies": {{"<id de actividad>": {{"allow": [categorías], "why": "por qué, ≤ 100 caracteres"}}}},
  "app_categories": {{"<paquete>": "<categoría>"}},
  "asks": [{{"question": "pregunta concreta para mañana", "options": ["opcional", "…"], "hour": 13}}],
  "report": {{"title": "≤ 60 caracteres", "short": "una línea (≤ 140) con lo más importante",
              "body": "reporte para leer en la app: patrones encontrados, qué cambiaste y por qué, y 1-3 acciones para mañana"}},
  "code_ideas": [{{"title": "mejora de código concreta y pequeña", "why": "evidencia en los datos o sugerencia del usuario"}}],
  "summary": "resumen interno ≤ 600 caracteres para el ciclo de mañana"
}}

Reglas:
- 3 a 6 creencias; 18 a 30 frases. Conserva el id de las frases que funcionan (puntos ≥ 0) y su texto;
  reescribe o quita las de puntos negativos. Nada genérico de taza de café: que se note que lo conoces.
- Categorías válidas: {cats}. "finance", "maps" y "essential" siempre se permiten; no hace falta listarlas.
  En las políticas solo actividades de tipo trabajo/enfocado; respeta la intención y el nombre.
  Si el usuario ya fijó apps permitidas a mano, no la incluyas. "messaging" quita el tope de WhatsApp:
  úsalo solo en actividades de chat o reuniones.
- Clasifica TODAS las apps sin clasificar (contenido sexual o de citas = "social").
- 1 o 2 preguntas como mucho; una de ellas pide sugerencias para mejorar Faro (concreta según los datos).
- No propongas aflojar la vigilancia anti-pornografía ni los bloqueos; sí puedes proponer reforzarlos.

DATOS:
{data}
"""


def reflect():
    d = hub("GET", "/api/digest?days=14")
    data = compact_digest(d)
    out = claude(PROMPT.format(cats=", ".join(CATEGORIES), data=json.dumps(data, ensure_ascii=False)), "reflect")

    # Frases: se conservan los puntos de las que siguen.
    old = {m.get("id"): m for m in (d["mind"].get("messages") or [])}
    mind = out.get("mind") or {}
    msgs = []
    for m in mind.get("messages") or []:
        if not m.get("text"):
            continue
        m["id"] = m.get("id") or f"m-{abs(hash(m['text'])) % 10**8}"
        prev = old.get(m["id"])
        if prev and prev.get("text") == m["text"]:
            m["score"] = prev.get("score", 0)
        msgs.append(m)
    if len(msgs) >= 8:
        mind["messages"] = msgs
        mind["version"] = int(d["mind"].get("version", 0)) + 1
        hub("PUT", "/api/mind", {"mind": mind})

    acts = d["activities"]
    def chatty(aid):
        a = acts[aid]
        return bool(CHAT_RE.search(f"{a.get('label', '')} {a.get('intent', '')}"))
    pol = {aid: {"allow": [c for c in p.get("allow", []) if c in CATEGORIES and (c != "messaging" or chatty(aid))],
                 "why": (p.get("why") or "")[:140]}
           for aid, p in (out.get("policies") or {}).items() if aid in acts and not acts[aid].get("allow")}
    if pol:
        hub("PUT", "/api/policies", {"policies": pol})

    cats = {k: v for k, v in (out.get("app_categories") or {}).items() if v in CATEGORIES}
    if cats:
        hub("PUT", "/api/apps/categories", {"categories": cats})

    tomorrow = time.time() + 6 * 3600
    for a in (out.get("asks") or [])[:2]:
        if a.get("question"):
            lt = time.localtime(tomorrow)
            when = time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday, int(a.get("hour") or 13), 0, 0, 0, 0, -1))
            hub("POST", "/api/asks", {"question": a["question"], "options": a.get("options") or [],
                                      "kind": "nocturna", "not_before": when})

    rep = out.get("report") or {}
    if rep.get("body"):
        hub("POST", "/api/inbox", {"title": rep.get("title") or "Reporte de la noche", "short": rep.get("short", ""),
                                   "body": rep["body"], "kind": "nightly", "source": "faro"})
    hub("PUT", "/api/nightly", {"summary": out.get("summary", ""), "code_ideas": out.get("code_ideas") or [],
                                "messages": len(msgs), "policies": len(pol), "categories": len(cats)})
    log("reflect", f"listo: {len(msgs)} frases, {len(pol)} políticas, {len(cats)} apps")


def watch():
    """Rollback: si la versión publicada falla sus pruebas en el teléfono, se republica la anterior."""
    st = hub("GET", "/api/state?source=faro")
    rel = st.get("release") or {}
    test = (st.get("selftests") or {}).get("phone") or {}
    prev = rel.get("previous") or {}
    if not rel or not prev.get("commit") or test.get("ok", True):
        return
    if str(test.get("version", "")).split(".")[-1] != str(rel.get("version_code")):
        return
    marker = f"/opt/faro/rolled-back-{rel['version_code']}"
    if os.path.exists(marker):
        return
    open(marker, "w").close()
    failed = ", ".join(k for k, v in (test.get("checks") or {}).items() if not v)
    log("watch", f"rollback de {rel['version_code']} a {prev['commit']} (fallaron: {failed})")
    subprocess.run(["/opt/faro/build.sh", prev["commit"], f"Regreso a la versión anterior: fallaron {failed}"], timeout=1500)
    hub("POST", "/api/inbox", {"title": "Regresé el teléfono a la versión anterior", "kind": "update",
                               "short": f"La versión {rel.get('version_name')} falló sus pruebas ({failed}).",
                               "body": f"La versión {rel.get('version_name')} reportó pruebas fallidas: {failed}. "
                                       f"Publiqué de nuevo el código {prev['commit']} con un número mayor; el teléfono "
                                       f"la instala solo."})


def ask():
    """Si hoy nadie ha preguntado nada, pregunta por sugerencias (a media tarde, en un descanso)."""
    st = hub("GET", "/api/state?source=faro")
    today = time.strftime("%Y-%m-%d")
    if any(time.strftime("%Y-%m-%d", time.localtime(a["ts"])) == today for a in st.get("asks") or []):
        return
    hub("POST", "/api/asks", {"question": "¿Qué le cambiarías hoy a Faro? Algo que estorbó, faltó o te ayudó.",
                              "kind": "diaria", "expires": time.time() + 20 * 3600})


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "reflect"
    try:
        {"reflect": reflect, "watch": watch, "ask": ask}[cmd]()
    except Exception as e:  # que quede en el log y en el buzón, no en silencio
        log(cmd, f"ERROR: {e!r}")
        if cmd == "reflect":
            try:
                hub("POST", "/api/inbox", {"title": "El ciclo nocturno falló", "short": str(e)[:140],
                                           "body": f"{e!r}\nRevisa /opt/faro/logs en el VPS.", "kind": "error",
                                           "source": "faro"})
            except Exception:
                pass
        raise
