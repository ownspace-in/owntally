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

Restarting ws-relay drops its connections; phones reconnect within seconds and nothing is lost (the logs stay on the phones).

## 6. Rolling back

| What went wrong | Undo |
|---|---|
| New ws-relay misbehaves | `sudo install -m 0755 /usr/local/bin/ws-relay.prev /usr/local/bin/ws-relay && sudo systemctl restart ws-relay` |
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
- **UDP 7842** is the one thing the scripts cannot open (cloud side). If phones stay on "Via relay" and never go direct, check it first.
- **Not covered:** the Play Console, the signing keys and `assetlinks.json` (`docs/RELEASE.md`), the uptime monitor, the second relay for failover (`docs/DESIGN.md`).
