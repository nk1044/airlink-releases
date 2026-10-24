#!/bin/bash
# Guided Phase 0 probe run. Builds airlink-probe, walks through every check in PROTOCOL.md §7,
# and saves everything to probe-output.txt for pasting back.
set -uo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/probe-output.txt"
step_log="$(mktemp -t airlink-probe)"
trap 'rm -f "$step_log"' EXIT
cd "$root"

swift build -c release --product airlink-probe >/dev/null || { echo "build failed"; exit 1; }
probe="$(swift build -c release --show-bin-path)/airlink-probe"

bold=$'\e[1m'; plain=$'\e[0m'

# step "<title>" "<what to do first, or empty>" <probe args…>
step() {
    local title="$1" todo="$2"
    shift 2
    echo
    echo "════════════════════════════════════════════════════════════"
    echo "${bold}${title}${plain}"
    if [ -n "$todo" ]; then
        echo
        printf '%s\n' "$todo" | sed 's/^/  /'
        echo
        read -r -p "  Press Enter when you've done that… " _
    fi
    echo "\$ airlink-probe $*" | tee -a "$out"
    "$probe" "$@" 2>&1 | tee -a "$out" | tee "$step_log"
    echo | tee -a "$out" >/dev/null
}

# Stops the run if the last step could not open the control channel.
require_connection() {
    if grep -q '^error:' "$step_log" || grep -q '^No matching connected device' "$step_log"; then
        echo
        echo "${bold}Could not connect to the buds, so the remaining steps would show nothing new.${plain}"
        echo "Stopping here. Paste the contents of: $out"
        exit 1
    fi
}

: >"$out"
{ echo "airlink-probe session $(date -u +%FT%TZ)"; sw_vers; } >>"$out"

step "Step 1 of 9: list services" \
"• Connect the buds to this Mac for audio (they should play sound from the Mac).
• Put BOTH buds in your ears and keep them there until step 7." \
    list

step "Step 2 of 9: info" "" info
require_connection
step "Step 3 of 9: info without hello" "" info --no-hello

step "Step 4 of 9: switch to Transparency" "" noise set transparency
step "Step 5 of 9: switch to ANC" "" noise set anc
step "Step 6 of 9: switch to Off" "" noise set off

step "Step 7 of 9: ANC with only one bud in" \
"• Take the RIGHT bud out of your ear and put it in the case.
• Keep the LEFT bud in your ear.
• Leave the case lid OPEN." \
    noise set anc

step "Step 8 of 9: Transparency with no bud in your ears" \
"• Now take the LEFT bud out of your ear too.
• Hold it in your hand. Do NOT put it in the case.
  (The right bud stays in the open case.)" \
    noise set transparency

step "Step 9 of 9: watch for updates from the buds (30 seconds)" \
"• Put BOTH buds back in your ears.
• After you press Enter you have 30 seconds to do this, in order:
    1. Touch and hold one bud until you hear the mode change. Do it twice.
    2. Take one bud out, put it in the case, close the lid, wait 3 seconds, open the lid." \
    monitor 30

echo
echo "${bold}Done.${plain} Paste the contents of: $out"
