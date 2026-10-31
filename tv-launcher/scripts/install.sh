#!/usr/bin/env bash
# Build, install, AOT-compile and (optionally) make Orbit the Home app on the TV.
#   scripts/install.sh <tv-ip> [--home] [--no-build]
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=dev.orbit.launcher
IP="${1:?usage: scripts/install.sh <tv-ip> [--home] [--no-build]}"; shift
MAKE_HOME=0; BUILD=1
for a in "$@"; do
  case "$a" in
    --home) MAKE_HOME=1 ;;
    --no-build) BUILD=0 ;;
  esac
done

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
command -v "$ADB" >/dev/null 2>&1 || ADB=adb
SERIAL="$IP:5555"
[[ "$IP" == *:* ]] && SERIAL="$IP"

if [[ $BUILD == 1 ]]; then
  if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17 ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  fi
  ./gradlew -q assembleRelease
fi
APK=app/build/outputs/apk/release/app-release.apk

# "No route to host" while the TV pings fine is a stale adb server.
if ! "$ADB" connect "$SERIAL" | grep -q "connected"; then
  "$ADB" kill-server; "$ADB" connect "$SERIAL"
fi
A=("$ADB" -s "$SERIAL")

echo "› installing"
"${A[@]}" install -r "$APK"

# This TV runs with the JIT off: uncompiled code is interpreted. Compile everything now.
if [[ "$("${A[@]}" shell getprop dalvik.vm.usejit | tr -d '\r')" == "false" ]]; then
  echo "› compiling (speed), about a minute on this TV"
  "${A[@]}" shell cmd package compile -m speed -f "$PKG"
  "${A[@]}" shell dumpsys package dexopt | grep -A2 "\[$PKG\]" || true
fi

if [[ $MAKE_HOME == 1 ]]; then
  echo "› setting Orbit as the Home app"
  "${A[@]}" shell cmd package set-home-activity "$PKG/.HomeActivity"
  "${A[@]}" shell input keyevent KEYCODE_HOME
else
  "${A[@]}" shell am start -W -n "$PKG/.HomeActivity" | grep -E "Status|TotalTime" || true
fi
echo "› done. Back to the TV's own launcher any time: scripts/restore-vendor-home.sh $IP"
