#!/usr/bin/env bash
# Instala (o actualiza) en el VPS lo de Faro que corre solo: repo para la mejora nocturna, pruebas y cron.
set -euo pipefail
cd /opt/faro
chmod +x faro_nightly.py build.sh improve.sh
[ -d repo.git ] || git init -q --bare repo.git
[ -x venv/bin/pytest ] || { python3 -m venv venv && venv/bin/pip install -q fastapi==0.115.6 httpx python-multipart==0.0.20 pytest; }
# Para que los enlaces /app del bot abran la app (verificación de Android App Links).
grep -q ^APK_CERT_SHA256= /opt/pomodoro-sync/.env || {
  FP=$(keytool -list -v -keystore ~/.android/debug.keystore -storepass android 2>/dev/null | awk '/SHA256:/ {print $2; exit}')
  [ -n "$FP" ] && echo "APK_CERT_SHA256=$FP" >> /opt/pomodoro-sync/.env && (cd /opt/pomodoro-sync && docker compose up -d)
}
CRON=$(crontab -l 2>/dev/null | grep -v '/opt/faro/' || true)
{
  echo "$CRON"
  echo "30 1 * * * /opt/faro/faro_nightly.py reflect >/dev/null 2>&1"
  echo "15 2 * * * /opt/faro/improve.sh >/dev/null 2>&1"
  echo "*/15 * * * * /opt/faro/faro_nightly.py watch >/dev/null 2>&1"
  echo "30 16 * * * /opt/faro/faro_nightly.py ask >/dev/null 2>&1"
} | crontab -
echo "Faro instalado: $(crontab -l | grep -c /opt/faro/) tareas en cron"
