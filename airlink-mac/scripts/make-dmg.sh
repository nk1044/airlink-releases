#!/bin/bash
# Packages build/AirLink.app into dist/AirLink.dmg: the app next to an Applications shortcut,
# so installing is a drag. Run scripts/build.sh first.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
app="$root/build/AirLink.app"
out="$root/dist/AirLink.dmg"

[[ -d "$app" ]] || { echo "error: $app not found; run scripts/build.sh first" >&2; exit 1; }

staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT
ditto "$app" "$staging/AirLink.app"
ln -s /Applications "$staging/Applications"

mkdir -p "$root/dist"
rm -f "$out"
hdiutil create -volname "AirLink" -srcfolder "$staging" -fs HFS+ -format UDZO \
    -imagekey zlib-level=9 -ov "$out" >/dev/null
shasum -a 256 "$out" | sed "s|$root/dist/||" >"$out.sha256"
echo "Created $out ($(du -h "$out" | cut -f1 | tr -d ' '))"
