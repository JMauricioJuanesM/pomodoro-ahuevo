#!/usr/bin/env bash
# Mejora de código nocturna: Claude Code (sin la compu) toma las ideas del ciclo de reflexión, tus sugerencias
# y las pruebas fallidas, hace 1 o 2 cambios pequeños en la rama «auto» del repo del VPS, y solo si pasan las
# pruebas los publica: la app del teléfono se compila y se instala sola; la compu los trae al prenderse
# (faro-sync). Nunca toca lo que protege contra recaídas (listas de contenido, autodefensa, bloqueos, vigilancia).
set -euo pipefail
WORK=/opt/faro/work
REPO=/opt/faro/repo.git
CLAUDE=${CLAUDE_BIN:-/root/.local/bin/claude}
TOKEN=$(grep ^POMODORO_TOKEN= /opt/pomodoro-sync/.env | cut -d= -f2-)
HUB=http://127.0.0.1:8090
LOG=/opt/faro/logs/$(date +%F)-improve.log
PROTECTED='(AdultDb|Strikes|SelfDefense|Blocks|BankMode|ScreenWatch)\.kt|server/screen\.py|server/nightly/|gnome-extension/|services/screenwatch\.py|AndroidManifest\.xml'
MAX_LINES=400
mkdir -p /opt/faro/logs
exec >>"$LOG" 2>&1
echo "== $(date) improve"

hub() { curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "$@"; }

[ -d "$WORK" ] || git clone -q "$REPO" "$WORK"
cd "$WORK"
git config user.name "Faro nocturno"; git config user.email "faro@mauriciojuanes.com"
git fetch -q origin
git checkout -q -B auto "$(git rev-parse --verify -q origin/auto || git rev-parse origin/master)"
git reset -q --hard
# Lo que hayas hecho tú en la compu manda: si no se puede mezclar limpio, se empieza de nuevo desde ahí.
git merge -q --no-edit origin/master || { git merge --abort; git reset -q --hard origin/master; }

IDEAS=$(hub "$HUB/api/digest?days=7" | python3 -c '
import json, sys, time
d = json.load(sys.stdin)
ideas = (d.get("nightly") or {}).get("code_ideas") or []
sug = [e["text"] for e in d["journal"] if e["type"] == "sugerencia"]
fails = {k: [c for c, ok in (v.get("checks") or {}).items() if not ok] for k, v in d["selftests"].items() if not v.get("ok", True)}
if not ideas and not sug and not fails:
    sys.exit(0)
print(json.dumps({"ideas_del_ciclo": ideas, "sugerencias_del_usuario": sug[-15:], "pruebas_fallidas": fails}, ensure_ascii=False, indent=1))
')
[ -n "$IDEAS" ] || { echo "nada que mejorar"; exit 0; }

PROMPT="Eres el ciclo de mejora nocturna de Faro (este repositorio: app de pomodoro y bienestar en compu, teléfono
Android y servidor). Lee README.md primero. Con lo de abajo, implementa como mucho 2 mejoras PEQUEÑAS, seguras y
verificables (≤ ${MAX_LINES} líneas en total), priorizando: 1) pruebas fallidas, 2) sugerencias explícitas del usuario,
3) ideas del ciclo. Reglas:
- No toques archivos que coincidan con: ${PROTECTED}. Nada que afloje la vigilancia anti-pornografía o los bloqueos.
- Respeta el estilo del código (comentarios en español, mismo idioma y densidad). Kotlin sin dependencias nuevas.
- Si cambia el comportamiento visible, actualiza README.md.
- No hagas commits ni push. Al terminar escribe un resumen de 1-3 líneas de lo que cambiaste, empezando con «CAMBIOS:».
- Si nada es seguro de hacer, no cambies nada y escribe «CAMBIOS: ninguno» con la razón.

$IDEAS"
timeout 2400 "$CLAUDE" -p --model opus --permission-mode acceptEdits \
  --allowedTools "Read" "Edit" "Write" "Glob" "Grep" "Bash(python3 -m py_compile *)" "Bash(git diff *)" "Bash(git status *)" \
  <<<"$PROMPT" > /tmp/faro-improve.out || true
cat /tmp/faro-improve.out
SUMMARY=$(grep -A3 "CAMBIOS:" /tmp/faro-improve.out | head -4 | sed 's/^CAMBIOS: *//' | tr '\n' ' ')

git add -A
if git diff --cached --quiet; then echo "sin cambios"; exit 0; fi
if git diff --cached --name-only | grep -Eq "$PROTECTED"; then
  echo "tocó archivos protegidos: se descarta"; git reset -q --hard; exit 0
fi
LINES=$(git diff --cached --numstat | awk '{s+=$1+$2} END {print s+0}')
if [ "$LINES" -gt "$MAX_LINES" ]; then echo "demasiado grande ($LINES líneas): se descarta"; git reset -q --hard; exit 0; fi
python3 -m py_compile $(git ls-files '*.py') || { echo "no compila (python)"; git reset -q --hard; exit 0; }
if [ -x /opt/faro/venv/bin/pytest ]; then
  (cd server && /opt/faro/venv/bin/pytest -q -p no:cacheprovider) || { echo "fallan las pruebas del hub"; git reset -q --hard; exit 0; }
fi
STAT=$(git diff --cached --stat | tail -20)
TITLE=$(printf '%s' "${SUMMARY:-cambios pequeños}" | sed 's/\. .*/./' | cut -c1-90)
git commit -q -m "Mejora nocturna: $TITLE" -m "$SUMMARY"
git push -q origin auto
COMMIT=$(git rev-parse --short HEAD)

PUBLISHED="no hizo falta compilar la app"
if git diff --name-only HEAD~1 | grep -q '^android/'; then
  if /opt/faro/build.sh auto "${SUMMARY}"; then PUBLISHED="la app nueva ya está publicada: el teléfono se la instala solo"
  else PUBLISHED="la app NO compiló: no se publicó (revisa /opt/faro/logs)"; git push -q -f origin HEAD~1:auto; fi
fi
python3 - "$SUMMARY" "$STAT" "$PUBLISHED" "$COMMIT" <<'PY' | hub -X POST -d @- "$HUB/api/inbox" >/dev/null
import json, sys
summary, stat, published, commit = sys.argv[1:]
print(json.dumps({"title": "Mejora nocturna de Faro", "short": summary[:140], "kind": "update", "source": "faro",
                  "body": f"{summary}\n\nCommit {commit} en la rama auto ({published}). La compu lo trae al prenderse.\n\n{stat}"}))
PY
echo "listo: $COMMIT"
