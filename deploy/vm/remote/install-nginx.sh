#!/usr/bin/env bash
# Adds the Iroh relay's paths and per-address limits to nginx. Run on the VM:  sudo bash ~/owntally-deploy/remote/install-nginx.sh
#
# 1. installs the limit zones as /etc/nginx/conf.d/00-iroh-relay-limits.conf,
# 2. inserts the /relay, /ping and /generate_204 blocks (with their limits) into /etc/nginx/conf.d/relay.ownspace.in.conf
#    just before the "location = /ws" block, unless they are already there,
# 3. runs nginx -t and reloads only if it passes; if not, puts the old files back.
# It never touches other sites' configs, and it does not touch the invite site's config.
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_file "$BUNDLE/nginx-locations.conf"
require_file "$BUNDLE/nginx-limits.conf"

CONF=/etc/nginx/conf.d/relay.ownspace.in.conf
ZONES=/etc/nginx/conf.d/00-iroh-relay-limits.conf
[ -f "$CONF" ] || die "no $CONF: on a fresh VM run first-time-certs.sh first"
nginx -t >/dev/null 2>&1 || die "nginx -t already fails before any change; fix that first (run: sudo nginx -t)"

backup "$CONF"; conf_backup="$LAST_BACKUP"
backup "$ZONES"; zones_backup="$LAST_BACKUP"
install -m 0644 "$BUNDLE/nginx-limits.conf" "$ZONES"

if grep -q 'location = /generate_204' "$CONF"; then
    log "the Iroh locations are already in $CONF; not inserting them again."
    log "If the limits in relay/iroh-relay/nginx-locations.conf changed, compare by hand (runbook section 9b step 4)."
else
    grep -q '^    location = /ws {' "$CONF" || die "no '    location = /ws {' line in $CONF: add the blocks by hand (relay/iroh-relay/RUNBOOK.md step 6)"
    tmp="$(mktemp)"
    awk -v blocks="$BUNDLE/nginx-locations.conf" '
        BEGIN { while ((getline l < blocks) > 0) if (l !~ /^#/) ins = ins l "\n" }
        /^    location = \/ws \{/ && !done { printf "%s", ins; done = 1 }
        { print }' "$CONF" > "$tmp"
    install -m 0644 "$tmp" "$CONF"; rm -f "$tmp"
    log "inserted the Iroh locations into $CONF"
fi

if ! nginx -t; then
    warn "nginx -t failed: putting the old files back"
    [ -z "$conf_backup" ] || cp -a "$conf_backup" "$CONF"
    if [ -n "$zones_backup" ]; then cp -a "$zones_backup" "$ZONES"; else rm -f "$ZONES"; fi
    nginx -t || true
    die "nginx config rejected; nothing was reloaded"
fi
systemctl reload nginx
log "nginx reloaded"
