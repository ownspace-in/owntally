# M11 tasks: restoring from the recovery phrase

Parent: [BUILD_PLAN.md](../BUILD_PLAN.md), milestone M11. Rules: [DESIGN.md](../DESIGN.md) "Recovery", [CRYPTO.md](../CRYPTO.md) sections 2, 6 and 7, [PROTOCOL.md](../PROTOCOL.md), [WIRE_FORMAT.md](../WIRE_FORMAT.md). Tests first. Decision X1 (9 Nov 2026) put restore here.

Goal (BUILD_PLAN): enter the 12 words on a new phone and get the same identity back; for each group, a friend's invite brings the phone back in as the same member with no new approval, and the history syncs. Acceptance: a phone that was wiped and restored from the phrase rejoins every group it was in without an admin's approval and **ends with the same balances**; tests with a wrong phrase and with another person's phrase.

## What already exists (so it is not rebuilt)

- The seed and every group key pair come from the phrase and a numbered slot (`KeyDerivation.groupIdentity`); `IdentityRepository.completeFirstRun(phrase, name, phone)` stores a seed from any valid phrase (the M6 tests already restore a phone this way).
- `JoinGroup.findMySlot(ticket, maxSlots = 16)`: asks the invitation's host, one connection per slot in order, which slot's key is already a member (the host answers a status question only to the holder of that key). Tested in `JoinGroupTest`.
- The host side: `GroupJoinBackend.statusOf` answers `APPROVED` for a current member; `JoinRequests.receive` ignores someone who is already a member ("needs no approval", DESIGN.md).
- Words are checked by `Bip39.check` with a `PhraseProblem` that never holds the words.

Not built: the restore screens, the joining path that uses `findMySlot`, protection against reusing slots, and (found in planning, below) a way to read earlier epochs.

## Finding P1 (found 11 Nov 2026, before any code): a member who joins after a key change cannot read the group

An invitation carries the key of the epoch it was made in, and a key change (`MemberRemoved`, `KeyRotated`) seals only the **new** key, to the members of that moment. So nobody who arrives later holds the keys of the epochs before the one in their invitation. A probe (a group with an expense, then `rotateKey`, then a new member through the real invitation, approval and sync) gave: **0 expenses, 0 members, epoch 0, "waiting for key: epoch 1"**. The group's creating event is in epoch 1, so the whole state stays empty: the joiner cannot even see the group's name. The same holds for a restored phone: it can recover the keys sealed to its key (epochs in which it was a member), but never epoch 1, nor any epoch before it was admitted.

This is not new in M11: it affects every group that ever had a removal or a key change, and nothing in M1 to M10 tests it (`KeyCoverage` says an admitted member "gets the key with the history", which holds only for the current epoch). It has to be fixed for the M11 acceptance ("ends with the same balances") and it is a real defect for ordinary joins already.

## Decisions needed (owner)

**D1. How do later members get the earlier keys?**

- **A (recommended): key chaining.** Every key change also carries the *previous* key sealed under the *new* key (`AEAD(newKey, oldKey)`, bound to group and epoch). Anyone who holds the newest key can unwrap back to epoch 1; nobody gains a key they did not already have access to by being in the group (a removed member holds the old keys anyway and never gets the new one). Needs: a field in `MemberRemoved` and `KeyRotated` (WIRE_FORMAT.md, PROTOCOL.md, CRYPTO.md changes), a replay rule that the field is present and well-formed, and the unwrap in `GroupSession`. Groups made before this change do not have it (no release exists yet, so nothing to migrate).
- **B: the invitation carries every key.** The ticket holds all epoch keys (32 bytes each), so the link and QR code grow with the number of key changes, and the restored phone gets them the same way. No wire change to events, but a ticket format change and long links.
- **C: new members see history only from their epoch.** Not possible as the log is built (the creating event is in epoch 1), so it would need a snapshot design. Not recommended.

**D2. How does a restored phone find the slots it used, and avoid reusing them?**

Slots are numbered 0, 1, 2, ... by a counter that a restored phone starts again from 0, so its next new group would reuse the key of an old group (the same member key in two groups; the unlinkability the design promises is lost, and the restored group cannot be given its own slot).

- **A (recommended): a window with one question.** A restored phone keeps slots 0 to 63 for finding old groups and starts new groups at 64; the join asks the host once, with proofs for all 64 keys in one message (a small addition to the join messages), instead of 64 connections. Limit: a person who had more than 64 groups, or restores twice, would not find all of them (the window can be raised by a later update).
- **B: derive each group's key from the group id, not from a slot.** No search, no limit, no collisions, nothing to remember. A change to key derivation (CRYPTO.md section 2) and to how a group is created (the creator's group id would be made from a random part that the key is derived from). Bigger and touches M2, M3 and M6 code and tests; cleaner for good.
- **C: keep the current search (16 slots, one connection each) and the counter from 0.** Smallest change; slow for people who are not members, still reuses slots on a restored phone. Not recommended.

## Tasks (written for D1 = A and D2 = A; they change with the answers)

- **T11.1 Key chaining.** WIRE_FORMAT.md, PROTOCOL.md and CRYPTO.md first; then tests (the probe above as a real test: a member who joins after a key change reads the whole history and gets the same balances; a removed member still cannot read the new epoch; a forged or altered wrap is ignored), then `EventBody`, the codec, replay, `KeyRotation`, `GroupSession`. The test vectors of WIRE_FORMAT.md are regenerated.
- **T11.2 Slot window.** `IdentityRepository` after a restore: the counter starts at 64; the batch status message and its answer in the join messages (own labels, proofs for up to 64 keys, the host answers only for keys whose proofs hold); `findMySlot` uses it.
- **T11.3 Restoring the identity.** `FirstRun`/`IdentityRepository`: a restore that takes 12 words, checks them (`PhraseProblem`), derives the seed and stores it, refuses if an identity already exists, and keeps the default name and phone for new joins (asked after the words). The phrase is never stored or logged.
- **T11.4 Rejoining.** `JoinGroup.open` on a restored phone: if `findMySlot` finds a slot the group is joined directly with that slot, the invitation's key is kept, no request is sent, and the sync starts with the host; the host does not use up the invitation for a member it recognises (or does, as decided in the tests); a phone that is not a member goes the normal way.
- **T11.5 Screens.** A "Restore" path on the welcome screen; the 12 words (one field per word or one field, as the mockups allow; checks on each word, a message from `PhraseProblem` that never repeats the words); name and number; then an empty Groups screen that says to ask a friend for an invitation. Both themes, large text and the accessibility checks.
- **T11.6 Texts.** Reverse the X1 sentences: the recovery-phrase screen, How it works, the unreadable-identity screen, About, `PrivacyFacts` (and so the web page and the store draft), DESIGN.md "Recovery"; `PromisesTest` is updated so the new statements are the ones that must stay true.
- **T11.7 End to end.** In the simulation: a phone with three groups (one after a key change, one where it was admin, one where it left and rejoined) is wiped, restored from the phrase and rejoins each through an invitation with no approval; the balances equal the old phone's; a wrong phrase and another person's phrase get nothing. On the emulator: the screens, from the welcome screen to a rejoined group.
- **T11.8 Hand back.** All tests, lint, the release build and bundle, the vulnerability check, the shrunk-build suite and the smoke test (extended with the restore screens), the owner's checklist (a real wipe and restore on a phone), the pull request.

## Open questions

D1 and D2 above. Nothing else is blocking.
