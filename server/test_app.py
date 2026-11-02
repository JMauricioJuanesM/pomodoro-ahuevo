"""Pruebas del hub: `pytest server/` (las corre también el ciclo nocturno antes de publicar cambios)."""
import importlib
import os
import sys
import tempfile

import pytest
from fastapi.testclient import TestClient

H = {"Authorization": "Bearer t"}


@pytest.fixture()
def client(monkeypatch):
    d = tempfile.mkdtemp()
    monkeypatch.setenv("POMODORO_DATA", os.path.join(d, "state.json"))
    monkeypatch.setenv("POMODORO_TOKEN", "t")
    monkeypatch.setenv("TICKTICK_AUTH", os.path.join(d, "nope.json"))
    sys.path.insert(0, os.path.dirname(__file__))
    import app as mod
    mod = importlib.reload(mod)
    with TestClient(mod.app) as c:
        c.mod = mod
        yield c


def test_auth(client):
    assert client.get("/api/state").status_code == 401
    assert client.get("/api/state", headers=H).status_code == 200


def test_snapshot_is_light(client):
    client.post("/api/journal", headers=H, json={"type": "nota", "text": "x" * 1000})
    snap = client.get("/api/state", headers=H).json()
    assert "journal" not in snap and snap["journal_rev"] > 0


def test_journal_roundtrip_and_dedupe(client):
    r = client.post("/api/journal", headers=H, json={"type": "urgencia", "text": "a", "ext_id": "bot-1"})
    assert r.json()["id"]
    r2 = client.post("/api/journal", headers=H, json={"type": "urgencia", "text": "a", "ext_id": "bot-1"})
    assert r2.json()["duplicate"]
    assert client.post("/api/journal", headers=H, json={"type": "otra"}).status_code == 400
    items = client.get("/api/journal", headers=H).json()["items"]
    assert len(items) == 1
    client.delete(f"/api/journal/{items[0]['id']}", headers=H)
    assert client.get("/api/journal", headers=H).json()["items"] == []


def test_inbox(client):
    r = client.post("/api/inbox", headers=H, json={"title": "Reporte", "body": "largo", "short": "corto"}).json()
    assert r["url"].startswith("/app#inbox/")
    assert client.get("/api/state", headers=H).json()["inbox_unread"] == 1
    client.post(f"/api/inbox/{r['id']}/read", headers=H)
    assert client.get("/api/state", headers=H).json()["inbox_unread"] == 0


def test_mind_react(client):
    client.put("/api/mind", headers=H, json={"mind": {"messages": [{"id": "m1", "text": "Puedo", "when": ["unlock"]}]}})
    client.post("/api/mind/react", headers=H, json={"id": "m1", "value": 1, "source": "phone"})
    mind = client.get("/api/mind", headers=H).json()["mind"]
    assert mind["messages"][0]["score"] == 1
    assert client.get("/api/journal", headers=H).json()["items"][-1]["type"] == "reaccion"


def test_asks(client):
    aid = client.post("/api/asks", headers=H, json={"question": "¿Qué mejorarías?"}).json()["id"]
    client.post(f"/api/asks/{aid}/answer", headers=H, json={"text": "menos avisos", "source": "phone"})
    st = client.get("/api/state", headers=H).json()
    assert st["asks"][0]["answer"]["text"] == "menos avisos"
    assert client.get("/api/journal", headers=H).json()["items"][-1]["type"] == "sugerencia"


def test_release_keeps_previous(client):
    client.put("/api/release", headers=H, json={"version_code": 10, "version_name": "3.0"})
    client.put("/api/release", headers=H, json={"version_code": 11, "version_name": "3.1"})
    rel = client.get("/api/state", headers=H).json()["release"]
    assert rel["version_code"] == 11 and rel["previous"]["version_code"] == 10


def test_selftest_failure_alerts(client):
    client.post("/api/selftest", headers=H, json={"source": "phone", "version": "3.0", "ok": False,
                                                   "checks": {"guard": False, "sync": True}})
    st = client.get("/api/state", headers=H).json()
    assert st["alerts"][-1]["level"] == "selftest" and "guard" in st["alerts"][-1]["reason"]


def test_tasks_without_token(client):
    assert client.get("/api/tasks", headers=H).json()["items"] == []
    assert client.post("/api/tasks/refresh", headers=H).json()["ok"] is False


def test_web_login(client):
    assert "Token" in client.get("/app").text
    r = client.post("/app/login", data={"token": "t"}, follow_redirects=False)
    assert r.status_code == 303 and "faro=" in r.headers.get("set-cookie", "")
    client.cookies.set("faro", "t")
    assert "Faro" in client.get("/app").text
    assert client.get("/api/state").status_code == 200


def test_digest(client):
    client.post("/api/apps", headers=H, json={"apps": {"com.bbva.bancomer": "BBVA"}})
    d = client.get("/api/digest", headers=H).json()
    assert d["apps_unclassified"] == {"com.bbva.bancomer": "BBVA"}
    client.put("/api/apps/categories", headers=H, json={"categories": {"com.bbva.bancomer": "finance"}})
    assert client.get("/api/digest", headers=H).json()["apps_unclassified"] == {}


def test_alert_goes_to_journal(client):
    client.post("/api/alert", headers=H, json={"source": "phone", "level": "explicit", "app": "x", "app_name": "X"})
    items = client.get("/api/journal", headers=H).json()["items"]
    assert items[-1]["type"] == "urgencia" and items[-1]["auto"]
    client.post("/api/alert", headers=H, json={"source": "phone", "level": "selftest"})
    assert len(client.get("/api/journal", headers=H).json()["items"]) == 1
