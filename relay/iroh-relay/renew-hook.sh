#!/bin/sh
# certbot deploy hook. Install at /etc/letsencrypt/renewal-hooks/deploy/iroh-relay.sh (mode 0755, owned by root).
# Copies the renewed certificate for the relay to where iroh-relay can read it, then reloads both programs.
set -e
# certbot sets RENEWED_LINEAGE to the live directory of the certificate that was renewed.
case "$RENEWED_LINEAGE" in
  */relay.ownspace.in) ;;
  *) exit 0 ;;
esac
install -o iroh-relay -g iroh-relay -m 0600 "$RENEWED_LINEAGE/privkey.pem" /etc/iroh-relay/certs/default.key
install -o iroh-relay -g iroh-relay -m 0644 "$RENEWED_LINEAGE/fullchain.pem" /etc/iroh-relay/certs/default.crt
systemctl reload nginx
systemctl restart iroh-relay
