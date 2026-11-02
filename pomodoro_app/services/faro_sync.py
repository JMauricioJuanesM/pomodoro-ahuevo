"""Al prender la compu: sincroniza el código con el VPS, donde corre la mejora nocturna (server/nightly/).

  - Sube tu rama principal al repo del VPS (`vps`), para que la mejora de esta noche parta de lo último.
  - Trae la rama `auto` (lo que mejoró el ciclo nocturno ya probado) y, si tu copia está limpia y no
    divergió, la adelanta (fast-forward). Si no, solo avisa: tú decides.

La app del teléfono no depende de esto: se actualiza sola desde el hub, o por ADB si está conectado.
"""
import os
import subprocess
import threading

from .system import notify

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REMOTE_URL = os.environ.get("FARO_VPS_REPO", "contabo:/opt/faro/repo.git")


def git(*args, timeout=60):
    return subprocess.run(["git", "-C", REPO, *args], capture_output=True, text=True, timeout=timeout)


def sync():
    if git("rev-parse", "--git-dir").returncode != 0:
        return
    if git("remote", "get-url", "vps").returncode != 0:
        git("remote", "add", "vps", REMOTE_URL)
    branch = git("branch", "--show-current").stdout.strip()
    if branch not in ("master", "main"):
        return
    if git("push", "-q", "vps", f"{branch}:master", timeout=120).returncode != 0:
        return   # sin red o sin VPS: otro día
    if git("fetch", "-q", "vps", "auto", timeout=120).returncode != 0:
        return
    ahead = git("rev-list", "--count", f"{branch}..FETCH_HEAD").stdout.strip()
    if not ahead or ahead == "0":
        return
    clean = not git("status", "--porcelain", "--untracked-files=no").stdout.strip()
    log = git("log", "--format=· %s", f"{branch}..FETCH_HEAD").stdout.strip()
    if clean and git("merge", "-q", "--ff-only", "FETCH_HEAD").returncode == 0:
        git("push", "-q", "vps", f"{branch}:master", timeout=120)
        notify("🗼 Faro se mejoró anoche", f"{log}\nSe aplica al reiniciar la app.")
    else:
        notify("🗼 Hay mejoras nocturnas de Faro", f"{log}\nTu copia tiene cambios: tráelas con «git merge vps/auto».")


def start():
    threading.Thread(target=lambda: _safe(sync), daemon=True).start()


def _safe(fn):
    try:
        fn()
    except (OSError, subprocess.SubprocessError):
        pass
