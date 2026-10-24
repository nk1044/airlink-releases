# Shared by setup-signing.sh and build.sh. Source it; don't run it.
#
# The identity lives in its own keychain with a generated password, so signing never prompts
# and nothing is added to the login keychain. macOS keys the Bluetooth permission to the app's
# designated requirement (bundle id + certificate), so a stable identity keeps the permission
# across rebuilds. Ad-hoc signatures change on every build and lose it.

AIRLINK_IDENTITY="AirLink Local Signing"
AIRLINK_KEYCHAIN="${AIRLINK_KEYCHAIN:-$HOME/Library/Keychains/airlink-signing.keychain-db}"
AIRLINK_PASSWORD_FILE="${AIRLINK_PASSWORD_FILE:-$HOME/.config/airlink/signing-keychain-password}"

# Prints the identity's SHA-1, or nothing if it is not set up.
airlink_identity_hash() {
    [[ -f "$AIRLINK_KEYCHAIN" && -f "$AIRLINK_PASSWORD_FILE" ]] || return 0
    security unlock-keychain -p "$(cat "$AIRLINK_PASSWORD_FILE")" "$AIRLINK_KEYCHAIN" >/dev/null 2>&1 || return 0
    # Without -v, so the self-signed (untrusted) identity is listed too; codesign accepts it by hash.
    security find-identity -p codesigning "$AIRLINK_KEYCHAIN" 2>/dev/null |
        awk -v name="\"$AIRLINK_IDENTITY\"" 'index($0, name) && $2 ~ /^[0-9A-F]{40}$/ { print $2; exit }'
}

# airlink_sign <bundle-or-binary> <identifier>
airlink_sign() {
    local target="$1" identifier="$2" hash
    hash="$(airlink_identity_hash)"
    if [[ -n "$hash" ]]; then
        codesign --force --sign "$hash" --keychain "$AIRLINK_KEYCHAIN" --identifier "$identifier" \
            --timestamp=none "$target"
        echo "Signed with \"$AIRLINK_IDENTITY\" (${hash:0:8}…)."
    else
        echo "warning: \"$AIRLINK_IDENTITY\" is not set up, so this build is signed ad-hoc." >&2
        echo "         macOS will ask for Bluetooth permission again after every rebuild." >&2
        echo "         Run scripts/setup-signing.sh once to fix that." >&2
        codesign --force --sign - --identifier "$identifier" "$target"
    fi
    codesign --verify --strict "$target"
}
