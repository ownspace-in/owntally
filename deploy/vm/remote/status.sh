#!/usr/bin/env bash
# Read-only report of the VM's state. Changes nothing. Self-contained, so it can also be piped:  ssh user@vm 'bash -s' < status.sh
set -u
echo "== services"
for u in ws-relay iroh-relay nginx; do printf '  %-12s %s\n' "$u" "$(systemctl is-active "$u" 2>/dev/null || true)"; done
echo "== listeners (expected: 80 and 443 public; 8080, 3340, 8443 on 127.0.0.1; UDP 7842)"
ss -ltn 2>/dev/null | grep -E ':(80|443|8080|3340|8443)[[:space:]]' | sed 's/^/  /'
ss -lun 2>/dev/null | grep ':7842' | sed 's/^/  /'
echo "== relay health on loopback"
printf '  ws-relay /healthz        %s\n' "$(curl -s --max-time 3 http://127.0.0.1:8080/healthz || echo unreachable)"
printf '  iroh-relay /generate_204 %s\n' "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:3340/generate_204 || echo unreachable)"
echo "== nginx config"
nginx -t 2>&1 | sed 's/^/  /'
echo "== certificates"
for n in relay.ownspace.in joinowntally.ownspace.in; do
    f="/etc/letsencrypt/live/$n/fullchain.pem"
    if [ -r "$f" ]; then
        end="$(openssl x509 -in "$f" -noout -enddate | cut -d= -f2)"
        days=$(( ($(date -d "$end" +%s) - $(date +%s)) / 86400 ))
        note=""; [ "$days" -ge 21 ] || note="  <-- renew soon: sudo certbot renew"
        printf '  %-28s expires %s (%s days)%s\n' "$n" "$end" "$days" "$note"
    else
        printf '  %-28s not readable (run with sudo, or no certificate yet)\n' "$n"
    fi
done
echo "== recent relay log lines (counts and errors only)"
journalctl -u ws-relay -n 2 --no-pager 2>/dev/null | sed 's/^/  /'
echo "== machine"
df -h / | tail -n 1 | sed 's/^/  disk: /'
free -m | sed -n 2p | sed 's/^/  memory (MB): /'
command -v ufw >/dev/null && ufw status 2>/dev/null | head -n 1 | sed 's/^/  /'
exit 0
