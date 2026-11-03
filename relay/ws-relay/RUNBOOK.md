# Runbook: deploy the WebSocket relay on a fresh VM

Goal: `wss://relay.ownspace.in/ws` accepts connections, restarts if it crashes, and starts at boot. The same nginx also serves the static invite page at `joinowntally.ownspace.in` (see `site/README.md`). Written so someone who has not seen the project can follow it. Status of these steps: the relay, nginx and certbot steps were run on the real VM on 20 Oct 2026 using the shortcut below, and the smoke test passed. The program, its tests, the smoke tool, the unit file syntax and both nginx configs (routing, WebSocket upgrade, redirects, content types, checked with a self-signed certificate) are verified on a development machine. The steps on a real VM, and the certbot steps, have not been run yet. If a step fails, fix the runbook as part of the same change.

## 0. What you need

- A Linux VM (Ubuntu 22.04 or 24.04 assumed) with a public IPv4 address, 1 GB RAM is plenty, and `sudo` access.
- Control of the DNS for `ownspace.in`.
- Ports 80 and 443 open in the cloud provider's firewall (security group) as well as on the VM. Port 80 is needed to get the HTTPS certificate.
- A laptop with Go installed, to build the program and to run the smoke test.

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

The VM already runs nginx. Our files go in `/etc/nginx/conf.d/`, which the default Ubuntu `nginx.conf` includes. Other sites on the VM are not touched, because ours match only their own server names. Run `sudo nginx -t` before every reload, and stop if it reports an error.

Install certbot (nginx is already there):

```
sudo apt install certbot
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

**Shortcut when nginx and certbot already run on the VM (this is what was actually done):** write plain port 80 server blocks for the two names (the `/ws` and `/healthz` proxy blocks from `relay.ownspace.in.conf`, and the static site from `joinowntally.ownspace.in.conf`, both without the `ssl` lines), run `sudo nginx -t && sudo systemctl reload nginx`, then `sudo certbot --nginx -d relay.ownspace.in -d joinowntally.ownspace.in`. Certbot adds HTTPS, the redirect and automatic renewal, so 4c is not needed. The invite site was deployed this way too and checked with `curl -sI` (200 on `/j` and `/style.css`, 301 from http). The stricter headers in `site/nginx/joinowntally.ownspace.in.conf` (CSP, `X-Frame-Options`) were left out of that port 80 config and should be added back before the invite page is announced.

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
| Change limits | edit the `ExecStart` line in `/etc/systemd/system/ws-relay.service` (flags are listed in `README.md`), then `sudo systemctl daemon-reload && sudo systemctl restart ws-relay` |
| Check the certificate | `echo | openssl s_client -connect relay.ownspace.in:443 -servername relay.ownspace.in 2>/dev/null | openssl x509 -noout -dates` |

The relay logs only counts and errors, never message contents or keys. Do not add logging that changes this.

## 8. Health check and alert

Set up an outside uptime monitor (any free service) that requests `https://relay.ownspace.in/healthz` every minute and emails or messages the owner after two failures. The relay restarts itself after a crash, so an alert usually means the VM, the network, DNS, nginx or a certificate.

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
| Smoke test says `429` | Too many connects from one address in a minute | Wait a minute, or raise `-connects-per-min` |
| Clients get `503 room full` | A room already has 20 members | Expected limit; raise `-max-per-room` only if the owner agrees |
| Memory grows | Many stuck connections | Restart the service and report it; `MemoryMax=300M` in the unit protects the VM |

## 11. Not done yet (production)

The relay has no authentication. See "Not in the spike" in `README.md`. Do not announce the relay publicly before that is built.
