# M4 tasks: membership, roles and ownership (still local)

Parent: [BUILD_PLAN.md](../BUILD_PLAN.md), milestone M4. Rules: [DESIGN.md](../DESIGN.md) ("Flows"), [PROTOCOL.md](../PROTOCOL.md), [CRYPTO.md](../CRYPTO.md). Mockups: [11](../../design/11-join-requests.png), [12](../../design/12-members.png), [13](../../design/13-remove-member.png), [14](../../design/14-ownership.png), [14b](../../design/14b-ownership-confirm.png), [14c](../../design/14c-ownership-sent.png). Tests first. No network code (sync is M5 to M7).

Goal (BUILD_PLAN): members, owner, admins, join requests, approve or decline, remove with the before and after screen, ownership transfer with acceptance, profile updates (name and phone only). Rules are enforced in the core, not the UI.

**This file is a draft: nothing below is built until the owner approves it** (section "Decisions needed").

## Progress

| Task | Status |
|---|---|
| T4.1 plan and decisions | Drafted (5 Nov 2026). D1 to D4, D5a and D5b (where) decided; D5c, D6 and D7 still open. |
| T4.2 to T4.14 | Not started. |

## What already exists (so it is not rebuilt)

- **Core rules (M1, and the decisions of 3 and 4 Nov):** `MemberJoined`, `MemberRemoved`, `KeyRotated`, `AdminAdded`, `AdminRemoved`, `OwnershipOffered`, `OwnershipOfferCancelled`, `OwnershipAccepted`, `ProfileUpdated`, with every permission rule and a test for each. Only the owner removes members and changes the key; admins approve joins; names are unique per group; a member may leave, an admin may not; the owner cannot be removed or leave.
- **`previewRemoval(state, member)`** (core): each remaining member's balance before and after, and the expenses and payments that stop counting. Mockup 13 is this, drawn.
- **Crypto:** `KeyRotation.seal`, `open` and `receive` (the sender is part of the sealed key's context).
- **Data layer:** `GroupSession.append` (events are checked by the core's rules before they are stored) and `Sessions`.

So M4 is mostly the data-layer operations, a small local store for join requests, a few core helpers, and the screens.

## Differences from the mockups (already settled by earlier decisions)

1. **Role descriptions on mockup 12 are out of date.** New text: *Owner: adds and removes admins, removes members, changes the group key, transfers ownership. Admin: approves joins and invites. Member: adds expenses, deletes their own.* (The mockup says admins remove members, delete any entry and change the relay.)
2. **Mockup 14b** says Rohan "will be able to add and remove admins and transfer ownership"; it also gains "remove members and change the group key".
3. **The invite icon (top right of mockup 12) and "Open invites" (mockup 11)** belong to M6 (tickets). They are left out until then: no controls that do nothing.
4. **The "…" menu on a member** appears only for the owner. An admin or a plain member has nothing to do to other people, so no menu is shown. The "Join requests" row is shown to the owner and admins.
5. **Mockup 14 for a plain member** offers "Make admin" in place of "Remove admin role". The owner sees "Make owner", "Make admin" or "Remove admin role", and "Remove from group".
6. **Names:** the admin sets the member's name when approving a join (decided 4 Nov); the name field is on the approval, with a live "name already taken" check.

## Decisions made

- **D1 (5 Nov 2026): how the person offered ownership answers.** A **banner at the top of that group's screen** ("Asha wants to make you the owner of Goa Trip") with **Accept** and **Decline**; a small **tag on the group's card in the Groups list** so it is seen without opening the group; and a **real Decline**: a new event, `OwnershipDeclined`, so the owner sees "Rohan declined". Accepting asks for a confirmation ("Become the owner of Goa Trip? You will be able to remove members, change the group key, and add and remove admins. Asha will become an admin."). Consequences:
  - **Protocol change:** `OwnershipDeclined` takes the next free tag, **16**, with empty content. Only the offered person may write it (`NOT_ALLOWED` otherwise); it clears the pending offer. An old app that does not know tag 16 keeps it as an unknown event with no effect, so it never breaks. Needs PROTOCOL.md (rules and events table), WIRE_FORMAT.md (tag table, and "tags 1 to 16 are known"), the codec, replay, new wire vectors from the separate Python generator, and tests. Tests and vectors that used tag 16 as their example of an unknown tag use 17 instead.
  - **Groups list:** the summary gets "an ownership offer is waiting for me" so the card shows the tag.
  - Owner's side as in mockups 14b and 14c; after a decline the waiting screen says "Rohan declined" with a way to offer it to someone else.

- **D2 (5 Nov 2026): the group key after a member leaves.** **Automatic and silent.** When the owner's phone sees a leave that the current key does not yet cover, it writes `KeyRotated` by itself (new key sealed to the members who stay; the leaver gets none). No prompt, no notice. Details:
  - "Covered" means the event that started the current epoch was written after the leave was seen (its `after` includes the leave). A removal already changes the key, so it needs nothing more.
  - Only the owner's phone does it; nobody else is asked. If the owner's phone is not running, it happens at its next start. If a later leave reaches the owner after a rotation, another rotation follows.
  - It must not loop: after the rotation the leave is covered, so a further refresh does nothing.
  - Cost the owner accepted: while the owner is away the leaver can still read new entries; every leave costs one small event.

- **D3 (5 Nov 2026): where a person edits their own name and phone.** **Both** places open the same screen, "Your name and phone in Goa Trip": tap the **"You" row on the Members screen**, or tap the **"You are the owner / an admin / a member" line under the group name on the Group screen**. It is per group (the first-run name and phone are only starting values; changing them in one group changes nothing in another). Saving writes `ProfileUpdated`; old entries then show the new name to everyone; a taken name gives "That name is already taken in this group" (changing only the capitals of your own name is fine); the phone follows the rule of 4 Nov (digits, `+`, spaces, `-`, `(`, `)`, `.`, at least 5 digits).

- **D4 (5 Nov 2026): leaving a group.**
  - **Where:** both a red **Leave group** row under the list on the Members screen and a red **Leave group** at the bottom of the profile screen (D3). It opens "Leave Goa Trip?" with the same before-and-after table as Remove member (the leave changes everyone's balances in the same way) and a confirm button.
  - **Who can leave:** a plain member. An admin sees "Ask the owner to make you a member first, or to remove you"; the owner sees "Hand over ownership first".
  - **What the leaver sees afterwards:** the group **disappears at once** from the Groups list, the Settle tab and the totals, and cannot be opened. **The phone keeps the stored, encrypted data hidden until the leave has reached at least one other member**, then deletes everything, keys included. Reason: the leave is one event that exists only on the leaver's phone until it syncs; deleting it at once would mean the others never learn the person left (they would stay a member, still counted in balances, and the owner's automatic key change in D2 would never happen). Delivery and the final delete are an **M5 task**; in M4 the group is only hidden. The hidden group is marked with a setting on this phone (no database change).
  - **A decision that departs from DESIGN.md**, which said a departed or removed member keeps a read-only copy. It now applies to people **removed by the owner** only, and that case waits for M5 when sync can tell their phone.
  - A person who left cannot rejoin with the same key (a new member with a new key; their old name stays reserved).

- **Entries of removed or departed people (5 Nov 2026): kept in the list.** Expenses they paid, and payments involving them, stay visible on the Group screen, greyed and not counted, with the reason: **"Excluded: Kabir was removed"** or **"Excluded: Kabir left"**. They are never erased (the log is signed and append-only). Expenses that others paid and that include them are not "their entries": those still count, re-split. **This also fixes a fault in the M3 Group screen**, which shows any uncounted expense as "Deleted · not counted", struck through; an expense excluded because its payer left or was removed is not deleted.

- **D5a (5 Nov 2026): the Members screen lists people who were removed or left.** A greyed **"Removed or left"** section below the current members, with "Removed" or "Left" beside each name (their names stay reserved). Tapping a name does nothing yet beyond what D5b decides.

- **D5b (5 Nov 2026): where the owner finds the cancelled late entries.** **Only from the "Removed or left" section** on the Members screen (D5a): tap the removed person and see "Late entries not counted: 2" and the list (description, amount and payer for expenses; other cancelled events as counts). **No note on the Group screen.** Owner only. The list can grow later, as late events sync in after the removal.

## Decisions needed

Each needs an answer from the owner before building (none has a mockup, or the mockup is silent).

- **D5c. Whether each cancelled expense has an "Add again" button** that opens the Add expense form already filled in.
- **D6. Join requests before M6.** Real requests arrive over a transport in M6. In M4 the screen and the store exist, a debug build can add a pretend request, and release builds show an empty list.
- **D7. How many pretend members the debug tool may add** (M3 limited it to three in total until M4).

## Tasks

Every task is tests first, with the mutation check used in M3 (put the old behaviour back, a test must fail). Core and data tasks run on the JVM; screens are also checked on the emulator against the mockups.

### T4.2 Core helpers (pure Kotlin)
Do: (0) **the new event `OwnershipDeclined`** (tag 16, empty; offered person only; clears the offer; D1) in `EventBody`, the codec, replay, vectors and tests; (a) `isNameTaken(state, name, except)`: one public function that replay and the screens both use, so the "name already taken" message can never disagree with the rule; (b) `cancelledEntries(log, state)`: the removed members' late events that replay cancelled (expense description, amount, payer; payments and other events as counts), per removed member; (c) `GroupState` helpers the screens need (who can do what: `canApproveJoins`, `canRemove`, `canTransfer`, derived from the role, never copied into the UI).
Done when: `OwnershipDeclined` is accepted from the offered person only, refused from anyone else or with no offer, clears the offer, survives encode and decode byte for byte, and an old decoder keeps it as an unknown event; tests for each helper, including the same name with capitals, spaces and a removed member's name; cancelled entries listed after a removal with a late event; permissions table tested for owner, admin and member.

### T4.3 Group operations in the data layer
Do: on `GroupSession`: `approveJoin(request, name)`, `removeMember(member)` (new key made, sealed to the members who stay, **saved on the phone before the event is written and deleted again if the rules refuse the event**), `leave()`, `rotateKey()`, `makeAdmin`/`removeAdmin`, `offerOwnership(to)`, `cancelOffer()`, `acceptOwnership()`, `updateProfile(name, phone)`. Each returns what the core said (`Added` or the refusal reason) so the screen can say why.
Done when: tests with several identities (owner, admin, member, outsider): each operation works for who may and is refused for who may not, a refused removal leaves no key behind, the new key opens only for the members who stay, a leave carries no key, the owner can offer, cancel and (as the target) accept ownership, and a name clash is refused with the reason.

### T4.4 Join requests (local store)
Do: a `join_requests` table in the database (new schema version with a migration and a migration test): group, the requester's member key and encryption key, name, phone, time received, state (waiting, approved, declined). A repository. Decline marks it declined on this phone (nothing is sent). Approve writes `MemberJoined` (T4.3) and marks it approved.
Done when: tests: create, list waiting, approve (event written, name set by the admin, request marked), decline, the same requester twice, 20-member limit refused with a clear reason, the migration keeps the existing groups and events (checked on the emulator against a database made by the M3 build).

### T4.5 Members screen (mockup 12) and models
Do: list with role badges, phone as entered, "You" first, the "Join requests" row with a count, the corrected role text; the "…" menu for the owner only; a "Removed" section (D5). Pure models with JVM tests for what each role sees.
Also here: the Group screen's expense rows say "Excluded: Kabir was removed" or "Excluded: Kabir left" (greyed, not struck through) for expenses that stop counting because of a removal or leave, and keep "Deleted by Rohan · not counted" for real deletes; payments are not listed on this screen today, so their reason is shown where payments are shown (Settle up, M8).
Done when: model tests for owner, admin, member views; model tests for the three kinds of uncounted expense (deleted, payer removed, payer left); the screen compared with mockup 12 on the emulator; reachable from the Group screen's members icon.

### T4.6 Member actions (mockup 14)
Do: the screen for one member: Make owner, Make admin or Remove admin role, Remove from group, by who is looking and who is looked at (the owner cannot act on themselves).
Done when: model tests for every combination of viewer and target; compared with mockup 14.

### T4.7 Remove member (mockup 13)
Do: the before and after table from `previewRemoval`, the "Also left out of the group" box (counts of expenses, shares and payments, with the payments listed by who paid whom), the confirmation, and the result. Uses T4.3 `removeMember`.
Done when: the table equals `previewRemoval` row for row; the payment line names who will owe again; removal writes the event and the new key; compared with mockup 13.

### T4.8 Ownership (mockups 14b and 14c, and D1)
Do: "Make Rohan the owner?" with the corrected text and **Send request**; the waiting screen with **Cancel request** (and "Rohan declined" after a decline); for the person offered, the banner with **Accept** and **Decline** on the group screen and the tag on the group's card in the Groups list (D1).
Done when: offer, cancel, decline, accept and the role change (the old owner becomes an admin) on two identities; the banner and the tag appear only for the offered person and go away when the offer ends; the screens follow the mockups.

### T4.9 Join requests screen (mockup 11)
Do: waiting requests with Decline and Approve; Approve opens the name field (filled with the requested name, live "already taken" check, 20-member message); the "Open invites" section is left out until M6.
Done when: model tests; approving with a clashing name is blocked; compared with mockup 11.

### T4.10 Profile and leave (D3, D4)
Do: **Leave group** from both places (D4) with the before-and-after table, the messages for an admin and the owner, and the group hidden at once afterwards; edit my name and phone from both entry points (the "You" row on Members and the role line on the Group screen; the group's unique-name rule shown as a message); Leave group with a warning when the balance is not settled, then a read-only "You left" state on this phone.
Done when: tests for the rename clash, a phone with the allowed characters, the leave table equals `previewRemoval` for the leaver, an admin and the owner are told why they cannot leave, a leaver's group no longer appears in the summaries (list, settle tab, totals) but its stored data is kept, and a leaver cannot add entries.

### T4.11 The key after a leave (D2)
Do: a step the owner's phone runs after each refresh (`Sessions`, only for groups where this phone is the owner): if a leave is not covered by the current epoch, write `KeyRotated` (T4.3 `rotateKey`), silently.
Done when: tests: after a leave the owner's session writes the rotation once and a further refresh writes nothing; the new epoch's key is sealed to exactly the members who stay and the leaver cannot open entries written after it; a non-owner never rotates; a removal needs no extra rotation; two leaves seen together give one rotation, a leave arriving later gives another; a new owner's phone takes over the duty after a transfer.

### T4.12 Cancelled late entries (D5b, D5c)
Do: a screen for the owner listing the removed member's late entries that were cancelled, reached only by tapping the person in the "Removed or left" section (no note on the Group screen), so someone can re-enter them; "Add again" as decided in D5c.
Done when: model tests; the list grows when a late event syncs after the removal (tested with the session).

### T4.13 Debug tools (D6, D7)
Do: debug builds only: add a pretend join request, and act as a pretend member (accept an ownership offer, write an event), so every screen above can be tried before M6. Release builds contain none of it.
Done when: the release build has no trace of them (the existing manifest and class checks), and each screen was exercised on the emulator through them.

### T4.14 Hand back
Checklist: every rule in DESIGN.md "Flows" has a test (a table in the task notes maps each rule to its test); `./gradlew` tests, lint, debug and release builds and the vulnerability check pass locally and in CI; the data survives a restart (add a member, remove one, rotate the key, kill and reopen); no secret in logs; the release build has no network permission; docs match; every interpretation is listed in the pull request.
