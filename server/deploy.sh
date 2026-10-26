#!/usr/bin/env bash
# Sube el servidor de sincronización al VPS y lo reinicia (el token vive en /opt/pomodoro-sync/.env).
set -euo pipefail
HOST=${POMODORO_BUILD_HOST:-contabo}
DIR=$(cd "$(dirname "$0")" && pwd)
scp -q "$DIR/app.py" "$DIR/screen.py" "$DIR/Dockerfile" "$DIR/docker-compose.yml" "$DIR/nginx.conf" "$HOST:/opt/pomodoro-sync/"
ssh "$HOST" 'cd /opt/pomodoro-sync && docker compose up -d --build && sleep 2 && curl -s 127.0.0.1:8090/health'
