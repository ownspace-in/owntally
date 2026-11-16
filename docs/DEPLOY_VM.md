# Deploying OwnTally's server side on the VM

One place that says what runs on the VM, how to put it there, how to check it, and how to undo it. The scripts are in `deploy/vm/`. The details behind each piece stay in the runbooks it points to; if a runbook and this guide disagree, fix both in the same change.

**Status of this guide.** The scripts were checked on a development machine only: syntax, the helper functions, the dry-run plan (`deploy/vm/test/run-tests.sh`), a real build, and both programs started locally. **They have not been run against the real VM.** The first run should be an update of the existing VM with `status` first, then `--only ws-relay`, and the owner should read the output. Everything the scripts do is what the runbooks already did by hand.

## 1. What runs on the VM

| Piece | What it is | Listens on | Managed by | Detail |
|---|---|---|---|---|
| **ws-relay** | The WebSocket relay (our Go program, `relay/ws-relay`). Forwards opaque messages between two phones in a room. Stores nothing. Phones sign in with their member key (M12). | `127.0.0.1:8080` | systemd unit `ws-relay` (runs as a throwaway user) | `relay/ws-relay/RUNBOOK.md` |
| **iroh-relay** | The Iroh relay (the official program, v1.4.1). Helps phones find each other and relays when they cannot connect directly. | `127.0.0.1:3340` and `127.0.0.1:8443` (TCP), **UDP 7842 public** | systemd unit `iroh-relay` (user `iroh-relay`) | `relay/iroh-relay/RUNBOOK.md` |
| **nginx** | Terminates HTTPS for both names; proxies `/ws`, `/healthz` to ws-relay and `/relay`, `/ping`, `/generate_204` to iroh-relay (with per-address limits); serves the static site. Access logging is **off** on purpose. | public TCP 80 and 443 | systemd | `/etc/nginx/conf.d/` |
| **certbot** | Free certificates for `relay.ownspace.in` and `joinowntally.ownspace.in`, renewed by a timer. Hooks reload nginx and copy the certificate for iroh-relay. | none | systemd timer | `relay/ws-relay/RUNBOOK.md` step 4 |
| **Invite site** | Static files: `/j` (invite page), `/privacy`, and later `/.well-known/assetlinks.json`. | via nginx | files in `/var/www/joinowntally` | `site/README.md` |

The VM runs other sites too. Nothing here touches their nginx configs: our files match only our two names.

Ports that must be open to the internet (cloud security list **and** `ufw` on the VM): TCP 22 (SSH), TCP 80, TCP 443, **UDP 7842**. Never open 8080, 3340 or 8443.

## 2. Before you start

- **A VM**: Ubuntu 22.04 or 24.04, a public IPv4 address, 1 GB of RAM, `sudo`.
- **DNS**: `A` records for `relay.ownspace.in` and `joinowntally.ownspace.in` pointing at the VM (DNS only, not proxied). `dig +short relay.ownspace.in` must print the VM's address.
- **Your machine**: `go`, `curl`, `tar`, `ssh`, `sha256sum`, `openssl`, and SSH access to the VM with your key (`ssh <user>@<vm>` works without a password prompt).
- **Cloud firewall**: the four ports above. The UDP rule cannot be made from the VM.
- **Settings**: `cp deploy/vm/config.env.example deploy/vm/config.env` and set `VM_HOST` and `VM_USER`. The file is git-ignored and holds no secrets.
- **Tests pass** and the commit you are deploying is merged to `main` (the bundle records the commit and warns when the tree is dirty).

## 3. The scripts

| Script | Runs on | What it does | Changes anything? |
|---|---|---|---|
| `deploy/vm/deploy.sh` | your machine | Builds, sends, installs on the VM, then runs `check.sh`. `--only` picks a part; asks you to type the host name first. | yes (the VM) |
| `deploy/vm/build.sh` | your machine | Builds `deploy/vm/dist/bundle`: ws-relay for the VM's CPU, the Iroh relay (checksum-verified download), the configs from their place in the repo, the site without its dev files, the VM scripts. | local files only |
| `deploy/vm/push.sh` | your machine | Copies the bundle to `~/owntally-deploy` on the VM. | yes (that folder only) |
| `deploy/vm/check.sh` | your machine | Looks at both names from outside: health, status codes, security headers, certificate days left. `SMOKE=1` adds the relay's end-to-end test. | no |
| `deploy/vm/test/run-tests.sh` | your machine | Tests of the scripts themselves; no VM or network needed. | no |
| `remote/first-time-certs.sh` | the VM | **Fresh VM only.** nginx, certbot, certificates for both names, final nginx configs, renewal hook. Refuses to run if the relay's nginx config exists. | yes |
| `remote/install-ws-relay.sh` | the VM | Installs the program and unit, restarts, checks `/healthz`. Keeps `ws-relay.prev`. | yes |
| `remote/install-iroh-relay.sh` | the VM | User, certificate copy, program, config, unit, renewal hook, restart, checks the listeners. Refuses a config with an active `access` line. | yes |
| `remote/install-nginx.sh` | the VM | Adds the limit zones and the three Iroh locations to the relay's nginx config; `nginx -t` first and after; puts the old files back if the test fails. | yes |
| `remote/install-site.sh` | the VM | Copies the site to `/var/www/joinowntally`. | yes |
| `remote/status.sh` | the VM | Read-only report: services, listeners, health, `nginx -t`, certificate days, disk, memory. | no |

Every file the scripts replace is first copied to `/root/owntally-backups/` on the VM.

## 4. First-time setup of a fresh VM

Do these in order. Stop at the first thing that does not match what is written.

1. **DNS, cloud firewall, SSH** as in section 2.
2. **Dry run** (shows the plan, changes nothing):
   `DRY_RUN=1 CERTBOT_EMAIL=you@example.org deploy/vm/deploy.sh --only first-time`
3. **Run it:** `CERTBOT_EMAIL=you@example.org deploy/vm/deploy.sh --only first-time`. It builds, asks you to type the host name, then on the VM: certificates and nginx (`first-time-certs.sh`), the site, ws-relay, iroh-relay, and the nginx limits, then it checks from outside.
   - If certbot fails: DNS does not point at the VM yet, or port 80 is closed.
   - If `nginx -t` says `duplicate "connection_upgrade" variable`: another site on the VM already defines that `map`. Delete the `map` block from `/etc/nginx/conf.d/relay.ownspace.in.conf` on the VM, then `sudo nginx -t && sudo systemctl reload nginx`.
4. **Firewall on the VM** (only after allowing SSH, or you lock yourself out):
   `sudo ufw allow OpenSSH && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp && sudo ufw allow 7842/udp && sudo ufw enable`
   If the VM already had other sites, `ufw` is probably already set up: add only what is missing (`sudo ufw status`).
5. **The assetlinks file**, once the signing certificate exists (`site/README.md`), then `deploy/vm/deploy.sh --only site`.
6. **The outside monitor** of `relay/ws-relay/RUNBOOK.md` section 8 and `relay/iroh-relay/RUNBOOK.md` (health check and alert).
7. **Phones:** the checks in `docs/RELEASE_CHECKLIST.md` (two real phones, over the relay path only, `authfail=0`).

## 5. Updating a VM that is already set up

The usual case: new relay code, a new site page, or new limits.

1. `deploy/vm/deploy.sh --only status` (read-only). Everything should be `active`, certificates more than 21 days.
2. `DRY_RUN=1 deploy/vm/deploy.sh --only ws-relay` to see the plan.
3. `deploy/vm/deploy.sh --only ws-relay` (or `iroh-relay`, `nginx`, `site`, or leave `--only` off for all four).
4. Read the output. Each script ends with a line saying healthy, or with the log and an error.
5. `deploy/vm/check.sh`. `SMOKE=1 deploy/vm/check.sh` also runs a real two-client round trip.

**Releases that change the app and the relay together** (like M12): deploy the relay, run the smoke test, then install the app on your phones. An old app is refused by the new relay (`relay/ws-relay/RUNBOOK.md`, "Updating to the version with sign-in").

**What a good run prints.** Each VM script ends with one line, and `deploy.sh` stops at the first script that fails:

| Script | Last line when it worked | If it does not appear |
|---|---|---|
| `install-ws-relay.sh` | `ws-relay is running and healthy` | the service log is printed above the error; roll back (section 6) |
| `install-iroh-relay.sh` | `iroh-relay is running and healthy on loopback` (then a reminder about UDP 7842) | the log is printed; read the `WARNING:` lines to see which listener or URL failed |
| `install-nginx.sh` | `nginx reloaded` (or `the Iroh locations are already in ...`, then `nginx reloaded`) | `nginx config rejected; nothing was reloaded`: the old files were put back, `sudo nginx -t` shows the error |
| `install-site.sh` | `site files are in /var/www/joinowntally` | a missing file is named |
| `check.sh` | `all checks passed` | each `FAIL` line says what it wanted and what it got |

Restarting ws-relay drops its connections; phones reconnect within seconds and nothing is lost (the logs stay on the phones).

## 5b. Updating the WebSocket relay by hand (no scripts)

Use this when the scripts are not an option, or to understand what `install-ws-relay.sh` does: it is the same steps. It is also the right way to do the **M12 update** (relay with sign-in) the first time. Every step has a check; do not go on if one fails. Steps 1 and 2 are on your laptop, 3 to 7 on the VM, 8 on your laptop, 10 on your phones; 9 is only if something fails.

**Why step 4 matters.** On 16 Nov 2026 the VM's unit file was still the one of 20 Oct 2026 (`ExecStart=/usr/local/bin/ws-relay -addr 127.0.0.1:8080 -trust-proxy`): the production limits of decision R3 (`-connects-per-min 300 -max-per-room 40`, in the repo since 9 Nov) had never reached it, so the VM ran on the program's defaults of 30 connections a minute and 20 clients per room. Updating only the program would have kept it that way.

**1. Laptop: test and build.**
```
cd relay/ws-relay
go test -race ./...
GOOS=linux GOARCH=amd64 go build -buildvcs=false -ldflags="-s -w" -o ws-relay .
govulncheck ./...
```
Use `GOARCH=arm64` if `ssh <user>@<vm> uname -m` prints `aarch64` (`x86_64` is amd64). `govulncheck` is installed with `go install golang.org/x/vuln/cmd/govulncheck@latest`; `tools/check_vulnerabilities.py` does not cover Go modules.
*Check:* the tests end with `ok` for every package (no `FAIL`); `ls -l ws-relay` shows a file of about 6 MB; `govulncheck` says `No vulnerabilities found` (if it lists one, read it before going on).

**2. Laptop: copy the program and the unit file to the VM.**
```
scp ws-relay deploy/ws-relay.service <user>@<vm>:~
```
*Check:* `ssh <user>@<vm> ls -l ws-relay ws-relay.service` lists both, with the sizes you expect.

**3. VM: look at the state before changing anything.**
```
systemctl status ws-relay nginx --no-pager
curl -s http://127.0.0.1:8080/healthz
```
*Check:* both services `active (running)` and the curl prints `ok`. If not, stop: this is not an update problem, fix it first (section 7, `relay/ws-relay/RUNBOOK.md` section 10).

**4. VM: compare the unit file on the VM with the repo's.**
```
diff /etc/systemd/system/ws-relay.service ~/ws-relay.service
systemctl cat ws-relay | grep ExecStart
```
*Check:* the repo's `ExecStart` ends in `-trust-proxy -connects-per-min 300 -max-per-room 40`. If the VM's does not, the diff shows exactly the added flags and comments: that is the update to make. If the diff shows something else (an edit made by hand on the VM), stop and read it; decide whether to keep it by putting it in the repo's file first.

**5. VM: save what you are about to replace.**
```
sudo cp -a /usr/local/bin/ws-relay /usr/local/bin/ws-relay.prev
sudo cp -a /etc/systemd/system/ws-relay.service ~/ws-relay.service.prev
```
*Check:* `ls -l /usr/local/bin/ws-relay.prev ~/ws-relay.service.prev` lists both. Without these step 9 cannot work.

**6. VM: install and restart.**
```
sudo install -m 0644 ~/ws-relay.service /etc/systemd/system/ws-relay.service
sudo install -m 0755 ~/ws-relay /usr/local/bin/ws-relay
sudo systemctl daemon-reload
sudo systemctl restart ws-relay
```
Phones lose their connection for a few seconds and reconnect by themselves.

**7. VM: check that the new program is the one running.**
```
systemctl status ws-relay --no-pager
systemctl cat ws-relay | grep ExecStart
sudo journalctl -u ws-relay -n 5 --no-pager
curl -s http://127.0.0.1:8080/healthz
```
*Check, all four:* `active (running)` with a start time of a minute ago; `ExecStart` now ends in `-connects-per-min 300 -max-per-room 40`; the stats line in the log ends in `authfail=0` (an old program prints no `authfail`, so this proves the new one runs); the curl prints `ok`. If any is wrong, go to step 9.

**8. Laptop: check from outside, through nginx.**
```
curl -s https://relay.ownspace.in/healthz
cd relay/ws-relay && go run -buildvcs=false ./tools/smoke
```
*Check:* `ok`, then `OK  connect=...  roundtrip=...` (the smoke tool signs in with keys it makes itself, so it needs the new program). If it fails:

| Output | Meaning | Do |
|---|---|---|
| `status 4401` or `not admitted` | An old program is still running, or the smoke tool is from an older commit | Step 7's `authfail` check; rebuild both from the same commit |
| `429` | Too many new connections from your address in a minute | Wait a minute, run again |
| `502` | nginx cannot reach the relay | `systemctl status ws-relay`, `sudo tail /var/log/nginx/relay-error.log` |
| `400`, or it hangs on connect | nginx is not passing the upgrade headers | Compare the `/ws` block with `relay/ws-relay/deploy/nginx/relay.ownspace.in.conf` |

**9. Only if a step failed: roll back.**
```
sudo install -m 0755 /usr/local/bin/ws-relay.prev /usr/local/bin/ws-relay
sudo install -m 0644 ~/ws-relay.service.prev /etc/systemd/system/ws-relay.service
sudo systemctl daemon-reload
sudo systemctl restart ws-relay
curl -s http://127.0.0.1:8080/healthz
```
*Check:* `ok`, and `systemctl cat ws-relay | grep ExecStart` shows the old line. Then report what failed, with the output.

**10. Phones: install the matching app.** The new relay refuses a client that does not sign in, so an app from before M12 shows the relay path as not reachable (Iroh and the local network still work).
```
./gradlew :app:app:assembleDebug
adb install -r app/app/build/outputs/apk/debug/<the apk file>
```
*Check:* on two phones, switch Wi-Fi off on both (mobile data), open a group, add an expense on one: it appears on the other. Then on the VM `sudo journalctl -u ws-relay -n 5 --no-pager` shows `clients=` above 0 while they sync and `authfail=0` still. This is the first item of the M12 part of `docs/RELEASE_CHECKLIST.md`; tick it there only when you have seen it.

## 6. Rolling back

| What went wrong | Undo |
|---|---|
| New ws-relay misbehaves | `sudo install -m 0755 /usr/local/bin/ws-relay.prev /usr/local/bin/ws-relay && sudo systemctl restart ws-relay` |
| New unit file misbehaves (service will not start after step 6 of section 5b) | `sudo install -m 0644 ~/ws-relay.service.prev /etc/systemd/system/ws-relay.service && sudo systemctl daemon-reload && sudo systemctl restart ws-relay` (the scripts keep the copy in `/root/owntally-backups/`, name starts `_etc_systemd_system_ws-relay.service`) |
| New iroh-relay misbehaves | `sudo install -m 0755 /usr/local/bin/iroh-relay.prev /usr/local/bin/iroh-relay`, copy the config back from `/root/owntally-backups/` (name starts `_etc_iroh-relay_config.toml`), `sudo systemctl restart iroh-relay` |
| nginx change | The script restores by itself when `nginx -t` fails. After a reload, copy the file from `/root/owntally-backups/` back, `sudo nginx -t && sudo systemctl reload nginx`. To remove the Iroh paths completely: `relay/iroh-relay/RUNBOOK.md`, "Undo". |
| Site change | Redeploy the earlier commit (`git checkout <commit>` then `--only site`). The script never deletes files, so files you added stay. |

## 7. Everyday operations

| Task | Command (on the VM) |
|---|---|
| Whole state | `deploy/vm/deploy.sh --only status` from your machine |
| ws-relay logs (counts and errors only, never contents) | `sudo journalctl -u ws-relay -n 50 --no-pager`; `authfail=` counts refused sign-ins |
| iroh-relay logs | `sudo journalctl -u iroh-relay -n 50 --no-pager` |
| nginx errors | `sudo tail /var/log/nginx/relay-error.log` (there is no access log, on purpose) |
| Certificate renewal check | `sudo certbot renew --dry-run` |
| Go vulnerabilities in the relay | `cd relay/ws-relay && govulncheck ./...` on your machine, before each deploy (`tools/check_vulnerabilities.py` does not cover Go modules) |
| Iroh release notes | read them before changing `IROH_VERSION`; `build.sh` refuses a version without a checksum |

**Do not add logging that records room names, keys or message contents.** The relay and nginx are set up so that a seized VM tells nothing about groups.

## 8. Things to know

- **The scripts replace by copying.** `build.sh` copies the configs from `relay/ws-relay/deploy/`, `relay/iroh-relay/` and `site/nginx/`; edit them there and redeploy. Do not edit them only on the VM.
- **The live invite-site config was changed by hand** on 3 Nov 2026 (the security headers, then certbot's edits) and the scripts do not rewrite it. `site/nginx/joinowntally.ownspace.in.conf` is what a fresh VM gets. If you want the live file to match, compare with `diff` and change it by hand with `nginx -t` before reload. `install-nginx.sh` only changes the relay's file.
- **Doc mismatch, left as is:** the comment at the top of `relay/iroh-relay/config.toml` says to install it as `config.template.toml` and run `render-config.sh`. That is the token mode, which is off (decision of 3 Nov 2026, runbook section 9b). Production installs the file as `config.toml`, which is what `install-iroh-relay.sh` does. The comment should be reworded when someone next edits that file.
- **The VM's ws-relay unit lagged the repo.** On 16 Nov 2026 the VM still ran the unit of 20 Oct (no `-connects-per-min 300 -max-per-room 40`), so the R3 limits were not in force until the update of section 5b. `status.sh` now warns when the live unit lacks them. Record the date of the update here when it is done: **DATE: not yet done**.
- **UDP 7842** is the one thing the scripts cannot open (cloud side). If phones stay on "Via relay" and never go direct, check it first.
- **Not covered:** the Play Console, the signing keys and `assetlinks.json` (`docs/RELEASE.md`), the uptime monitor, the second relay for failover (`docs/DESIGN.md`).
