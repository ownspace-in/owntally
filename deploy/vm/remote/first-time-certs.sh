#!/usr/bin/env bash
# FRESH VM ONLY. Installs nginx and certbot, gets certificates for both names, and puts the final nginx configs in place.
#   sudo env CERTBOT_EMAIL=you@example.org bash ~/owntally-deploy/remote/first-time-certs.sh
# (deploy.sh --only first-time does this for you.) Mirrors relay/ws-relay/RUNBOOK.md steps 4 and 4a to 4c.
# Needs: DNS for both names already pointing at this VM, and ports 80 and 443 open. It does not touch the firewall.
# It refuses to run if the relay's nginx config already exists, so it cannot overwrite a working setup.
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
: "${CERTBOT_EMAIL:?set CERTBOT_EMAIL=you@example.org (certificate expiry notices go there)}"
for f in nginx/bootstrap-http.conf nginx/relay.ownspace.in.conf nginx/joinowntally.ownspace.in.conf; do require_file "$BUNDLE/$f"; done
[ ! -e /etc/nginx/conf.d/relay.ownspace.in.conf ] || die "/etc/nginx/conf.d/relay.ownspace.in.conf exists: this VM is already set up. Use install-nginx.sh instead."

if ! command -v nginx >/dev/null || ! command -v certbot >/dev/null; then
    log "installing nginx and certbot"
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nginx certbot
fi
mkdir -p /var/www/certbot /var/www/joinowntally

install -m 0644 "$BUNDLE/nginx/bootstrap-http.conf" /etc/nginx/conf.d/owntally-bootstrap.conf
nginx -t || die "nginx rejected the temporary config"
systemctl reload nginx

for name in relay.ownspace.in joinowntally.ownspace.in; do
    if [ -f "/etc/letsencrypt/live/$name/fullchain.pem" ]; then
        log "certificate for $name already exists"
    else
        certbot certonly --webroot -w /var/www/certbot -d "$name" --agree-tos -m "$CERTBOT_EMAIL" --non-interactive \
            || die "certbot failed for $name. Check: DNS points here (dig +short $name), port 80 is open in the cloud firewall."
    fi
done

rm -f /etc/nginx/conf.d/owntally-bootstrap.conf
install -m 0644 "$BUNDLE/nginx/relay.ownspace.in.conf" /etc/nginx/conf.d/relay.ownspace.in.conf
install -m 0644 "$BUNDLE/nginx/joinowntally.ownspace.in.conf" /etc/nginx/conf.d/joinowntally.ownspace.in.conf
if ! nginx -t; then
    # If another site on this VM already defines the "connection_upgrade" map, ours is a duplicate.
    die "nginx rejected the final config. If it says 'duplicate \"connection_upgrade\" variable', delete the map block from /etc/nginx/conf.d/relay.ownspace.in.conf and run: sudo nginx -t && sudo systemctl reload nginx"
fi
systemctl reload nginx

install -d /etc/letsencrypt/renewal-hooks/deploy
printf '#!/bin/sh\nsystemctl reload nginx\n' > /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
chmod 0755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
certbot renew --dry-run || warn "the renewal dry run failed; it sometimes fails once on a timeout, run 'sudo certbot renew --dry-run' again"
log "certificates and nginx are in place. Next: the relay, the site, then the firewall (docs/DEPLOY_VM.md section 4)."
