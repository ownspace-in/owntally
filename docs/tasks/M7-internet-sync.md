# M7 tasks: internet sync, the relay and the status chips

Parent: [BUILD_PLAN.md](../BUILD_PLAN.md), milestone M7. Rules: [DESIGN.md](../DESIGN.md) ("Sync transports (ladder)", "Relay", "Can't reach a member", "Sync states shown to the user"), [WIRE_FORMAT.md](../WIRE_FORMAT.md) (sections 6 to 9), [CRYPTO.md](../CRYPTO.md) (7a to 7c), `relay/ws-relay/README.md`, `relay/iroh-relay/RUNBOOK.md`, [SPIKE_RESULTS.md](../SPIKE_RESULTS.md), mockups 05, 16 and 16b.

Goal (BUILD_PLAN): internet sync as chosen in M0, the relay as last resort used automatically (no setting, no admin switch) at `relay.ownspace.in`, status chips (Direct, Via relay, Offline, Needs relay), the "Can't reach a member" screen, and the retry rules: Nearby only when asked (done, M6); the internet chain staggered, Iroh first and the WebSocket relay joining after 3 s if Iroh has not connected, first to connect wins; connect 15 s, stall 30 s, back off 1, 5 and 30 minutes.

Acceptance (BUILD_PLAN): two phones on different mobile networks sync, the status shows the right chip, a pair that can reach each other only through the relay shows "Via relay", and when nothing connects the status says so instead of failing silently.

**Decisions W1, I1 and B1 are made (8 Nov 2026, below). Nothing is built until the owner approves this plan.**

## Progress

| Task | Status |
|---|---|
| T7.1 plan and decisions | In progress: W1, I1 and B1 decided; open questions below. |
| T7.2 to T7.10 | Not started. |

## What already exists (so it is not rebuilt)

- **Sync engine, coordinator, status model and screens** (M5), the join flow (M6), the same-network link (TLS then plain, with proofs of keys) and Nearby (M6), `SyncCoordinator.runOnce` (one pass over every group, built for the background job), retries after 1, 5 and 30 minutes, `SyncResult.via`, `PeerStatus.kind`.
- **`WebSocketTransport`** (`app/sync`, from the M0 spike, tested against the real Go relay and run on real phones: 30 of 30 messages on mobile data): one socket per group room, reconnecting with the backoff, `RelayStatus`. It is **OkHttp 5, compile-only**, because OkHttp 5 needs compile SDK 37 (see W1). **It does not prove who is at the other end:** the relay forwards by the key a client names, and `connect` succeeds as soon as the relay socket is open.
- **The relay** (`relay/ws-relay`, Go, deployed on 20 Oct 2026 at `wss://relay.ownspace.in/ws`): frames of at most 64 KB, targeted (to one key) or broadcast (to the room), no queue, 50 messages a second with a burst of 100, 20 clients a room, 30 new connections a minute per address. `relay/iroh-relay` is the Iroh relay on the same host (M0: real phones, "RELAYED at connect, DIRECT by the end of the run").
- **Iroh in the spike** (`computer.iroh:iroh-android:1.1.0`): works on real phones on mobile data (3 logged runs and the owner's reported runs), with a threading fix (its calls finish on a Rust thread: reads and writes go to `Dispatchers.IO`).

## Decisions made

- **W1 (8 Nov 2026): the WebSocket client is OkHttp 4.12 (Apache-2.0).** The last 4.x release; works with the current build tools and compile SDK 36, so no AGP 9 migration is needed. It is in maintenance mode, so Dependabot's proposal of 5.x is held back (an `ignore` for its major version, as for Kotlin), and `tools/check_vulnerabilities.py` watches it. The spike's `WebSocketTransport` is ported to the 4.x API and OkHttp becomes a normal (not compile-only) dependency of the sync module; `NetworkUseGuardTest` already allows it there.
- **I1 (8 Nov 2026): Iroh is in M7, with n0's public discovery as well as our relay.** Phones find each other through our relay (its address is in the ticket's hint and the default) and also publish and look up addresses through n0's discovery service, so they find each other even if our relay is down. **Cost the owner accepted: a third party (n0) learns each phone's key, IP address and when it is online** (the relay operator, us, already learns that for relayed links). The privacy text of M10 must say so; DESIGN.md's Relay section is updated in T7.10. The relay stays the last resort for carrying data.
- **B1 (8 Nov 2026): the background sync job is in M7.** Android's WorkManager (AndroidX, Apache-2.0) runs one pass (`runOnce`: every group, then leaves to deliver) about every 15 minutes, or as Android allows, on any network, while the app is closed. Limits to state plainly: Android picks the time, and with no mailbox the other phone must be online at about the same time, so it helps less than it sounds. It also lets a person who left a group deliver the leave (D4).

## Plan (tasks)

Each task starts with tests, as in M5 and M6. The internet cannot be tested on the emulator or in CI the way the local link was: the pieces are built behind small interfaces and tested with fakes and with the real relay program; the two-phone checks are the owner's.

- **T7.2 OkHttp 4.12 and the relay client.** Port `WebSocketTransport` to OkHttp 4.12; make it a normal dependency; Dependabot ignore for the major version; tests against the real relay program (as in M0). Done when the existing WebSocket tests pass on 4.12.
- **T7.3 A link over the relay that proves who is there.** The relay forwards by the key a client names and tells nobody who is connected, so every link starts with the same signed handshake as the plain local link (CRYPTO.md 7b, over the relay's frames) and carries a link id (several syncs with one phone at once); messages are chunked to the relay's 64 KB frames and put back together. Tests with an in-process pretend relay (and the real one): wrong key refused, replay refused, a peer that is not connected is "unreachable" after the connect wait, reconnects, the relay's rate limit is respected.
- **T7.4 Iroh behind a small interface.** `IrohPort` (bind with our relay as the home relay and n0 discovery on, dial a node, accept, streams) so the logic is tested without the native library; a link is a stream of frames. **Depends on open question 1.** The real class (`computer.iroh`) is the owner's check on two phones.
- **T7.5 The chain.** One transport for the internet: Iroh first, the WebSocket relay joins after 3 s if Iroh has not connected, the first to connect wins and the other is cancelled, 15 s to connect overall; in front of it the same-network link (a short look first, so a phone on the same Wi-Fi is not made to wait for the internet). `LinkKind` says Direct or Relayed (an Iroh link that starts relayed and becomes direct says what it was when it connected). Tests on virtual time.
- **T7.6 Status chips and "Can't reach a member".** Direct, Via relay, Offline, Needs relay (direct failed and the relay is unreachable), the per-member text of DESIGN.md "Sync states shown to the user", and the screen of mockups 16 and 16b: why, that no entries are lost (they stay on this phone and any member holding them can pass them on), and the two options, meet up (Nearby) or try again later (no "turn on the relay" option: the app decides, 4 Nov). Retry waits 1, 5 and 30 minutes (exist). Model tests and the screens compared with the mockups.
- **T7.7 Joining over the internet.** The host listens on the relay and on Iroh while its app is open (the ticket's hint names the relay; empty means the default); the joiner's transport chain reaches it; tests with the pretend relay and the fake Iroh port end to end.
- **T7.8 Background sync (B1).** A periodic WorkManager job calling the coordinator's `runOnce` and the delivery of leaves, with a network constraint; manifest permissions that WorkManager adds are listed and tested (`ManifestRulesTest`); Doze and battery notes in the doc. Tests with WorkManager's test helpers.
- **T7.9 The relay's limits against real sync.** Check the relay's limits (64 KB frames, 50 messages a second, 20 clients a room, 30 connections a minute) against a large first sync and many groups; a script that syncs a big history through the relay; any change to the relay is the owner's (server). Update the relay docs and the privacy wording.
- **T7.10 App wiring and hand back.** The chain in the app container in place of the local-only transport, the checklist of two phones on **different mobile networks** (and through the relay only), all tests, lint, builds, vulnerability check (OkHttp 4.12 and `computer.iroh`), the simulation, docs, and every interpretation listed in the pull request.

## Open questions (for the owner, before the related task)

1. **T7.4: which key does Iroh use?** Simplest: Iroh's secret key *is* the member's signing key, so a phone's Iroh node id is its member key and Iroh's own handshake proves who it is (nothing more to build). The cost: the same key signs both our events and Iroh's connections (different contexts, but one key in two protocols). The other way: a separate Iroh key derived from the seed for each group and tied to the member key by a signature, which keeps the keys apart but needs the other phone to learn the Iroh node id (another published fact) before it can dial. To be asked when T7.4 starts, with the library's API in hand.
2. **Real phones.** The mobile-network checks (two carriers, a pair that only the relay can connect) are the owner's, as the Nearby and Wi-Fi checks were.
3. **The Iroh library's own checks** (T7.4): licence, size, minimum Android version, what it does on the network by default (its probing of n0's servers) and whether it fits `NetworkUseGuardTest`'s rule, as was done for the scanner and for Nearby.
