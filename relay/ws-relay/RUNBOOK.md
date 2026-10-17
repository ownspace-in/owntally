# Runbook: deploy the WebSocket relay on a fresh VM

Goal: `wss://relay.ownspace.in/ws` accepts connections, restarts if it crashes, and starts at boot. Written so someone who has not seen the project can follow it. Status of these steps: the program, its tests, the smoke tool and the unit file syntax are checked on a development machine. The steps on a real VM, and the Caddy file, have not been run yet. If a step fails, fix the runbook as part of the same change.

## 0. What you need

- A Linux VM (Ubuntu 22.04 or 24.04 assumed) with a public IPv4 address, 1 GB RAM is plenty, and `sudo` access.
- Control of the DNS for `ownspace.in`.
- Ports 80 and 443 open in the cloud provider's firewall (security group) as well as on the VM. Port 80 is needed to get the HTTPS certificate.
- A laptop with Go installed, to build the program and to run the smoke test.

## 1. DNS

Create an `A` record `relay.ownspace.in` pointing to the VM's IPv4 address (and an `AAAA` record only if the VM has working IPv6). Check it from your laptop:

```
dig +short relay.ownspace.in
```

It must print the VM's address before you continue. Caddy cannot get a certificate until DNS is right.

## 2. Build the program (on your laptop)

Find the VM's CPU type with `uname -m` on the VM: `x86_64` means `amd64`, `aarch64` means `arm64`.

```
cd relay/ws-relay
GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o ws-relay .
scp ws-relay deploy/ws-relay.service deploy/Caddyfile <user>@<vm>:~
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

## 4. Install Caddy and put HTTPS in front

Follow Caddy's official instructions for Ubuntu (the Caddy "stable" package repository), then:

```
sudo install -m 0644 ~/Caddyfile /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

Caddy gets and renews the certificate by itself. Watch it work:

```
sudo journalctl -u caddy -n 50 --no-pager
```

Look for a line saying the certificate was obtained. If you see errors about the challenge, check DNS (step 1) and that ports 80 and 443 are open.

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

Set up an outside uptime monitor (any free service) that requests `https://relay.ownspace.in/healthz` every minute and emails or messages the owner after two failures. The relay and Caddy both restart automatically after a crash, so an alert usually means the VM, the network, DNS or the certificate.

## 9. Keep it updated

- Turn on automatic security updates: `sudo apt install unattended-upgrades` and `sudo dpkg-reconfigure -plow unattended-upgrades`.
- Caddy updates through `apt`.
- Rebuild and redeploy the relay when Go publishes security fixes.

## 10. If something goes wrong

| Symptom | Likely cause | What to do |
|---|---|---|
| `curl https://relay.ownspace.in/healthz` times out | Ports 80 or 443 blocked, or DNS wrong | Check the cloud firewall, `ufw status`, `dig` |
| Certificate errors | DNS not yet pointing at the VM, or port 80 closed | Fix, then `sudo systemctl restart caddy` and read its log |
| `502 Bad Gateway` | The relay is not running | `systemctl status ws-relay`, read `journalctl -u ws-relay` |
| Smoke test says `429` | Too many connects from one address in a minute | Wait a minute, or raise `-connects-per-min` |
| Clients get `503 room full` | A room already has 20 members | Expected limit; raise `-max-per-room` only if the owner agrees |
| Memory grows | Many stuck connections | Restart the service and report it; `MemoryMax=300M` in the unit protects the VM |

## 11. Not done yet (production)

The relay has no authentication. See "Not in the spike" in `README.md`. Do not announce the relay publicly before that is built.
