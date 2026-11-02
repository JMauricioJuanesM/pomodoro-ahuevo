#!/usr/bin/env bash
# Compila la app del teléfono en el VPS a partir de un commit del repo, la prueba y la publica en el hub
# (el teléfono se la instala solo, ver Updater.kt). Cada compilación lleva un número mayor que la anterior,
# así también sirve para regresar a una versión vieja (rollback).
#   build.sh [ref] [notas]      ref: rama o commit de /opt/faro/repo.git (por defecto «auto»)
set -euo pipefail
REF=${1:-auto}
NOTES=${2:-""}
REPO=/opt/faro/repo.git
SRC=/opt/faro/build-src
APKDIR=/opt/pomodoro-sync/data/apk
TOKEN=$(grep ^POMODORO_TOKEN= /opt/pomodoro-sync/.env | cut -d= -f2-)
HUB=http://127.0.0.1:8090
LOG=/opt/faro/logs/$(date +%F)-build.log
mkdir -p /opt/faro/logs "$APKDIR"
exec >>"$LOG" 2>&1
echo "== $(date) build $REF"

rm -rf "$SRC" && git clone -q "$REPO" "$SRC" && git -C "$SRC" checkout -q "$REF"
COMMIT=$(git -C "$SRC" rev-parse --short HEAD)

# Pruebas antes de compilar: el hub y la app de la compu (sintaxis).
python3 -m py_compile $(git -C "$SRC" ls-files '*.py' | sed "s|^|$SRC/|")
if [ -x /opt/faro/venv/bin/pytest ]; then (cd "$SRC/server" && /opt/faro/venv/bin/pytest -q -p no:cacheprovider); fi

CUR=$(curl -s -H "Authorization: Bearer $TOKEN" "$HUB/api/state?source=faro" | python3 -c 'import json,sys; print((json.load(sys.stdin).get("release") or {}).get("version_code") or 10)')
CODE=$((CUR + 1))
cd "$SRC/android"
echo sdk.dir=/opt/android-sdk > local.properties
FARO_VERSION_CODE=$CODE JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 nice /opt/gradle-8.7/bin/gradle --no-daemon -q assembleDebug
APK=app/build/outputs/apk/debug/app-debug.apk
cp "$APK" "$APKDIR/Faro.apk.new" && mv "$APKDIR/Faro.apk.new" "$APKDIR/Faro.apk"
SHA=$(sha256sum "$APKDIR/Faro.apk" | cut -d' ' -f1)
python3 - "$CODE" "$SHA" "$COMMIT" "$NOTES" <<'PY' | curl -s -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d @- "$HUB/api/release"
import json, sys
code, sha, commit, notes = sys.argv[1:]
print(json.dumps({"version_code": int(code), "version_name": f"3.0.{code}", "sha256": sha, "commit": commit, "notes": notes}))
PY
echo "publicada 3.0.$CODE ($COMMIT)"
