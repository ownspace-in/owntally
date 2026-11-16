#!/usr/bin/env bash
# Tests for the deploy scripts that need no VM and no network:  deploy/vm/test/run-tests.sh
# Covers: syntax, shellcheck (if installed), the helper functions of the VM scripts, and the plan deploy.sh prints in a dry run.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fails=0
ok() { echo "ok    $1"; }
bad() { echo "FAIL  $1"; fails=$((fails + 1)); }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

for f in "$HERE"/*.sh "$HERE"/remote/*.sh "$HERE"/test/*.sh; do
    if bash -n "$f" 2>"$tmp/err"; then ok "syntax ${f#"$HERE"/}"; else bad "syntax ${f#"$HERE"/}: $(cat "$tmp/err")"; fi
done
if command -v shellcheck >/dev/null; then
    if shellcheck -x -S warning "$HERE"/*.sh "$HERE"/remote/*.sh >"$tmp/sc" 2>&1; then ok "shellcheck"; else bad "shellcheck"; cat "$tmp/sc"; fi
else
    echo "skip  shellcheck (not installed)"
fi

# remote/common.sh: backup keeps a copy and sets LAST_BACKUP; wait_for retries and gives up.
(
    export BACKUP_DIR="$tmp/backups"
    # shellcheck disable=SC1091
    . "$HERE/remote/common.sh"
    echo hello > "$tmp/file"
    backup "$tmp/file" >/dev/null
    [ -f "$LAST_BACKUP" ] && [ "$(cat "$LAST_BACKUP")" = hello ] || exit 1
    backup "$tmp/missing" >/dev/null; [ -z "$LAST_BACKUP" ] || exit 2
    wait_for 2 true || exit 3
    ! wait_for 1 false || exit 4
) && ok "remote/common.sh helpers" || bad "remote/common.sh helpers (exit $?)"

# deploy.sh in a dry run with a fake bundle: prints the plan, runs nothing on a VM.
mkdir -p "$tmp/bundle/remote"; echo "commit test" > "$tmp/bundle/VERSION"
export DRY_RUN=1 VM_HOST=vm.example VM_USER=tester VM_ARCH=amd64 BUNDLE_DIR="$tmp/bundle" SKIP_BUILD=1
out="$("$HERE/deploy.sh" --only all --yes --no-check 2>&1)"
for want in "install-ws-relay.sh" "install-iroh-relay.sh" "install-nginx.sh" "install-site.sh"; do
    echo "$out" | grep -q "\[dry-run\] ssh tester@vm.example .*$want" && ok "dry run plans $want" || bad "dry run plans $want"
done
echo "$out" | grep -q "\[dry-run\] copy" && ok "dry run plans the copy" || bad "dry run plans the copy"
unset DRY_RUN

# Without --yes and without an answer, deploy.sh must stop before changing anything.
if out="$(SKIP_BUILD=1 "$HERE/deploy.sh" --only ws-relay </dev/null 2>&1)"; then bad "deploy.sh asked for no confirmation"; else
    echo "$out" | grep -q "nothing was changed" && ok "deploy.sh stops without confirmation" || bad "deploy.sh stopped for another reason: $out"
fi
# first-time needs the email.
if out="$(env -u CERTBOT_EMAIL SKIP_BUILD=1 "$HERE/deploy.sh" --only first-time --yes </dev/null 2>&1)"; then bad "first-time ran without CERTBOT_EMAIL"; else
    echo "$out" | grep -q CERTBOT_EMAIL && ok "first-time asks for CERTBOT_EMAIL" || bad "first-time failed for another reason"
fi
# The access-line guard: the config in the repo must not have an active access line.
if grep -q '^access' "$HERE/../../relay/iroh-relay/config.toml"; then bad "repo config.toml has an active access line"; else ok "repo config.toml has no active access line"; fi

# status.sh warns about the unit flags it names: those must still be in the repo's unit, or the warning would be wrong.
for flag in '-trust-proxy' '-connects-per-min 300' '-max-per-room 40'; do
    grep -q -- "$flag" "$HERE/../../relay/ws-relay/deploy/ws-relay.service" && grep -q -- "$flag" "$HERE/remote/status.sh" \
        && ok "status.sh and the repo's unit agree on '$flag'" || bad "status.sh and the repo's unit disagree on '$flag'"
done

[ "$fails" -eq 0 ] && echo "all passed" || { echo "$fails failed"; exit 1; }
