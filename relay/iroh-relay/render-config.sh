#!/bin/sh
# Builds /etc/iroh-relay/config.toml from the template and the token list. Run as root on the VM.
# Tokens live only in /etc/iroh-relay/tokens (one per line, 0600, root). Blank lines and # lines are ignored.
# This script never prints a token.
set -eu

DIR=/etc/iroh-relay
TEMPLATE="$DIR/config.template.toml"
TOKENS="$DIR/tokens"
OUT="$DIR/config.toml"

[ "$(id -u)" -eq 0 ] || { echo "run as root" >&2; exit 1; }
[ -r "$TEMPLATE" ] || { echo "missing $TEMPLATE" >&2; exit 1; }
[ -r "$TOKENS" ] || { echo "missing $TOKENS" >&2; exit 1; }

list=""
count=0
# The loop reads the file directly (no pipe) so count survives it.
while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ""|"#"*) continue ;; esac
    if ! printf '%s' "$line" | grep -Eq '^[A-Za-z0-9_-]{32,}$'; then
        echo "a line in $TOKENS is not a valid token (32 or more letters, digits, - or _)" >&2
        exit 1
    fi
    list="${list:+$list, }\"$line\""
    count=$((count + 1))
done < "$TOKENS"

[ "$count" -ge 1 ] || { echo "no tokens in $TOKENS; refusing to write a config" >&2; exit 1; }

grep -Eq '^#? ?access\.shared_token = ' "$TEMPLATE" || { echo "template has no access.shared_token line" >&2; exit 1; }

tmp=$(mktemp "$DIR/.config.XXXXXX")
trap 'rm -f "$tmp"' EXIT
awk -v list="$list" '/^#? ?access\.shared_token = /{ print "access.shared_token = [" list "]"; next } { print }' "$TEMPLATE" > "$tmp"
chown root:iroh-relay "$tmp"
chmod 0640 "$tmp"
mv "$tmp" "$OUT"
trap - EXIT
echo "wrote $OUT with $count token(s)"
