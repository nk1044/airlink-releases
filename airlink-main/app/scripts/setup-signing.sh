#!/bin/bash
# Creates the "AirLink Local Signing" code-signing identity, once.
#
# It is a self-signed certificate in a dedicated keychain whose random password is stored in
# ~/.config/airlink (mode 600). Nothing is trusted system-wide and the login keychain is untouched.
# To undo: security delete-keychain ~/Library/Keychains/airlink-signing.keychain-db
#          rm -r ~/.config/airlink
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
source "$root/scripts/signing-common.sh"

if [[ -n "$(airlink_identity_hash)" ]]; then
    echo "\"$AIRLINK_IDENTITY\" already exists in $AIRLINK_KEYCHAIN. Nothing to do."
    exit 0
fi
if [[ -e "$AIRLINK_KEYCHAIN" ]]; then
    echo "error: $AIRLINK_KEYCHAIN exists but has no usable identity." >&2
    echo "       Delete it (security delete-keychain \"$AIRLINK_KEYCHAIN\") and run this again." >&2
    exit 1
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mkdir -p "$(dirname "$AIRLINK_PASSWORD_FILE")"
chmod 700 "$(dirname "$AIRLINK_PASSWORD_FILE")"
( umask 077; /usr/bin/openssl rand -hex 24 >"$AIRLINK_PASSWORD_FILE" )
password="$(cat "$AIRLINK_PASSWORD_FILE")"

# A code-signing certificate valid for ten years.
cat >"$work/cert.cnf" <<EOF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $AIRLINK_IDENTITY
[ext]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature
extendedKeyUsage = critical, codeSigning
EOF
/usr/bin/openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -config "$work/cert.cnf" \
    -keyout "$work/key.pem" -out "$work/cert.pem" 2>/dev/null
/usr/bin/openssl pkcs12 -export -inkey "$work/key.pem" -in "$work/cert.pem" -name "$AIRLINK_IDENTITY" \
    -out "$work/identity.p12" -passout "pass:$password" 2>/dev/null

# Keep the user's keychain search list as it was: codesign is pointed at this keychain directly.
search_list=()
while IFS= read -r line; do
    line="${line#"${line%%[![:space:]]*}"}"; line="${line%\"}"; line="${line#\"}"
    [[ -n "$line" ]] && search_list+=("$line")
done < <(security list-keychains -d user)

security create-keychain -p "$password" "$AIRLINK_KEYCHAIN"
security list-keychains -d user -s "${search_list[@]}"
security set-keychain-settings "$AIRLINK_KEYCHAIN"          # no auto-lock
security unlock-keychain -p "$password" "$AIRLINK_KEYCHAIN"
security import "$work/identity.p12" -k "$AIRLINK_KEYCHAIN" -P "$password" -T /usr/bin/codesign >/dev/null
# Let codesign use the key without a dialog.
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$password" "$AIRLINK_KEYCHAIN" >/dev/null

hash="$(airlink_identity_hash)"
if [[ -z "$hash" ]]; then
    echo "error: the identity was imported but codesign cannot see it." >&2
    exit 1
fi
echo "Created \"$AIRLINK_IDENTITY\" (${hash:0:8}…) in $AIRLINK_KEYCHAIN."
echo "Now run scripts/build.sh."
