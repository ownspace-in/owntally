# Runbook: deploy the WebSocket relay on a fresh VM

Goal: `wss://relay.ownspace.in/ws` accepts connections, restarts if it crashes, and starts at boot. The same nginx also serves the static invite page at `joinowntally.ownspace.in` (see `site/README.md`). Written so someone who has not seen the project can follow it. If a step fails, fix the runbook as part of the same change.

**What has been run, and when.** On the real VM on 20 Oct 2026: the program was installed under systemd, nginx and certbot were set up **by the shortcut at the end of step 4** (not by steps 4a to 4c), and the smoke test passed (step 6). The security headers of the invite site were added by hand on 3 Nov 2026 and checked from outside. Phones used the relay on mobile data on 2 Nov 2026. **Not run on a real VM:** steps 4a to 4c as written (they were checked on a development machine with a self-signed certificate: routing, the WebSocket upgrade, redirects, content types), and a run of the whole runbook from a fresh VM, which is the owner's check of M9. The program, its tests, the smoke tool and the unit file's syntax are verified on a development machine; the limits of step 7 were measured against the real program in M7 (T7.9).

## 0. What you need

- A Linux VM (Ubuntu 22.04 or 24.04 assumed) with a public IPv4 address, 1 GB RAM is plenty, and `sudo` access.
- Control of the DNS for `ownspace.in`.
- Ports 80 and 443 open in the cloud provider's firewall (security group) as well as on the VM. Port 80 is needed to get the HTTPS certificate.
- A laptop with Go installed, to build the program and to run the smoke test (and `git` and `ssh`/`scp`).
- About an hour, and an email address for the certificate's expiry notices.

## 1. DNS

Create `A` records for `relay.ownspace.in` and `joinowntally.ownspace.in` pointing to the VM's IPv4 address (and `AAAA` records only if the VM has working IPv6). If the DNS is on Cloudflare, use "DNS only", not proxied. Check from your laptop:

```
dig +short relay.ownspace.in
dig +short joinowntally.ownspace.in
```

Both must print the VM's address before you continue. Certbot cannot get certificates until DNS is right.

## 2. Build the program (on your laptop)

Find the VM's CPU type with `uname -m` on the VM: `x86_64` means `amd64`, `aarch64` means `arm64`.

```
cd relay/ws-relay
GOOS=linux GOARCH=amd64 go build -buildvcs=false -ldflags="-s -w" -o ws-relay .
scp ws-relay deploy/ws-relay.service deploy/nginx/*.conf ../../site/nginx/*.conf <user>@<vm>:~
```

Use `GOARCH=arm64` for an ARM VM.

## 3. Install the relay (on the VM)

```
sudo install -m 0755 ~/ws-relay /usr/local/bin/ws-relay
sudo install -m 0644 ~/ws-relay.service /etc/systemd/system/ws-relay.service
sudo systemctl daemon-reload
sudo systemctl enable --now ws-relay
```

Check it is running and answering locally:

```
systemctl status ws-relay --no-pager
curl -s http://127.0.0.1:8080/healthz      # prints: ok
```

The relay listens only on `127.0.0.1:8080`. It is not reachable from the internet yet, which is intended.

## 4. nginx and HTTPS certificates (on the VM)

On a **fresh VM** install nginx and certbot first (`sudo apt install nginx certbot`; the VM we use already had nginx for other sites). Our files go in `/etc/nginx/conf.d/`, which the default Ubuntu `nginx.conf` includes, and other sites on the VM are not touched, because ours match only their own server names. Run `sudo nginx -t` before every reload, and stop if it reports an error.

```
sudo apt install nginx certbot
sudo mkdir -p /var/www/certbot /var/www/joinowntally
```

**4a. Temporary HTTP-only config, so certbot can prove we own the names:**

```
sudo install -m 0644 ~/bootstrap-http.conf /etc/nginx/conf.d/owntally-bootstrap.conf
sudo nginx -t && sudo systemctl reload nginx
sudo certbot certonly --webroot -w /var/www/certbot -d relay.ownspace.in
sudo certbot certonly --webroot -w /var/www/certbot -d joinowntally.ownspace.in
```

Certbot asks for an email address for expiry notices. If it fails, check DNS (step 1) and that port 80 is open to the internet.

**4b. Final configs (replace the temporary one):**

```
sudo rm /etc/nginx/conf.d/owntally-bootstrap.conf
sudo install -m 0644 ~/relay.ownspace.in.conf /etc/nginx/conf.d/relay.ownspace.in.conf
sudo install -m 0644 ~/joinowntally.ownspace.in.conf /etc/nginx/conf.d/joinowntally.ownspace.in.conf
sudo nginx -t && sudo systemctl reload nginx
```

If `nginx -t` says `duplicate "connection_upgrade" variable`, another config on this VM already defines that `map`. Delete the `map` block from `relay.ownspace.in.conf` and test again.

**4c. Reload nginx automatically after each renewal** (certificates renew about every 60 days, by a timer certbot installs):

```
printf '#!/bin/sh\nsystemctl reload nginx\n' | sudo tee /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
sudo certbot renew --dry-run
```

Two details in `relay.ownspace.in.conf` matter. It sets `X-Forwarded-For` to the client address instead of adding to it, because the relay uses that value for rate limits and a client must not be able to fake it. And it turns access logging off, because the room and key are in the query string.

**4d. Put the invite page in place** (it can wait until the page is final): copy the contents of `site/` to `/var/www/joinowntally/`, as described in `site/README.md`.

**Shortcut when nginx and certbot already run on the VM (this is what was actually done):** write plain port 80 server blocks for the two names (the `/ws` and `/healthz` proxy blocks from `relay.ownspace.in.conf`, and the static site from `joinowntally.ownspace.in.conf`, both without the `ssl` lines), run `sudo nginx -t && sudo systemctl reload nginx`, then `sudo certbot --nginx -d relay.ownspace.in -d joinowntally.ownspace.in`. Certbot adds HTTPS, the redirect and automatic renewal, so 4c is not needed. The invite site was deployed this way too and checked with `curl -sI` (200 on `/j` and `/style.css`, 301 from http). The stricter headers in `site/nginx/joinowntally.ownspace.in.conf` (CSP, `X-Frame-Options`) were left out of that port 80 config. **Added by hand to the live config on 3 Nov 2026** (two `add_header` lines under the existing two, in the `listen 443 ssl` block, then `nginx -t` and a reload) and checked from outside: the page answers 200 with `X-Content-Type-Options`, `Referrer-Policy`, `X-Frame-Options: DENY` and the CSP; `/j/invite.js` is still `application/javascript`; port 80 still redirects with 301. The page's files have no inline styles, scripts or outside resources, so the CSP does not break them. If you add an `add_header` to any `location` there (for example `Cache-Control` on `assetlinks.json`), repeat the four security headers in that block, because nginx drops the server-level ones for a location that sets its own.

## 5. Firewall on the VM

```
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status
```

Do not open 8080. Do this step only after allowing SSH, or you will lock yourself out.

## 6. Check it from outside (the "done" test)

From your laptop:

```
curl -s https://relay.ownspace.in/healthz        # prints: ok
curl -sI https://joinowntally.ownspace.in/j      # 200 once the invite page is copied (step 4d)
cd relay/ws-relay && go run ./tools/smoke        # prints: OK  connect=...  roundtrip=...
```

`go run ./tools/smoke` connects two test clients to `wss://relay.ownspace.in/ws`, sends one message and checks that it arrives. Save the printed times in `docs/SPIKE_RESULTS.md`.

## 7. Everyday operations

| Task | Command |
|---|---|
| See relay logs | `sudo journalctl -u ws-relay -f` |
| See how many clients are connected | `sudo journalctl -u ws-relay -n 5 --no-pager` (a stats line is logged every minute) |
| Restart | `sudo systemctl restart ws-relay` |
| Update the program | build as in step 2, `scp` the file, then `sudo install -m 0755 ~/ws-relay /usr/local/bin/ws-relay && sudo systemctl restart ws-relay` |
| Change limits | edit the `ExecStart` line in `/etc/systemd/system/ws-relay.service` (flags are listed in `README.md`; the repo's file has the production values `-connects-per-min 300 -max-per-room 40`, decision R3), then `sudo systemctl daemon-reload && sudo systemctl restart ws-relay`. Connected phones reconnect by themselves within seconds. |
| Check the certificate | `echo | openssl s_client -connect relay.ownspace.in:443 -servername relay.ownspace.in 2>/dev/null | openssl x509 -noout -dates` |

The relay logs only counts and errors, never message contents or keys. Do not add logging that changes this.

## 8. Health check and alert

Set up an outside uptime monitor (any free service, from outside the VM and its network) with these checks, each every minute, alerting the owner (email or a message) after **two failures in a row**:

| Address | Healthy answer | What a failure usually means |
|---|---|---|
| `https://relay.ownspace.in/healthz` | 200 and the text `ok` | The WebSocket relay, nginx, the VM, DNS or the certificate |
| `https://joinowntally.ownspace.in/j` | 200 | nginx, the VM, DNS or the certificate (the invite page) |
| `https://joinowntally.ownspace.in/.well-known/assetlinks.json` | 200, `application/json` (add this once the file exists, M10) | The file is gone or nginx changed; invite links stop opening the app |

The Iroh relay's own checks are in `relay/iroh-relay/RUNBOOK.md`. Also ask the service to warn about certificate expiry (most can), or check by hand every few weeks with the command in step 7. The relay restarts itself after a crash, so an alert usually means the VM, the network, DNS, nginx or a certificate, not the program. **When an alert comes:** run the checks of step 6 from a laptop, then `systemctl status ws-relay nginx` and `journalctl -u ws-relay -n 50` on the VM. Phones keep working meanwhile: the app tries Iroh and the same network first, tries again after 1, 5 and 30 minutes, and keeps every entry on the phone; only phones that can reach each other no other way notice an outage.

## 9. Keep it updated

- Turn on automatic security updates: `sudo apt install unattended-upgrades` and `sudo dpkg-reconfigure -plow unattended-upgrades`.
- nginx and certbot update through `apt`. Check renewal now and then with `sudo certbot renew --dry-run`.
- Rebuild and redeploy the relay when Go publishes security fixes.

## 10. If something goes wrong

| Symptom | Likely cause | What to do |
|---|---|---|
| `curl https://relay.ownspace.in/healthz` times out | Ports 80 or 443 blocked, or DNS wrong | Check the cloud firewall, `ufw status`, `dig` |
| Certbot fails | DNS not yet pointing at the VM, port 80 closed, or the temporary config in 4a is not loaded | Fix, check `sudo nginx -t`, then run certbot again |
| `502 Bad Gateway` from nginx | The relay is not running | `systemctl status ws-relay`, read `journalctl -u ws-relay` |
| Smoke test says `400` or hangs on connect | nginx is not passing the upgrade headers | Compare the `/ws` block in `relay.ownspace.in.conf` with the repo |
| Smoke test says `429` | Too many new connections from one address in a minute (the service file allows 300) | Wait a minute; if real phones are held back, raise `-connects-per-min` in the service file |
| Clients get `503 room full` | A room already has 40 clients (the service file's limit; a group has at most 20 members) | Unusual: many stale members or someone abusing a room. Look at `journalctl -u ws-relay`; ask the owner before raising `-max-per-room` further |
| Memory grows | Many stuck connections | Restart the service and report it; `MemoryMax=300M` in the unit protects the VM |

## 11. What the relay does not do (read before changing it)

- **It cannot read or change what phones say.** Since M7 the app runs TLS between the two phones inside the relay's frames, each side proving the member key it holds, so the relay (and anyone who runs it or watches it) sees only the two public keys, the room (a hash of the group id), addresses, timing and sizes. Do not add anything that depends on the relay seeing content.
- **It has no accounts and does not check who connects.** Anyone who knows a room's name and a member's public key can connect with that key and, because a new connection with the same key replaces the old one, push the real member's connection off the relay now and then (the member's phone reconnects, and tries Iroh and the same network meanwhile). They cannot read, forge or change entries. Who can know both: the group's members, including someone who was removed. The measures today are the rate limits of the service file, nginx's limits and the relay being only one of three ways phones reach each other. Making each client prove it holds its key (the program's `README.md`, "Not in the spike") is **not built**; whether to build it is the owner's decision.
- **It stores nothing.** A message for a phone that is not connected is lost; the sync protocol recovers on the next sync.
