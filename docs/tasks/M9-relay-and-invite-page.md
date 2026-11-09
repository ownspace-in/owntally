# M9 tasks: the relay, the invite page and opening the app from a link

Parent: [BUILD_PLAN.md](../BUILD_PLAN.md), milestone M9. Rules: [DESIGN.md](../DESIGN.md) ("Invite link page", "Hosting your own relay", "Join by link"), `relay/ws-relay/RUNBOOK.md`, `relay/iroh-relay/RUNBOOK.md`, `site/README.md`. Tests first.

Goal (BUILD_PLAN): the relay runbook that someone who has not seen the project can follow, and the invite page at `joinowntally.ownspace.in` (`/j` and `/.well-known/assetlinks.json`). Acceptance: from a fresh VM, the runbook produces a working relay; tapping an invite link on a phone with the app opens it directly, and on a phone without it shows the install page.

**Decisions A1 and R3 are made (9 Nov 2026, below). Nothing is built until the owner says "next".**

## Progress

| Task | Status |
|---|---|
| T9.1 plan and decisions | Done (9 Nov 2026): A1 and R3 decided. |
| T9.2 to T9.6 | Not started. |

## What already exists (so it is not rebuilt)

- **The relays, on the real VM:** the WebSocket relay (`relay/ws-relay`, Go) at `wss://relay.ownspace.in/ws` since 20 Oct 2026 and the Iroh relay (`relay/iroh-relay`) at `https://relay.ownspace.in`, with nginx, certbot, systemd units, health checks, runbooks with their checks, a smoke test, and phones verified on mobile data (2 Nov 2026). Not run yet: the Iroh relay's access token and rate-limit step (RUNBOOK section 10) and the outside uptime monitor (the owner's).
- **The invite site (`site/`):** a landing page, the page behind `/j#<code>` (shows the code, a Copy button and install steps, with no outside resources, no inline script, no logging), the nginx config, deployed on 20 Oct and given its security headers on 3 Nov 2026. Missing: `/.well-known/assetlinks.json` (it needs the signing certificate of a signed build, which is M10) and the Play Store link ("will be added before release").
- **The app side of the link:** `Invites.create` builds `https://joinowntally.ownspace.in/j#<ticket>`, `Ticket.fromShared` reads the link or the bare code, and `JoinGroup.open` handles an invitation pasted into "Join with link or code" (M6). **Not there:** the manifest declares no link, so tapping the link opens the browser page, not the app, and `MainActivity` never looks at the intent that started it.

## Decisions made

- **A1 (9 Nov 2026): an invite link that arrives while the app is on first-run setup or the lock screen is saved on the phone until used.** It survives the app being closed during setup (the recovery phrase step can take a while). The invite holds the group key, so it is kept only in the app's protected storage (the Keystore-sealed secret files in the no-backup folder, like the group keys), never in the database, which Android may back up, and never in a log. One pending link: a newer one replaces it. It is removed when it is used, when it is refused as not an invitation, or when its own time is up (the ticket's expiry), whichever comes first. After setup or unlock the join form opens with it.
- **R3 (9 Nov 2026): the relay's service file in the repo takes `-connects-per-min 300 -max-per-room 40`** (T7.9: phones behind one carrier address share the 30 a minute; a removed member or a joiner waiting to be admitted is the 21st in a full group). The runbook says why. The owner applies it to the live VM (edit the unit, `daemon-reload`, restart).

## Builder's choices (to confirm in the pull request)

1. **Only `https` links on this host and path open the app:** `https://joinowntally.ownspace.in/j` with a code after `#`. No custom `owntally://` scheme (the page says WhatsApp only linkifies `https://`), no other host, no `http`, no path other than `/j`.
2. **The app claims the link with `android:autoVerify="true"`** (Android App Links). Until `assetlinks.json` matches the signing certificate Android does not verify it and, on Android 12 and newer, the link opens in the browser; the page still shows the code, so nothing is lost. For testing before M10 the owner can turn the link on by hand (Settings, Apps, OwnTally, Open by default, Add link) or the checker below can use the debug certificate.
3. **The app handles a new link while it is already open** (`singleTask` and `onNewIntent`), so a second tap does not stack a second copy of the app.
4. **After a link is read, the intent's data is cleared** so it does not stay in the activity, the recents list or a saved state; the app asks again from its own storage (A1).
5. **A link for a group the person is already in, expired, or not an invitation** says so in words (the same messages as pasting it); nothing is written.
6. **`assetlinks.json` is made by a script from the certificate's SHA-256 fingerprint** (`site/make_assetlinks.py`), not typed by hand; the repo keeps a template with a placeholder and a test that checks its shape and the application id.

## Tasks

- **T9.2 The invite page against the real link.** A test that the page's script reads exactly what the app writes (`Ticket.encode` through `Invites`' link, with the characters that need care), that the page and its script load nothing from outside and keep working under the site's content-security policy (a static check of the files), and that the nginx files still pass `nginx -t` where nginx is available. Fix what it finds.
- **T9.3 Opening the app from a link.** (a) `InviteLinks`, plain Kotlin: reads an intent's action, scheme, host, path and fragment and returns the link text or null (choice 1), with tests for every refusal (other host, `http`, other path, no fragment, empty fragment, a very long one, odd encodings). (b) The pending link (A1): stored in the secret store, one at a time, with its expiry, removed when used, refused or expired; tests for each, for a restart, and that the database and the backup rules never see it. (c) The manifest filter (`VIEW`, `BROWSABLE`, `DEFAULT`, `https`, the host, path `/j`, `autoVerify`) and `ManifestRulesTest` updated (one exported activity, one extra filter, exactly this one). (d) `MainActivity`: read the intent in `onCreate` and `onNewIntent`, save the link, clear the intent (choice 4); the app shows the join form once it is past setup and the lock (messages of choice 5). (e) Emulator tests: the link fired at the app on a set-up phone (opens the form, and the form shows the invitation), during first-run setup (opens after it), on the lock screen (after unlocking), twice, with a bad link, and for a group already joined.
- **T9.4 `assetlinks.json`.** `site/make_assetlinks.py` (the application id and one or more SHA-256 fingerprints in, the file out, with the checks of `site/README.md`), a template, a test that the template has the right shape and the application id of the build, and the README section: how to get the fingerprint of the release key and of Play's app signing key (M10), how to test with the debug key, and the `curl` check.
- **T9.5 The relay runbooks.** `-connects-per-min 300 -max-per-room 40` in the service file (R3) and the runbook (why, and how to apply them to a live VM); the outside uptime monitor made concrete (which two URLs, how often, what an alert means) for the WebSocket relay and the Iroh relay; the status lines made true (what was run on the real VM and when, what was not); a read of both runbooks from the top as someone who has not seen the project, fixing what a stranger would trip on; the Iroh relay's section 10 (access token) stated as decided and not run. **A fresh-VM run is the owner's check:** the runbook cannot be proved by me.
- **T9.6 Hand back.** All tests, lint, builds, the vulnerability check; the checklist for the owner (a real phone tapping a link with the app installed and not, with the app locked, and after first run; the assetlinks check once there is a signed build; the fresh-VM run; the uptime monitor); DESIGN.md and BUILD_PLAN.md updated; the pull request.

## Open questions

None at the moment. What only the owner can do: the fresh-VM run of the relay runbook, the uptime monitor, applying R3 to the live VM, and (M10) the signing certificate for `assetlinks.json` and the Play Store link on the page.

## Results

(To be written as each task is done.)
