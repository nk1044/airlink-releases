#!/usr/bin/env bash
# The §9 checklist numbers: dexopt status, cold start, memory, frame stats.
#   scripts/measure.sh <tv-ip>
#   Hold the D-pad across the rows for ~20 s when prompted.
set -euo pipefail
PKG=dev.orbit.launcher
IP="${1:?usage: scripts/measure.sh <tv-ip>}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
command -v "$ADB" >/dev/null 2>&1 || ADB=adb
SERIAL="$IP:5555"; [[ "$IP" == *:* ]] && SERIAL="$IP"
A=("$ADB" -s "$SERIAL")
"$ADB" connect "$SERIAL" >/dev/null

echo "== dexopt";      "${A[@]}" shell dumpsys package dexopt | grep -A2 "\[$PKG\]" | grep -o "status=[a-z-]*" | head -1
echo "== cold start";  "${A[@]}" shell am force-stop $PKG; sleep 1
"${A[@]}" shell am start -W -n $PKG/.HomeActivity | grep -E "TotalTime|WaitTime"
sleep 3
echo "== PSS on Home"; "${A[@]}" shell dumpsys meminfo $PKG | grep -E "TOTAL( PSS)?:|TOTAL  " | head -2
"${A[@]}" shell dumpsys gfxinfo $PKG reset >/dev/null
read -r -p "Scroll Home with the remote for ~20 s, then press Enter… "
echo "== frames";      "${A[@]}" shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|percentile"
echo "== after UI_HIDDEN"
"${A[@]}" shell am start -a android.settings.SETTINGS >/dev/null; sleep 4
"${A[@]}" shell dumpsys meminfo $PKG | grep -E "TOTAL( PSS)?:|TOTAL  " | head -2
"${A[@]}" shell input keyevent KEYCODE_HOME
