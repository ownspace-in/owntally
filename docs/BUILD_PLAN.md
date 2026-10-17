# OwnTally build plan

This plan is written so that any developer or AI coding agent can pick up one milestone and build it without asking what was meant. The behaviour is defined in [DESIGN.md](DESIGN.md) and the screens are in [../design/](../design/). If this plan and DESIGN.md disagree, DESIGN.md wins and the plan should be fixed.

## Ground rules for whoever builds

1. Read `DESIGN.md` fully, then the milestone you are working on. Do not start a later milestone early.
2. Do not change a decision recorded in `DESIGN.md`. If something seems wrong or is missing, stop, write the question in the pull request, and ask the owner.
3. Write the tests first, from the acceptance criteria below, then the code.
4. Keep the pure logic (events, balances, settlement, crypto formats) in plain Kotlin with no Android imports, so it runs on the JVM and is fast to test.
5. One milestone per pull request, small commits, all tests green before asking for review.
6. Never commit secrets (keystores, server keys, `.env`). Never add analytics, crash reporters, ads or any network call that is not part of sync.
7. Do not write your own cryptography. Use the libraries named below, or tell the owner why one does not work.
8. Security-relevant code (M2, M5, M7) must be reviewed by a human before merge.

## Stack

Names marked "candidate" must be confirmed in M0 before depending on them.

| Area | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3, light theme first, then dark |
| Android | minSdk 26, targetSdk latest stable |
| Local storage | SQLite through Room (event log is one table, append only) |
| Crypto | Google Tink or libsodium bindings (candidate): Ed25519 signatures, XChaCha20-Poly1305 or AES-256-GCM, X25519 key exchange, HKDF |
| Recovery phrase | BIP39 word list, 12 words (candidate: a small Kotlin BIP39 library) |
| Same-room sync | Google Nearby Connections (`play-services-nearby`), with same-Wi-Fi discovery as fallback |
| Internet sync | Decided in M0 between Iroh (Rust, Kotlin bindings) and a simple WebSocket relay (OkHttp client). Both sit behind one `Transport` interface. See risk R1. |
| QR | ZXing or ML Kit barcode scanning (candidate) |
| Build and CI | Gradle Kotlin DSL, GitHub Actions running unit tests and lint on every pull request |
| DI | Hilt, or manual wiring if that is simpler |

## Code layout

```
app/
  core-model/     pure Kotlin: events, log replay, balances, split rules, settlement plan
  core-crypto/    pure Kotlin or thin wrappers: seed, group keys, sign, encrypt, tickets
  data/           Room database, repositories
  sync/           transport interface, sync protocol, Nearby and Iroh transports
  ui/             Compose screens and view models
  app/            application module
relay/            relay config, systemd unit, runbook
site/             static invite page and assetlinks.json for joinowntally.ownspace.in
docs/             DESIGN.md, BUILD_PLAN.md, PROTOCOL.md (written in M1)
design/           mockup images
```

## Risks to settle early

| Id | Risk | Why it matters | How M0 handles it |
|---|---|---|---|
| R1 | Iroh's Kotlin bindings may not work on Android out of the box. The published package is reported to cover JVM only, and async calls are reported not to complete on Android. Community builds exist. | Internet sync and the relay depend on it. | M0 tests it on real phones, and builds a WebSocket relay baseline so there is always a working fallback. |
| R2 | Direct connections fail on many mobile networks. | Some pairs will only work through the relay. | M0 measures it with the real relay. |
| R3 | Nearby Connections needs Google Play Services. | Phones without it cannot use same-room sync. | Same-Wi-Fi fallback is built in M6. |
| R4 | Android restricts background work. | Sync only works while the app is open. | Accepted by design. Never promise background sync. |

## Milestones

### M0. Spike: choose the internet transport (risk check, throwaway code)

Step-by-step tasks: [tasks/M0-spike.md](tasks/M0-spike.md).

Build a tiny test app, not the product. Write results into `docs/SPIKE_RESULTS.md`. Define the `Transport` interface first (connect to a member, send bytes, receive bytes, report link type), so that every candidate below is plugged in behind it.

Step 1. Baseline: WebSocket relay (about one day).
- Write a minimal relay program for `relay/`: phones connect outward over a secure WebSocket, identify by public key, and the relay forwards opaque messages between two phones in the same group room. It stores nothing and never decrypts.
- Two phones on different mobile networks exchange a message through it, using OkHttp on Android.
- This is the guaranteed fallback. Keep it small (a few hundred lines) and document it.

Step 2. Iroh (time-box: a few days).
- Two phones on different mobile networks exchange a message, first directly, then forced through `relay.ownspace.in`.
- Try the official Kotlin package. If async calls hang on Android, try a community JNI-based build, or build the bindings from source for arm64.
- Pass only if all of these hold: connection completes on real phones, no hanging calls, a relayed connection works through our relay, and app size growth is acceptable (record it).
- If it fails, record why and stop. Do not spend more than the time-box.

Step 3. Same room: two phones exchange a message with Nearby Connections.

Step 4. Record, for each candidate: success or failure, time to connect, direct or relayed, app size added, minimum Android version that worked, number of runs.

Decision rule (the owner approves):
- If Iroh passes: use Iroh as the main transport, and keep the WebSocket relay behind the same interface as a backup.
- If Iroh fails: use the WebSocket relay. Direct links across networks are then not available, and same-room sync stays on Nearby.
- libp2p and WebRTC are not tested in M0. Try one only if Iroh fails and the owner decides direct links across networks are a must-have.

Done when: `docs/SPIKE_RESULTS.md` has the numbers and a recommendation, the owner has chosen the transport, and the `Transport` interface is committed.

### M1. Core domain (pure Kotlin, JVM tests only)

Step-by-step tasks: [tasks/M1-core-domain.md](tasks/M1-core-domain.md).

Build and test: the event types in DESIGN.md, the log (append only, identified by device key and counter, merge is a union), replay to current state, balance calculation, equal and exact splits, the group-level settlement plan (simple matching, at most members minus 1 payments), deletion rules, and member removal with rebalancing (their expenses and payments excluded, shares re-split for equal splits). Write `docs/PROTOCOL.md` describing the event format and rules you chose, and have the owner approve it.

Acceptance:
- Replaying the same events in any order gives identical balances (property test).
- The settlement plan always sums to zero and clears every balance (property test over random groups).
- A removal fixture with hand-computed before and after balances passes. The numbers in the Remove mockup are illustrative.
- Deleting an expense removes it from balances but keeps it in history.
- A removed member's later events are ignored.

### M2. Identity and crypto

Master seed and 12-word phrase, per-group keypairs derived from the seed, event signing and verification, group-key encryption of event bodies, new group key (epoch) on member removal sent to each remaining member, ticket encode and decode (host id, group id, group key, expiry 8 hours, single use).

Acceptance:
- Phrase restore gives the same keys (test vectors).
- A tampered event, a wrong signature or an event from a non-member is rejected.
- A removed member cannot decrypt events from the new epoch.
- An expired or reused ticket is rejected.
- No key or phrase is ever written to logs.

### M3. Storage and app shell (local only)

Room database, repositories, navigation, and the screens that need no network: Welcome, Recovery phrase (show then confirm, cannot be skipped), Groups, Group, Add expense, Settle up (computed plan, buttons inert), How it works. Match the mockups. Keys live in Android Keystore. Optional app lock. Screenshots blocked on the phrase screen.

Acceptance: a user can create a group, add and delete expenses, and see correct balances, fully offline, and the data survives an app restart.

### M4. Membership, roles and ownership (still local)

Members, owner, admins, join requests, approve or decline, remove with the before and after screen, ownership transfer with acceptance, profile updates (name and phone number only). Rules enforced in the core, not the UI.

Acceptance: every rule in DESIGN.md "Flows" has a test, including: only creator or admin can delete, only owner changes admins, any admin can remove, owner cannot be removed until ownership moves.

### M5. Sync protocol over a fake transport

Define a `Transport` interface. Build the protocol on top: exchange version summaries, send only missing events, verify and apply, idempotent, resumable. Test with an in-memory transport and 3 to 5 simulated devices with random delays, drops and reordering.

Acceptance: simulated devices always converge to identical logs and balances. Interrupted syncs resume. Events with bad signatures are dropped and reported.

### M6. Nearby sync, invites and joining

Nearby transport with fallback to same Wi-Fi, invite screen (QR, share link, copy code, expiry, revoke), QR scan, Join flow, host approval, join requests screen, sync status screen.

Acceptance (two real phones): invite by QR, scan, approve, history arrives, add an expense on each side and both converge, airplane mode with Wi-Fi only still works if fallback is in.

### M7. Internet sync and relay setting

Internet transport (as chosen in M0), per-group relay switch (admin only), default `https://relay.ownspace.in`, custom address field, status chips (Direct, Via relay, Offline, Needs relay), the "Can't reach a member" screen, retry rules (connect 15 s, stall 30 s, back off 1, 5, 30 minutes).

Acceptance: two phones on different mobile networks sync, the status shows the right chip, and turning the relay off makes relay-only pairs show "Needs relay" instead of failing silently.

### M8. Settling

I paid, Received, Not received and reasons, reminders through the share sheet, unconfirmed state, plan updates after changes.

Acceptance: every state in screens 4 and 15 reachable and persisted, events merge correctly across devices.

### M9. Relay and invite page (can run in parallel with M6 to M8)

`relay/`: install steps for the chosen relay (the Iroh relay program, or the WebSocket relay from M0), config, systemd unit, firewall notes, health check and uptime alert, written as a runbook that someone who has not seen the project can follow. `site/`: static page for `/j`, `/.well-known/assetlinks.json` (filled after the first signed build), hosted with HTTPS on `joinowntally.ownspace.in`.

Acceptance: from a fresh VM, the runbook produces a working relay. Tapping an invite link on a phone with the app opens it directly, and on a phone without it shows the install page.

### M10. Hardening and release

Recovery restore test end to end, dark theme, accessibility pass (touch targets, labels, contrast), release signing, Play Store listing, privacy text that states who runs the relay and what it can see, final review of every security-relevant file.

Acceptance: a release build installs, runs on a 2 GB phone, and passes the full test suite and a manual checklist.

## Definition of done (every milestone)

- All acceptance items have an automated test, or a written manual test where hardware is needed.
- `./gradlew test lint` passes locally and in CI.
- No new network calls other than sync, no analytics, no secrets in the repo.
- `DESIGN.md` and the mockups still match behaviour. If they changed, they were updated in the same pull request.

## What the owner decides, not the builder

Choice of internet transport after M0, any change to DESIGN.md, copyright and licence matters, store listing text, and anything involving real keys or servers.
