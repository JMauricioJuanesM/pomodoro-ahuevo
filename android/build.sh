#!/usr/bin/env bash
# Compila la app en el VPS (tiene JDK 17 + Android SDK) y la instala en el teléfono por ADB.
#   ./android/build.sh            compilar e instalar
#   ./android/build.sh --no-install
set -euo pipefail
HOST=${POMODORO_BUILD_HOST:-contabo}
DIR=$(cd "$(dirname "$0")" && pwd)
OUT="$DIR/build/Pomodoro.apk"

rsync -a --delete --exclude build --exclude .gradle --exclude local.properties "$DIR/" "$HOST:/opt/pomodoro-build/"
ssh "$HOST" 'cd /opt/pomodoro-build && echo sdk.dir=/opt/android-sdk > local.properties &&
  JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 nice /opt/gradle-8.7/bin/gradle --no-daemon -q assembleDebug'
mkdir -p "$DIR/build"
scp -q "$HOST:/opt/pomodoro-build/app/build/outputs/apk/debug/app-debug.apk" "$OUT"
echo "APK: $OUT"
[[ "${1:-}" == "--no-install" ]] || adb install -r "$OUT"
