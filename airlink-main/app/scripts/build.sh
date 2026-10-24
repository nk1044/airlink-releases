#!/bin/bash
# Builds build/AirLink.app (release, stripped, signed). With --install, replaces
# /Applications/AirLink.app and launches it.
#
# Environment:
#   UNIVERSAL=1      build for Apple silicon and Intel (used for releases)
#   VERSION=1.2.0    stamp CFBundleShortVersionString (default: the value in Packaging/Info.plist)
#   BUILD_NUMBER=42  stamp CFBundleVersion
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
source "$root/scripts/signing-common.sh"
cd "$root"

install=false
for arg in "$@"; do
    case "$arg" in
        --install) install=true ;;
        -h|--help) echo "usage: scripts/build.sh [--install]"; exit 0 ;;
        *) echo "unknown option $arg" >&2; exit 2 ;;
    esac
done

bundle_id="io.github.nk1044.airlink"
app="$root/build/AirLink.app"

arch_flags=()
[[ "${UNIVERSAL:-0}" == 1 ]] && arch_flags=(--arch arm64 --arch x86_64)

echo "→ swift build -c release ${arch_flags[*]+${arch_flags[*]}}"
swift build -c release --product AirLink ${arch_flags[@]+"${arch_flags[@]}"}
binary="$(swift build -c release --show-bin-path ${arch_flags[@]+"${arch_flags[@]}"})/AirLink"

echo "→ assembling $app"
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$binary" "$app/Contents/MacOS/AirLink"
strip -S -x "$app/Contents/MacOS/AirLink"
cp Packaging/Info.plist "$app/Contents/Info.plist"
[[ -n "${VERSION:-}" ]] && plutil -replace CFBundleShortVersionString -string "$VERSION" "$app/Contents/Info.plist"
[[ -n "${BUILD_NUMBER:-}" ]] && plutil -replace CFBundleVersion -string "$BUILD_NUMBER" "$app/Contents/Info.plist"
plutil -lint -s "$app/Contents/Info.plist"
printf 'APPL????' >"$app/Contents/PkgInfo"

[[ -f Packaging/AppIcon.icns ]] || swift scripts/make-icon.swift Packaging/AppIcon.icns
cp Packaging/AppIcon.icns "$app/Contents/Resources/AppIcon.icns"
# Optional custom menu bar artwork (template PDF); see MenuBarIcon in Theme.swift.
[[ -f Packaging/MenuBarIcon.pdf ]] && cp Packaging/MenuBarIcon.pdf "$app/Contents/Resources/MenuBarIcon.pdf"

echo "→ signing"
airlink_sign "$app" "$bundle_id"
echo "Built $app ($(du -sh "$app" | cut -f1 | tr -d ' '), binary $(du -h "$app/Contents/MacOS/AirLink" | cut -f1 | tr -d ' '))"

if $install; then
    echo "→ installing to /Applications"
    if pgrep -xq AirLink; then
        osascript -e "tell application id \"$bundle_id\" to quit" >/dev/null 2>&1 || true
        for _ in 1 2 3 4 5 6 7 8 9 10; do pgrep -xq AirLink || break; sleep 0.3; done
        pkill -x AirLink 2>/dev/null || true
    fi
    rm -rf /Applications/AirLink.app
    ditto "$app" /Applications/AirLink.app
    open /Applications/AirLink.app
    echo "Installed and launched /Applications/AirLink.app"
fi
