"""Vigilancia del TEXTO de la pantalla (lo que NudeNet no ve: chats de «novia» de IA, relatos, búsquedas…).

Ligero a propósito, por capas, de la más barata a la más cara:
  1. el título de la ventana al frente (ya viene en focus.json): reglas de `core/risk.py`, gratis;
  2. si el título no dice nada y llevas `DWELL` s en esa página de una app de riesgo, la extensión vigía
     captura la ventana y se le saca el texto con tesseract (local, una vez por página, y otra vez cada
     `RECHECK` s si sigues ahí: en un chat el título no cambia pero la plática sí);
  3. si el texto solo trae una pista suave, decide la IA (`Judge.ask_risk`, con su tope de llamadas).
Lo que se detecta sube la misma escalera que las imágenes (`ScreenWatch.content_hit`). Nada se guarda:
la captura se borra al leerla. Sin tesseract solo funciona la capa 1.
"""
import os
import re
import shutil
import subprocess
import threading
import time

from ..core.focus import is_browser, norm_title
from ..core.risk import text_risk, title_risk
from .screenwatch import SHOT_DIR

TEXT_REQ = os.path.join(SHOT_DIR, "textreq")
TEXT_SHOT = os.path.join(SHOT_DIR, "text.png")
RISK_APPS = re.compile(r"telegram|discord|whatsapp|signal|eog|loupe|shotwell|gthumb|vlc|mpv|totem|celluloid")
EVERY = 6        # segundos entre avisos a la escalera mientras sigue al frente (como las imágenes)
DWELL = 8        # segundos en una página antes de leerle el texto
RECHECK = 90     # volver a leer la misma página si sigues en ella
TTL = 600        # un veredicto «limpio» de una página se recuerda esto


def _risky_app(app):
    return is_browser(app) or bool(RISK_APPS.search(app or ""))


class TextWatch:
    def __init__(self, app):
        self.app = app
        self.ocr = shutil.which("tesseract")
        self.seen = {}           # título normalizado → {"level", "ts" (último análisis), "since"}
        self.pending = set()     # títulos que la IA está revisando
        self.last_hit = 0.0
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        while True:
            try:
                self._check()
            except Exception:  # noqa: BLE001 — un sensor que falla no debe tumbar la app
                pass
            time.sleep(2)

    def _check(self):
        s = self.app.presence.fresh()
        if not s or not s.get("window") or s["locked"] or not _risky_app(s["app"]):
            return
        now = time.time()
        key = norm_title(s["title"])
        if not key:
            return
        self.seen = {k: v for k, v in self.seen.items() if now - v["ts"] < TTL or k == key}
        info = self.seen.setdefault(key, {"level": None, "ts": 0.0, "since": now})
        if info["level"] is None:
            tl = title_risk(s["title"])
            if tl:
                info["level"], info["ts"] = tl, now
        if info["level"] is None and self.ocr and s.get("ext_v", 1) >= 2 and key not in self.pending:
            due = now - info["since"] >= DWELL and now - info["ts"] >= RECHECK
            if due:
                info["ts"] = now
                text = self._read_screen()
                if text:
                    level = text_risk(text)
                    sig = hash(text[:900])
                    if level == "unsure" and self.app.cfg["focus_llm"] and info.get("asked") != sig:
                        info["asked"] = sig   # el mismo texto no se le vuelve a preguntar a la IA
                        self.pending.add(key)
                        self.app.judge.ask_risk(s["title"], text, lambda lv, k=key: self._ai_done(k, lv))
                    elif level != "unsure":
                        info["level"] = level
        if info["level"] and now - self.last_hit >= EVERY:
            self.last_hit = now
            level, name = info["level"], s["app"]
            self.app.after(0, lambda: self.app.screenwatch.content_hit(level, name, "texto"))

    def _ai_done(self, key, level):
        self.pending.discard(key)
        if key in self.seen:
            self.seen[key]["level"] = level

    def _read_screen(self):
        """Pide a la extensión una captura grande de la ventana al frente y le saca el texto."""
        try:
            os.remove(TEXT_SHOT)
        except OSError:
            pass
        try:
            with open(TEXT_REQ, "w") as f:
                f.write("1")
        except OSError:
            return ""
        for _ in range(20):
            time.sleep(0.25)
            if os.path.exists(TEXT_SHOT):
                break
        else:
            return ""
        time.sleep(0.2)   # que termine de escribirse
        try:
            r = subprocess.run(["nice", "-n", "15", self.ocr, TEXT_SHOT, "-", "-l", self._langs(), "--psm", "3"],
                               capture_output=True, text=True, timeout=20)
            return r.stdout
        except (OSError, subprocess.TimeoutExpired):
            return ""
        finally:
            try:
                os.remove(TEXT_SHOT)
            except OSError:
                pass

    def _langs(self):
        if not hasattr(self, "_lang_cache"):
            try:
                out = subprocess.run([self.ocr, "--list-langs"], capture_output=True, text=True, timeout=5).stdout
            except (OSError, subprocess.TimeoutExpired):
                out = ""
            self._lang_cache = "+".join(x for x in ("spa", "eng") if x in out.split()) or "eng"
        return self._lang_cache
