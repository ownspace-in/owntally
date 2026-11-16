#!/usr/bin/env bash
# Installs or updates the Iroh relay. Run on the VM:  sudo bash ~/owntally-deploy/remote/install-iroh-relay.sh
#
# Needs the certificate for relay.ownspace.in to exist already (certbot; see first-time-certs.sh).
# Creates the system user, copies the certificate where the program can read it, installs the program,
# config, unit and the certbot renewal hook, restarts, and checks the listeners (runbook relay/iroh-relay/RUNBOOK.md steps 1 to 5, 8).
# Does not touch nginx (install-nginx.sh does) and does not open the UDP port (see the end of the output).
# Rollback: sudo install -m 0755 /usr/local/bin/iroh-relay.prev /usr/local/bin/iroh-relay; restore the config from /root/owntally-backups; sudo systemctl restart iroh-relay
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
for f in iroh-relay iroh-relay.service config.toml renew-hook.sh; do require_file "$BUNDLE/$f"; done

LE=/etc/letsencrypt/live/relay.ownspace.in
[ -f "$LE/fullchain.pem" ] && [ -f "$LE/privkey.pem" ] || die "no certificate in $LE yet: run first-time-certs.sh (or certbot) first"
# Decision of 3 Nov 2026 (runbook section 9b): open access with limits, no token. An active access line would lock phones out.
if grep -q '^access' "$BUNDLE/config.toml"; then die "config.toml has an active 'access' line; refusing (runbook section 9b)"; fi
"$BUNDLE/iroh-relay" --version >/dev/null || die "the iroh-relay program does not run on this VM (wrong CPU type?)"

if [ -f /usr/local/bin/iroh-relay ]; then cp -a /usr/local/bin/iroh-relay /usr/local/bin/iroh-relay.prev; fi
install -m 0755 "$BUNDLE/iroh-relay" /usr/local/bin/iroh-relay

id -u iroh-relay >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin iroh-relay
install -d -m 0750 -o root -g iroh-relay /etc/iroh-relay /etc/iroh-relay/certs
backup /etc/iroh-relay/config.toml
install -m 0640 -o root -g iroh-relay "$BUNDLE/config.toml" /etc/iroh-relay/config.toml
install -o iroh-relay -g iroh-relay -m 0600 "$LE/privkey.pem" /etc/iroh-relay/certs/default.key
install -o iroh-relay -g iroh-relay -m 0644 "$LE/fullchain.pem" /etc/iroh-relay/certs/default.crt

install -d /etc/letsencrypt/renewal-hooks/deploy
install -m 0755 "$BUNDLE/renew-hook.sh" /etc/letsencrypt/renewal-hooks/deploy/iroh-relay.sh
backup /etc/systemd/system/iroh-relay.service
install -m 0644 "$BUNDLE/iroh-relay.service" /etc/systemd/system/iroh-relay.service
systemctl daemon-reload
systemctl enable iroh-relay >/dev/null 2>&1
systemctl restart iroh-relay

ok=1
wait_for 15 systemctl is-active --quiet iroh-relay || ok=0
sleep 2
systemctl is-active --quiet iroh-relay || ok=0
if [ "$ok" = 1 ]; then
    wait_for 10 code_is https://127.0.0.1:8443/healthz 200 || { warn "https://127.0.0.1:8443/healthz did not give 200"; ok=0; }
    [ "$(http_code http://127.0.0.1:3340/generate_204)" = 204 ] || { warn "http://127.0.0.1:3340/generate_204 did not give 204"; ok=0; }
    ss -ltn | grep -q '127.0.0.1:3340' || { warn "nothing listens on TCP 127.0.0.1:3340"; ok=0; }
    ss -ltn | grep -q '127.0.0.1:8443' || { warn "nothing listens on TCP 127.0.0.1:8443"; ok=0; }
    ss -lun | grep -q ':7842' || { warn "nothing listens on UDP 7842 (QUIC address discovery did not start)"; ok=0; }
fi
if [ "$ok" != 1 ]; then
    journalctl -u iroh-relay -n 30 --no-pager || true
    die "iroh-relay is not healthy. Read the log above; roll back with the command at the top of this script."
fi
log "iroh-relay is running and healthy on loopback"
log "REMINDER: UDP 7842 must be open to the internet in the cloud security list (and 'sudo ufw allow 7842/udp' if ufw is on). This script cannot do the cloud side."
