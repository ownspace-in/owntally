# Runbook: Iroh relay on relay.ownspace.in (next to the WebSocket relay)

Goal: phones that use Iroh can reach `https://relay.ownspace.in` as their relay, and learn their public address from it over QUIC. It runs on the same VM as the WebSocket relay and the other sites. nginx keeps TCP 80 and 443.

**Status: written, not yet run on a real VM, and the relay program has not been run by us at all.** Several details come from the `iroh-relay` v1.4.1 source and docs, not from experience. Every step below has a check. If a check does not give the expected answer, stop, undo that step (section "Undo") and report the exact output. Fix this runbook in the same change.

Verified on the VM on 29 Oct 2026 (steps 1 to 5): the program installs and starts under systemd as its own user; with `cert_mode = "Manual"` it loads the certificate and listens on TCP `127.0.0.1:3340` and `127.0.0.1:8443` and UDP `0.0.0.0:7842` (QUIC address discovery); `/healthz` and `/ping` answer 200 on the HTTPS listener; `/relay` exists on the HTTPS listener and demands a `sec-websocket-protocol` header (a bare test gets 400, which is correct, and with `Sec-WebSocket-Protocol: iroh-relay-v1` it answers `101 Switching Protocols`; `curl` then waits, so use `--max-time 3`); `/generate_204` is served only on the plain HTTP listener (3340) and answers 204.

Also verified through nginx from outside on 29 Oct 2026 (step 6): `https://relay.ownspace.in/generate_204` gives 204, `/ping` 200, `/healthz` ok, and `/relay` with `Sec-WebSocket-Protocol: iroh-relay-v1` gives `101 Switching Protocols`. The WebSocket relay smoke test still passes afterwards (connect 204 ms, round trip 17 ms).

Also verified on 29 Oct 2026: UDP 7842 packets from outside reach the VM (tcpdump), and `ufw` already allows `7842/udp`; the renewal hook runs by hand without errors and the relay restarts with the copied certificate; `certbot renew --dry-run` succeeds for all four certificates (an earlier run failed with `Timeout during connect` on every certificate, then passed on retry, with Let's Encrypt's validation servers seen reaching port 80, so the cause was temporary and not the relay setup).

Verified with phones on 2 Nov 2026 (step 9, before the token existed): a v1.4.1 relay works with phones built on the Iroh Kotlin package 1.1.0. Redmi (Android 15) and Galaxy S24 (Android 17), both on mobile data, both report `home relay https://relay.ownspace.in/`; the link started RELAYED and ended DIRECT; 30 of 30 messages, 0 corrupt, average round trip 222 ms. Other rooms and swapped roles also worked (owner-reported).

Not verified yet:
- That the direct link came from our relay's QUIC address discovery. The phones' logs do not say how the direct path was found. To check: look at `sudo journalctl -u iroh-relay` and `sudo tcpdump -ni any udp port 7842` on the VM during a run, or test with two phones on different carriers.

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

## 9b. Production update: per-IP limits and rate cap, no token (3 Nov 2026, not yet run)

Decision: ship with limits only (open access, the relay's own per-client cap, and nginx per-IP limits). The token in step 10 stays optional and off. Run this on the VM after steps 1 to 9 are done. Copy the files first, from the development machine:

```
scp relay/iroh-relay/config.toml relay/iroh-relay/nginx-limits.conf relay/iroh-relay/nginx-locations.conf <user>@<vm>:~
```

1. **Back up what you change.**
   ```
   sudo cp -a /etc/iroh-relay/config.toml ~/config.toml.bak
   sudo cp -a /etc/nginx/conf.d/relay.ownspace.in.conf ~/relay.ownspace.in.conf.bak
   ```
2. **Relay config.** The repo file has no active `access` line, so access stays open.
   ```
   grep -n '^access' ~/config.toml || echo "no active access line: good"
   sudo install -m 0644 ~/config.toml /etc/iroh-relay/config.toml
   sudo systemctl restart iroh-relay
   systemctl status iroh-relay --no-pager; sudo journalctl -u iroh-relay -n 20 --no-pager
   ```
   Check: `active (running)`, no config error (a complaint about `limits` means a field name is wrong: restore the backup and report the log). Do not continue if `grep` printed an `access` line.
3. **nginx zones, as their own file.**
   ```
   sudo install -m 0644 ~/nginx-limits.conf /etc/nginx/conf.d/00-iroh-relay-limits.conf
   ```
4. **nginx locations.** Open `/etc/nginx/conf.d/relay.ownspace.in.conf` and add these lines inside the existing blocks (compare with `nginx-locations.conf`):
   - In `location = /relay {`: `limit_req zone=iroh_req burst=20 nodelay;`, `limit_req_status 429;`, `limit_conn iroh_conn 20;`, `limit_conn_status 429;`
   - In `location = /ping {` and `location = /generate_204 {`: `limit_req zone=iroh_req burst=20 nodelay;` and `limit_req_status 429;`
   Then `sudo nginx -t`, and only if it passes: `sudo systemctl reload nginx`. If it fails, `sudo cp -a ~/relay.ownspace.in.conf.bak /etc/nginx/conf.d/relay.ownspace.in.conf` and remove the new zones file.
5. **Check (from your laptop).**
   ```
   curl -s -o /dev/null -w '%{http_code}\n' https://relay.ownspace.in/generate_204        # 204
   curl -s https://relay.ownspace.in/healthz                                                 # ok
   seq 1 80 | xargs -P 40 -I{} curl -s -o /dev/null -w '%{http_code}\n' https://relay.ownspace.in/generate_204 | sort | uniq -c
   sleep 5; curl -s -o /dev/null -w '%{http_code}\n' https://relay.ownspace.in/generate_204 # 204 again
   (cd relay/ws-relay && go run -buildvcs=false ./tools/smoke)
   ```
   Expected: the burst shows mostly `204` and some `429` (the limit works); after a pause it is `204` again; the smoke test passes. If the burst shows no `429`, the limit is not in effect: check the `limit_req` lines and the zones file. Run the burst once only, from your own address.
6. **Phone test.** One run of the Iroh test (step 9, no token, build without `local-secrets.properties`). It must still pass 30 of 30. Afterwards on the VM: `sudo grep -c 'limiting' /var/log/nginx/error.log` should not have grown from real phones; if it has, raise the numbers before announcing.

**Undo:** restore both `.bak` files, `sudo rm /etc/nginx/conf.d/00-iroh-relay-limits.conf`, `sudo nginx -t && sudo systemctl reload nginx`, `sudo systemctl restart iroh-relay`.

## 10. Access token and rate limit (decided 2 Nov 2026, not yet run)

Decision: a shared token plus per-client rate limits. The relay supports only one access mode at a time (`everyone`, `allowlist`, `denylist`, `http` or `shared_token`), so a denylist is not used alongside the token. Abuse is handled by rotating the token (below).

Know the limits: the token ships inside the APK, so anyone can extract it. It stops casual use, not a determined person, and a new APK carries the new token too. The rate limits cap the damage either way.

**Where tokens live.** Only in `/etc/iroh-relay/tokens` on the VM (one per line, `0600`, root) and in KeePass (one entry per token). The repo holds a template (`config.toml`, with a placeholder) and `render-config.sh`, which writes the real `/etc/iroh-relay/config.toml` (`0640`, root:iroh-relay) from the template and the token list. The script never prints a token.

**First setup**, after copying `config.toml`, `render-config.sh` and `iroh-relay.service` to the VM:

1. In KeePass, make an entry `OwnTally relay token 1` and generate a 48-character hex password.
2. On the VM, create the token file without leaving the token in shell history, and paste the token when `sudoedit` opens it:
   ```
   sudo install -m 0600 -o root -g root /dev/null /etc/iroh-relay/tokens
   sudoedit /etc/iroh-relay/tokens
   ```
3. Install and render:
   ```
   sudo install -m 0644 ~/config.toml /etc/iroh-relay/config.template.toml
   sudo install -m 0750 ~/render-config.sh /usr/local/sbin/iroh-relay-render-config
   sudo install -m 0644 ~/iroh-relay.service /etc/systemd/system/iroh-relay.service
   sudo iroh-relay-render-config
   sudo systemctl daemon-reload && sudo systemctl restart iroh-relay
   systemctl status iroh-relay --no-pager; sudo journalctl -u iroh-relay -n 20 --no-pager
   ls -l /etc/iroh-relay
   ```

Checks:
- The service stays running, `config.toml` is `-rw-r-----` root:iroh-relay and `tokens` is `-rw-------` root. The journal must not show the token.
- Step 5's header test still gives 101, because the token is checked after the upgrade, so test with phones.
- A phone **without** the token cannot connect through `https://relay.ownspace.in` (it never reaches `online`, or dials fail).
- A phone **with** the token connects as in step 9.
- The placeholder text from the template does **not** work as a token.

**Rotating a token** (a leaked token, or a retired app version):
1. KeePass: new entry `OwnTally relay token 2`. Set the old entry's expiry to the planned removal date.
2. VM: add the new token as a second line in `tokens` (`sudoedit`), then `sudo iroh-relay-render-config && sudo systemctl restart iroh-relay`. Phones reconnect by themselves. Both tokens work now.
3. Build and ship an app with the new token (`spike/local-secrets.properties`, key `irohRelayToken`).
4. Wait. There is no analytics, so you cannot see who has updated: choose a date.
5. VM: delete the old line, render, restart. Apps with the old token lose the relay but can still use Nearby or the WebSocket relay.

Never put a token in git, a command line, a URL or a chat. The app sends it as an `Authorization: Bearer` header (not in the nginx access log).

**Phone side.** The Iroh Kotlin package 1.1.0 has `RelayConfig(url, quicPort, authToken)` and `RelayMode.custom(RelayMap)`. Checked in the library itself (2 Nov 2026): the native library `libiroh_ffi.so` is built on the `iroh-relay` 1.0.2 client and contains an `auth_token` setting documented as an `Authorization: Bearer ...` header on the upgrade request, and an error for a token that is not a valid header value. The spike app reads the token from the git-ignored `spike/local-secrets.properties`. Not checked until the phone test: a working connection with a token, and the refusal without one.

**Rate limit.** `bytes_per_second = 262144` and `max_burst_bytes = 1048576` per client (what the relay reads from one connection). The values are a first guess from the 0.5 MB test. Run the phone test again and confirm 30 of 30 still pass; raise them if a large sync is slowed. `accept_conn_limit` is not implemented in v1.4.1. QUIC address discovery on UDP 7842 is not known to be covered by the token.

## 11. Not decided yet

- **Who may use the relay.** Decided: shared token, see step 10. A different relay address entered by a group admin (`docs/DESIGN.md`) has its own token, so the app needs a place for the admin to enter it.
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
