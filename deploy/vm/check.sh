#!/usr/bin/env bash
# Checks the deployed services from OUTSIDE, as a phone or browser would see them. Read-only.
#   deploy/vm/check.sh            (SMOKE=1 also runs the WebSocket relay's end-to-end smoke test; needs Go)
# Exit status 0 only if every required check passed. The assetlinks.json check only warns until the file exists.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_config
require_cmd curl; require_cmd openssl

fails=0
pass() { log "ok    $1"; }
fail() { log "FAIL  $1"; fails=$((fails + 1)); }
code() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@" || true; }

expect_code() { # label url want
    local got; got="$(code "$2")"
    if [ "$got" = "$3" ]; then pass "$1 ($got)"; else fail "$1: wanted $3, got ${got:-nothing}"; fi
}
expect_header() { # label url header-regex
    if curl -sI --max-time 10 "$2" | grep -Eqi "^$3"; then pass "$1"; else fail "$1: header '$3' missing"; fi
}
cert_days() { # host -> days left
    local end; end="$(echo | openssl s_client -connect "$1:443" -servername "$1" 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)"
    [ -n "$end" ] || { echo -1; return; }
    echo $(( ($(date -d "$end" +%s) - $(date +%s)) / 86400 ))
}

log "WebSocket relay"
body="$(curl -s --max-time 10 "https://$RELAY_HOST/healthz" || true)"
if [ "$body" = ok ]; then pass "https://$RELAY_HOST/healthz says ok"; else fail "https://$RELAY_HOST/healthz said '${body:-nothing}'"; fi

log "Iroh relay (a 429 means this machine is being rate-limited: wait a few seconds and run again)"
expect_code "/ping" "https://$RELAY_HOST/ping" 200
expect_code "/generate_204" "https://$RELAY_HOST/generate_204" 204

log "Invite site"
expect_code "/j" "https://$SITE_HOST/j" 200
expect_code "/privacy" "https://$SITE_HOST/privacy" 200
expect_code "/style.css" "https://$SITE_HOST/style.css" 200
expect_code "http redirects to https" "http://$SITE_HOST/j" 301
expect_header "security header X-Frame-Options" "https://$SITE_HOST/j" "x-frame-options: deny"
expect_header "security header Content-Security-Policy" "https://$SITE_HOST/j" "content-security-policy:"
al="$(code "https://$SITE_HOST/.well-known/assetlinks.json")"
if [ "$al" = 200 ]; then
    expect_header "assetlinks.json is JSON" "https://$SITE_HOST/.well-known/assetlinks.json" "content-type: application/json"
else
    warn "assetlinks.json answered ${al:-nothing}: invite links open in the browser, not the app (site/README.md)"
fi

log "Certificates"
for h in "$RELAY_HOST" "$SITE_HOST"; do
    d="$(cert_days "$h")"
    if [ "$d" -ge 21 ]; then pass "$h certificate valid for $d more days"
    elif [ "$d" -ge 0 ]; then fail "$h certificate expires in $d days (renewal should have happened: ssh in, sudo certbot renew)"
    else fail "$h certificate could not be read"; fi
done

if [ "${SMOKE:-0}" = 1 ]; then
    log "WebSocket relay smoke test"
    require_cmd go
    if (cd "$REPO_ROOT/relay/ws-relay" && go run -buildvcs=false ./tools/smoke -url "wss://$RELAY_HOST/ws"); then pass "smoke test"; else fail "smoke test"; fi
fi

if [ "$fails" -eq 0 ]; then log "all checks passed"; else log "$fails check(s) failed"; exit 1; fi
