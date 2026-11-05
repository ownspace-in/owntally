# M5 tasks: sync protocol over a fake transport

Parent: [BUILD_PLAN.md](../BUILD_PLAN.md), milestone M5. Rules: [DESIGN.md](../DESIGN.md) ("Data model", "Sync transports", "Remove member"), [PROTOCOL.md](../PROTOCOL.md) (sections 3 and 4), [WIRE_FORMAT.md](../WIRE_FORMAT.md), [CRYPTO.md](../CRYPTO.md). Tests first. The real transports (Nearby, Iroh, WebSocket relay) and joining by ticket are M6 and M7; **M5 uses only the in-memory fake network.**

Goal (BUILD_PLAN): a `Transport` interface and the protocol on top of it: exchange version summaries, send only the missing events, verify and apply, idempotent, resumable. Tested with an in-memory transport and 3 to 5 simulated devices with random delays, drops and reordering. Acceptance: simulated devices always converge to identical logs and balances; interrupted syncs resume; events with bad signatures are dropped and reported. Also (from the M4 plan, D4): a group the person has left is deleted, keys included, once the leave has reached another member.

**This file is a draft: nothing below is built until the owner approves it** (section "Decisions needed").

## Progress

| Task | Status |
|---|---|
| T5.1 plan and decisions | In progress (this file). |
| T5.2 to T5.12 | Not started. |

## What already exists (so it is not rebuilt)

- **`app/sync`:** the `Transport` and `Link` interfaces, `GroupRoom` (an opaque room name from the group id), `InMemoryNetwork`, the `Chunker`, a WebSocket transport and probe (M0 spike code). It has its own small `PublicKey` class and no dependency on `core-model` yet.
- **Core:** `EventLog.summary()` (per author, the highest counter with no gap below it), `missingFor(summary)`, `conflictedIds()` (two different events with one id), deterministic replay, and the rule that a conflicted id has no effect but still counts as present.
- **Envelopes:** `author`, `counter`, `groupId` and `epoch` are readable without any key; everything else is encrypted. A phone can therefore store and pass on envelopes it cannot read (an epoch whose key it lacks). `GroupRepository.appendEnvelope` already checks group, size (64 KB) and signature, and stores identical bytes once.
- **`GroupSession`:** reads stored envelopes, keeps unreadable ones ("waiting for key" and "waiting for member"), takes keys from accepted rotation events, and (M4) the owner's phone changes the key after a leave when it refreshes. `refresh()` is the hook sync will call after storing new envelopes.

## Decisions needed

To be asked one at a time, with options and no default marked. Each answer is written here as "D(n) (date): ...".

- **S1. Two different events with the same id (a cheating or broken author).** A summary of counters cannot see them: two phones can each hold a different version of "Ben #7" and both believe they are up to date, and then the group no longer agrees (one phone ignores both, the other uses the one it has). Options: (A) accept this in v1, because it needs a cheating member, and say so; (B) add a short fingerprint of each author's events to the summary, so a mismatch is found and the two versions are exchanged, and every phone ends up ignoring both (as PROTOCOL.md says); (C) refuse to sync with a phone that shows a second version and tell the user.
- **S2. Who may connect, and what a person who left or was removed receives.** DESIGN.md says a removed person's phone shows "You were removed" the next time it syncs, but that person must not get later entries. Options: how much a removed person is sent (nothing but their own removal; everything up to their removal; nothing at all), and whether a person who left on their own may connect once to hand over their leave.
- **S3. When a phone syncs.** Options: when the app opens; after each local change (push to everyone who is reachable); every few minutes while the app is open; when the network changes. Which of these are in M5 (as functions the app can call) and which wait until M6 has real connections.
- **S4. Where a rejected event is reported.** "Dropped and reported": in a log kept on the phone, in a count on the Sync status, a message to the person, or only in tests and debug builds for now.
- **S5. The Sync tab in M5.** The tab says "arrives in a later update". Options: leave it until M6, show a real status (up to date with N members, syncing, waiting for a friend) fed by the fake network in debug builds only, or build the status model without a screen.
- **S6. A group the person has left and nobody ever received the leave.** The group stays hidden and kept until the leave reaches someone. If the person is offline for good, or the others never come back, the data stays forever. Options: keep it hidden until delivered (no way out); add a "Delete now, even though nobody has seen that I left" in settings, with a warning; or delete after a time limit.

## Tasks

### T5.2 Messages and wire format
Do: the sync messages as bytes, with a version number: `Hello` (group room, protocol version, my summary), `Request` (author and counter ranges I lack), `Envelopes` (a batch), `Done`/`Ack`, `Error`. Limits: a batch is at most 200 envelopes or 256 KB, an envelope at most 64 KB (the store's limit), a whole sync at most a set number of batches before it pauses and resumes. Unknown message types and newer versions are refused with a clear error, not a crash. Written into WIRE_FORMAT.md with test vectors from an independent encoder, as for the event format.
Done when: round-trip and invalid-input tests, vectors checked by the Python generator, an unknown version refused, a batch over the limits refused.

### T5.3 The sync engine (plain Kotlin)
Do: one sync session over one `Link`: exchange summaries, work out what each side lacks, send it in batches, verify each envelope (group, size, signature, known author) before storing it, store it only if new, answer with what was stored. Idempotent (running it twice stores nothing the second time), safe when the link drops at any point, and the engine never reads message bodies (only the fields in the clear). `sync` depends on `core-model` and `crypto`; the duplicate `PublicKey` in `app/sync` is replaced by the core's.
Done when: two stores with different histories converge; a second sync is a no-op; each of the following is tested: link closed after every possible message, a duplicate batch, a batch out of order, an envelope for another group, an oversized envelope, a bad signature (dropped, counted, the rest of the batch still stored).

### T5.4 The simulation
Do: a simulated network on virtual time (no real waiting): 3 to 5 devices, each with its own store, random delays, drops, duplicated and reordered messages, links that break, devices that go offline and come back. A random scenario writes real events through `GroupSession` (expenses, deletes, joins, removals, leaves, ownership, key changes) while syncing happens at random moments. Seeds are printed and a failing seed can be replayed.
Done when: for at least 500 seeds, after a final quiet period with full connectivity, every device has the same set of envelopes, the same state and the same balances; a failing seed reproduces exactly.

### T5.5 Same id, different event (S1)
Do: whatever S1 decides, with the decision written into PROTOCOL.md.
Done when: a test builds two versions of one event on two devices and shows what the decision promises.

### T5.6 Bad events: drop and report (S4)
Do: an envelope with a bad signature, a wrong group, an unknown author, or too large is not stored; it is counted and recorded (kind, from which peer, when) where S4 says; an unknown author is kept for later as in M2 (it may be admitted later), a bad signature is not.
Done when: tests for each kind; the report never contains the envelope's content; a peer that sends only bad data is disconnected after a limit.

### T5.7 Who may connect (S2)
Do: the access rules from S2, checked before any data is sent. A phone answers a link only from a key it knows as a member (or former member, as S2 says), and sends a removed person only what S2 says.
Done when: tests for a member, an outsider, a removed member and a leaver; an outsider gets nothing and learns nothing about the group.

### T5.8 Sync in the data layer
Do: `SyncCoordinator` on top of `Sessions`: for a group, connect to the other members, run the engine, call `GroupSession.refresh()` after new envelopes arrive (so the owner's phone changes the key after a leave, T4.11), and publish a status (idle, syncing with N, waiting for a friend, last result). Triggers as S3 decides.
Done when: two real `Sessions` on two in-memory stores, joined to one fake network, converge after a change on either side; a leave written on one phone makes the owner's phone change the key by itself on the other.

### T5.9 Leave delivered, then deleted (D4, S6)
Do: after a leave, the phone keeps syncing the hidden group until at least one other member's summary shows the leave's counter; then it deletes the group's events, keys and the "left" mark. S6 decides what happens if that never happens.
Done when: tests: hidden and kept before delivery; deleted, keys included, after delivery; a restart in between keeps it hidden and still delivers; nothing is deleted if the peer only claims to have it (the summary must come from a connection that was accepted by the rules of S2).

### T5.10 Sync status (S5)
Do: the status model for DESIGN.md "Sync states shown to the user" (up to date with N members, syncing, waiting for a friend to come online, not reachable directly, relay unreachable) and, as S5 decides, a screen.
Done when: model tests for every state; the screen (if any) compared with mockup 05.

### T5.11 Interruptions and restart
Do: a sync that stops (link drop, app killed) resumes from what is stored; nothing is half-stored; a restart loses nothing.
Done when: a test kills the engine after every stored envelope and restarts; on the emulator, a sync of a large history (for example 5,000 envelopes) between two stores is stopped and resumed and ends identical.

### T5.12 Hand back
Checklist: all tests, lint, debug and release builds and the vulnerability check pass locally and in CI; the simulation passes its seeds; every rule in DESIGN.md "Data model" and "Remove member" about syncing has a test (a table in the task notes maps each to its test); no network permission and no analytics in the release build (the sync module still has no real network use); docs match; every interpretation is listed in the pull request.

## Open questions

S1 to S6 above.
