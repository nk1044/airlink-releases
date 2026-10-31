#!/usr/bin/env bash
# Undo the 2026-10-31 clean-up: re-enable the system apps that were disabled for user 0.
# (DistroTV and Internet Browser were uninstalled: reinstall from Play Store.)
#   scripts/restore-tv-apps.sh <tv-ip>
set -euo pipefail
IP="${1:?usage: scripts/restore-tv-apps.sh <tv-ip>}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
command -v "$ADB" >/dev/null 2>&1 || ADB=adb
SERIAL="$IP:5555"; [[ "$IP" == *:* ]] && SERIAL="$IP"
"$ADB" connect "$SERIAL" >/dev/null
for p in com.graymatrix.did com.android.browser com.softmedia.receiver com.vstv.ilink \
         com.vstv.appstore com.vstv.diagnosis com.vstv.tv.assistant; do
  "$ADB" -s "$SERIAL" shell pm enable "$p" && echo "re-enabled $p"
done
