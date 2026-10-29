# Runbook: Iroh relay on relay.ownspace.in (next to the WebSocket relay)

Goal: phones that use Iroh can reach `https://relay.ownspace.in` as their relay, and learn their public address from it over QUIC. It runs on the same VM as the WebSocket relay and the other sites. nginx keeps TCP 80 and 443.

**Status: written, not yet run on a real VM, and the relay program has not been run by us at all.** Several details come from the `iroh-relay` v1.4.1 source and docs, not from experience. Every step below has a check. If a check does not give the expected answer, stop, undo that step (section "Undo") and report the exact output. Fix this runbook in the same change.

Verified on the VM on 29 Oct 2026 (steps 1 to 5): the program installs and starts under systemd as its own user; with `cert_mode = "Manual"` it loads the certificate and listens on TCP `127.0.0.1:3340` and `127.0.0.1:8443` and UDP `0.0.0.0:7842` (QUIC address discovery); `/healthz` and `/ping` answer 200 on the HTTPS listener; `/relay` exists on the HTTPS listener and demands a `sec-websocket-protocol` header (a bare test gets 400, which is correct, and with `Sec-WebSocket-Protocol: iroh-relay-v1` it answers `101 Switching Protocols`; `curl` then waits, so use `--max-time 3`); `/generate_204` is served only on the plain HTTP listener (3340) and answers 204.

Not verified yet:
- That nginx can proxy `/relay` to the relay's HTTPS listener on loopback, including the WebSocket upgrade (step 6).
- That the UDP port is reachable from the internet (step 7) and that phones then form direct links.
- That the certbot hook works (step 8).
- That a v1.4.1 relay works with phones built on the Iroh Kotlin package 1.1.0 (step 9).

## 0. What you need

- The VM from `relay/ws-relay/RUNBOOK.md`, with nginx, certbot, and a working certificate for `relay.ownspace.in` (already in place).
- `sudo` on the VM, and access to the cloud firewall (Oracle security list) to open one UDP port.

## 1. Get the program (on the VM)

Check the CPU type: `uname -m` (`x86_64` is amd64, `aarch64` is arm64). Download the matching static build from the official release and unpack it in a new, empty folder:

```
mkdir ~/iroh-relay-dl && cd ~/iroh-relay-dl
# amd64:
curl -sSL -O https://github.com/n0-computer/iroh/releases/download/v1.4.1/iroh-relay-v1.4.1-x86_64-unknown-linux-musl.tar.gz
# arm64: use iroh-relay-v1.4.1-aarch64-unknown-linux-musl.tar.gz instead
sha256sum iroh-relay-*.tar.gz
```

For the amd64 file the SHA-256 should be `75254641e3fad7fcbac6dee4d485556fbe06ef708340a9d8810469270ba626b8` (two downloads on the development machine matched; there is no published checksum to compare against). Then:

```
tar xzf iroh-relay-*.tar.gz
sudo install -m 0755 iroh-relay /usr/local/bin/iroh-relay
/usr/local/bin/iroh-relay --version
```

Check: it prints a version. Do not run anything else from that folder.

## 2. User, config and certificate copy

Copy `config.toml`, `iroh-relay.service`, `renew-hook.sh` and `nginx-locations.conf` from `relay/iroh-relay/` to the VM (`scp`), then:

```
sudo useradd --system --no-create-home --shell /usr/sbin/nologin iroh-relay
sudo mkdir -p /etc/iroh-relay/certs
sudo install -m 0644 ~/config.toml /etc/iroh-relay/config.toml
sudo install -o iroh-relay -g iroh-relay -m 0600 /etc/letsencrypt/live/relay.ownspace.in/privkey.pem /etc/iroh-relay/certs/default.key
sudo install -o iroh-relay -g iroh-relay -m 0644 /etc/letsencrypt/live/relay.ownspace.in/fullchain.pem /etc/iroh-relay/certs/default.crt
sudo chown root:iroh-relay /etc/iroh-relay /etc/iroh-relay/certs
sudo chmod 0750 /etc/iroh-relay /etc/iroh-relay/certs
ls -l /etc/iroh-relay /etc/iroh-relay/certs
```

Check: the key is `-rw-------` owned by `iroh-relay`, and only that user and root can read `/etc/iroh-relay/certs`.

## 3. Install and start the service

```
sudo install -m 0644 ~/iroh-relay.service /etc/systemd/system/iroh-relay.service
sudo systemctl daemon-reload
sudo systemctl enable --now iroh-relay
systemctl status iroh-relay --no-pager
sudo journalctl -u iroh-relay -n 30 --no-pager
```

Check: `active (running)` and no error lines. If it exits, read the log. A complaint about the config means a field name or value is wrong (the program's own error says which). A complaint about the key or certificate means step 2 permissions or paths. Do not continue until it stays running.

## 4. Check what it listens on

```
sudo ss -ltnp | grep iroh-relay
sudo ss -lunp | grep iroh-relay
```

Check: TCP on `127.0.0.1:3340` and `127.0.0.1:8443`, and UDP on `0.0.0.0:7842`. If the UDP port is missing, QUIC address discovery did not start. Read the log for the reason. The relay is not reachable from the internet yet, which is intended.

## 5. Check the web paths on loopback (before touching nginx)

```
curl -sk -o /dev/null -w '%{http_code}\n' https://127.0.0.1:8443/healthz
curl -sk -o /dev/null -w '%{http_code}\n' https://127.0.0.1:8443/ping
curl -sk -o /dev/null -w '%{http_code}\n' https://127.0.0.1:8443/generate_204
curl -sk -i -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' https://127.0.0.1:8443/relay | head -5
```

Expected (seen on the VM): `200` for `/healthz`, `200` for `/ping`, `404` for `/generate_204` on 8443 (it is served on the plain HTTP port instead: `curl -s -i http://127.0.0.1:3340/generate_204` gives `204`), and for `/relay` a `400` with the body `missing header: sec-websocket-protocol`. That body means the path is right; real clients send that header. A `404` on `/relay` would mean the path differs.

## 6. Add the paths to nginx

1. Open `/etc/nginx/conf.d/relay.ownspace.in.conf` (certbot edited it earlier).
2. Inside the `server { listen 443 ssl ...; server_name relay.ownspace.in; }` block, paste the three `location` blocks from `nginx-locations.conf`, next to `location = /ws`. Keep `/ws` and `/healthz` as they are.
3. Run `sudo nginx -t`. Reload only if it passes: `sudo systemctl reload nginx`.

Check from your laptop:

```
curl -s -o /dev/null -w '%{http_code}\n' https://relay.ownspace.in/generate_204
curl -s https://relay.ownspace.in/healthz
cd relay/ws-relay && go run -buildvcs=false ./tools/smoke
```

Expected: `204`, `ok`, and the smoke test passing. The last two prove the WebSocket relay is unaffected. If `/generate_204` gives `502`, nginx cannot reach the relay's HTTPS port (check step 4 and `sudo tail /var/log/nginx/relay-error.log`).

## 7. Open the UDP port

1. In Oracle Cloud, add an ingress rule to the VM's security list: protocol UDP, destination port 7842, source `0.0.0.0/0`.
2. On the VM, if `ufw` is active: `sudo ufw allow 7842/udp`. If it isn't active, skip this (do not turn it on just for this, see the WebSocket runbook step 5).

Check from your laptop (it cannot prove QUIC works, but shows the port is reachable): `nc -zvu relay.ownspace.in 7842`. A UDP "success" only means nothing refused it. The real check is step 9.

## 8. Renewal hook

```
sudo install -m 0755 ~/renew-hook.sh /etc/letsencrypt/renewal-hooks/deploy/iroh-relay.sh
sudo certbot renew --dry-run
```

Check: the dry run succeeds. A dry run does not call deploy hooks on every setup, so also run the script once by hand to prove it works:

```
sudo RENEWED_LINEAGE=/etc/letsencrypt/live/relay.ownspace.in sh /etc/letsencrypt/renewal-hooks/deploy/iroh-relay.sh
systemctl status iroh-relay --no-pager
```

Expected: no error, and the service is running again. This also reloads nginx. It replaces the older `reload-nginx.sh` hook for this certificate's purposes, and you can keep both.

## 9. Test with the phones

Use the debug APK from the spike (`spike/app/build/outputs/apk/debug/app-debug.apk`, build stamp shown under the title). On both phones: pick Iroh, and in the "Relay address" box enter `https://relay.ownspace.in` (it must start with `http`, otherwise the app uses Iroh's public relays). Start B, wait for `iroh: ready as …`, then Start A and Run test.

Check:
- The log says `online, home relay https://relay.ownspace.in/` (not `n0.iroh.link`). This proves the phones are using our relay.
- `acked=30 corrupt=0` on A.
- Watch whether the link becomes `DIRECT` after a few seconds. On the VM: `sudo journalctl -u iroh-relay -n 50 --no-pager`.

Direct links forming over mobile networks is the sign that address discovery works. If everything works but the link stays `RELAYED` across different mobile networks, address discovery may be failing: check the UDP port (steps 4 and 7) and the certificate dates:

```
echo | openssl s_client -connect relay.ownspace.in:443 -servername relay.ownspace.in 2>/dev/null | openssl x509 -noout -dates
sudo openssl x509 -in /etc/iroh-relay/certs/default.crt -noout -dates
```

## 10. Not decided yet

- **Who may use the relay.** The config leaves access open to everyone, so anyone could use our bandwidth. The program supports a shared token, an allowlist and a webhook (`access` in the config). Pick one before announcing the relay. Do not put a token in the repo.
- **Rate limits** (`limits` in the config). Not set.
- **Version mismatch.** The relay is v1.4.1 and the phone library is 1.1.0.
- **Second relay for failover.** `docs/DESIGN.md` suggests one on a different server later.

## Undo (reverses everything above except the downloaded files)

```
sudo systemctl disable --now iroh-relay
sudo rm /etc/systemd/system/iroh-relay.service /etc/letsencrypt/renewal-hooks/deploy/iroh-relay.sh
sudo systemctl daemon-reload
```

Then remove the three `location` blocks you added to `relay.ownspace.in.conf`, run `sudo nginx -t && sudo systemctl reload nginx`, close UDP 7842 in the security list (and `sudo ufw delete allow 7842/udp` if you added it), and optionally `sudo rm -r /etc/iroh-relay /usr/local/bin/iroh-relay` and `sudo userdel iroh-relay`. The WebSocket relay and your other sites are not touched by any of this.

## Everyday operations

| Task | Command |
|---|---|
| Logs | `sudo journalctl -u iroh-relay -f` |
| Restart | `sudo systemctl restart iroh-relay` |
| Update the program | repeat step 1 with the new version, then `sudo systemctl restart iroh-relay` |
| Change config | edit `/etc/iroh-relay/config.toml`, then restart |
