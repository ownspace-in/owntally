#!/usr/bin/env bash
# Installs or updates the WebSocket relay. Run on the VM:  sudo bash ~/owntally-deploy/remote/install-ws-relay.sh
#
# Keeps the old program as /usr/local/bin/ws-relay.prev, installs the new program and the systemd unit,
# restarts the service (phones reconnect by themselves within seconds), and checks /healthz on 127.0.0.1:8080.
# Does not touch nginx, certificates or the firewall.
# Rollback:  sudo install -m 0755 /usr/local/bin/ws-relay.prev /usr/local/bin/ws-relay && sudo systemctl restart ws-relay
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
require_file "$BUNDLE/ws-relay"
require_file "$BUNDLE/ws-relay.service"

if [ -f /usr/local/bin/ws-relay ]; then
    cp -a /usr/local/bin/ws-relay /usr/local/bin/ws-relay.prev
    log "kept the old program as /usr/local/bin/ws-relay.prev"
fi
install -m 0755 "$BUNDLE/ws-relay" /usr/local/bin/ws-relay
backup /etc/systemd/system/ws-relay.service
install -m 0644 "$BUNDLE/ws-relay.service" /etc/systemd/system/ws-relay.service
systemctl daemon-reload
systemctl enable ws-relay >/dev/null 2>&1
systemctl restart ws-relay

if ! wait_for 15 curl -fsS --max-time 3 http://127.0.0.1:8080/healthz; then
    journalctl -u ws-relay -n 30 --no-pager || true
    die "the relay did not answer /healthz. Roll back with the command at the top of this script."
fi
[ "$(curl -fsS --max-time 3 http://127.0.0.1:8080/healthz)" = ok ] || die "/healthz did not say ok"
log "ws-relay is running and healthy"
journalctl -u ws-relay -n 3 --no-pager || true
