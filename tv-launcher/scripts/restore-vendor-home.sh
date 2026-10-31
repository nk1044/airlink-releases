#!/usr/bin/env bash
# Hand Home back to the TV's own launcher (Orbit stays installed).
#   scripts/restore-vendor-home.sh <tv-ip>
set -euo pipefail
IP="${1:?usage: scripts/restore-vendor-home.sh <tv-ip>}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
command -v "$ADB" >/dev/null 2>&1 || ADB=adb
SERIAL="$IP:5555"; [[ "$IP" == *:* ]] && SERIAL="$IP"
"$ADB" connect "$SERIAL" >/dev/null
"$ADB" -s "$SERIAL" shell cmd package set-home-activity com.vstv.mtk.samsung.tv/.activity.SaActivity
"$ADB" -s "$SERIAL" shell pm disable dev.orbit.launcher/.BootReceiver >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_HOME
echo "Vendor launcher restored."
