#!/usr/bin/env bash
# Sube el hub al VPS y lo reinicia (el token vive en /opt/pomodoro-sync/.env), junto con el ciclo
# nocturno y el compilador de la app (/opt/faro, ver nightly/).
set -euo pipefail
HOST=${POMODORO_BUILD_HOST:-contabo}
DIR=$(cd "$(dirname "$0")" && pwd)
rsync -a "$DIR/app.py" "$DIR/screen.py" "$DIR/Dockerfile" "$DIR/docker-compose.yml" "$DIR/nginx.conf" "$DIR/web" \
  "$HOST:/opt/pomodoro-sync/"
rsync -a --delete --exclude __pycache__ "$DIR/nightly/" "$HOST:/opt/faro/"
ssh "$HOST" 'cd /opt/pomodoro-sync && docker compose up -d --build && sleep 3 && curl -s 127.0.0.1:8090/health &&
  /opt/faro/install.sh'
