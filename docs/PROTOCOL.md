# OwnTally protocol: events, rules and calculations

Status: **APPROVED by the owner on 3 Nov 2026** (task T1.2 in [tasks/M1-core-domain.md](tasks/M1-core-domain.md)). Incorporates the owner's answers of 3 Nov 2026 (listed at the end). Any change from here needs the owner's approval. (task T1.2 in [tasks/M1-core-domain.md](tasks/M1-core-domain.md)). No code may depend on anything here until it is approved. Behaviour comes from [DESIGN.md](DESIGN.md); where this draft adds or changes something, it is listed in "Changes and additions" at the end.

This file covers the data and rules in the pure Kotlin core. Encryption, signing, transport and storage are other milestones.

## 1. Basic types

| Type | Definition |
|---|---|
| `Money` | `Long`, paise (1 rupee = 100 paise). No floating point anywhere. |
| `PublicKey` | 32 bytes (the member's per-group key). Compared and sorted as unsigned bytes. |
| `GroupId` | 16 bytes: 8 random bytes, then the first 8 bytes of SHA-256(creator's public key + those 8 random bytes). The id proves who created the group (owner decision, 4 Nov 2026). |
| `EventId` | `(author: PublicKey, counter: Long)`. |
| Day | `Long`, days since 1970-01-01, for expense dates. |

Limits (proposed): group of at most 20 members; name 1 to 40 characters; phone 5 to 20 characters from digits, `+` and spaces; description 1 to 80 characters; amounts greater than 0.

## 2. Event

Every event has:

| Field | Meaning |
|---|---|
| `version` | Envelope version (docs/WIRE_FORMAT.md), 1 for now. |
| `author` | The member's `PublicKey`. One phone per member in v1, so this also identifies the device. |
| `counter` | 1 for the author's first event in this group, then +1 each time. No gaps. A phone restored from the recovery phrase continues from the highest counter its group peers have for that key (needs a sync first; M2). |
| `after` | Map of author to the highest counter of theirs that the author had seen when writing this event. Includes the author's own previous counter. |
| `time` | Wall clock milliseconds. Display only. Never used by any rule. |
| `body` | One of the bodies below. |

`EventId` = `(author, counter)`. Expenses and settlements are identified by the `EventId` of the event that created them. There are no separate ids.

### Bodies

| Body | Fields |
|---|---|
| `GroupCreated` | `groupId`, `name`, `currency`, `encryptionKey` |
| `MemberJoined` | `member`, `encryptionKey`, `name`, `phone` |
| `ProfileUpdated` | `name`, `phone` |
| `ExpenseAdded` | `description`, `total`, `paidBy`, `day`, `split` |
| `ExpenseDeleted` | `expense: EventId` |
| `SettlementRecorded` | `from`, `to`, `amount` |
| `SettlementConfirmed` | `settlement: EventId` |
| `PaymentDisputed` | `settlement: EventId`, `reason` (one of `NOTHING_RECEIVED`, `DIFFERENT_AMOUNT`, `NOT_CHECKED`, `DUPLICATE`) |
| `AdminAdded` / `AdminRemoved` | `member` |
| `OwnershipOffered` | `to` |
| `OwnershipOfferCancelled` | none |
| `OwnershipAccepted` | none |
| `OwnershipDeclined` | none (tag 16; M4, owner decision 5 Nov 2026) |
| `MemberRemoved` | `member`, `newEpoch`, `sealedKeys` |
| `KeyRotated` | `newEpoch`, `sealedKeys` |

`encryptionKey` is the member's X25519 public key for this group; `sealedKeys` maps each member who stays to the new group key sealed to their `encryptionKey` (docs/CRYPTO.md). `split` is either `Equal(participants)` or `Exact(amounts)`, where `amounts` maps each participant to a `Money` greater than 0. For `Exact`, the amounts must sum to `total`. For `Equal`, `participants` is a non-empty set. All participants must be current members when the expense is added.

## 3. Order

A log is a set of events. Two phones with the same set of events must compute the same result.

1. **Dependencies.** An event depends on its own previous counter, and on every `(author, c)` with `c <= after[author]`.
2. **Ready.** An event is applied only when all its dependencies are present. Events whose dependencies are missing are held back and do not affect state until the dependencies arrive.
3. **Order.** Apply ready events with a deterministic topological order: repeatedly take, from all ready events, the one with the smallest `(author bytes, counter)`.
4. **Duplicates.** If two different events have the same `EventId`, both are ignored with `DUPLICATE_ID`, but they still count as present for dependencies. A phone keeps at most the two versions of an id with the smallest hashes (WIRE_FORMAT.md section 6); the rules treat two or more alike. Counters alone cannot show that two phones hold different versions, so the sync summary carries a fingerprint of each author's events (owner decision S1, 7 Nov 2026): phones with the same counters but different fingerprints exchange every version they hold of that author's events, end with the same two, and so ignore the event everywhere.
5. **Unknown types.** An event with an unknown body type or a newer `version` is kept in the log with no effect (`UNKNOWN_TYPE`), and counts as present for dependencies.
6. **Rules** (section 4) are checked against the state at that point in the order. An event that fails a rule stays in the log but has no effect, and is reported with a reason.

## 4. Rules

State: group, members (with name, phone, role), expenses, settlements, pending ownership offer, removed members.

Roles: `OWNER`, `ADMIN`, `MEMBER`. The owner is also an admin.

**What the author had seen (owner decision S7, 7 Nov 2026).** Two things written at the same time on different phones (a leave and a key change, a join and a removal) can be applied in either order, because the order of section 3 knows nothing about who saw what. So a fact about *who is a member* or *which epoch it is* that the author wrote into an event is judged against what the **author had seen** when writing it, not against the state at the event's place in the order. An event X was seen by an event E if X's counter is at most `E.after[X's author]` (for E's own earlier events, if it is below E's counter). The *known members* of E are those whose admitting event was seen by E and whose removal (if any) was not. The *epoch seen* by E is the latest key-starting event that E saw (1 if none). Everything that is only about the state at that moment (the author's role, whether a name is free, whether a target already holds a role) is still judged in the order, and every phone gets the same answer. The effect: an event that was valid for its author stays valid on every phone, whatever concurrent event is applied first.

General: an event's author must be a current member, except `GroupCreated`. Reasons for ignoring an event: `NOT_MEMBER`, `NOT_ALLOWED`, `INVALID`, `UNKNOWN_TARGET`, `ALREADY_DONE`, `AFTER_REMOVAL`, `DUPLICATE_ID`, `UNKNOWN_TYPE`.

| Body | Rule |
|---|---|
| `GroupCreated` | The group id must have been made for the author (see `GroupId`), else `INVALID`. Must be the author's event number 1 with empty `after`, and the only one in the group. The author becomes owner, admin and member (name and phone come from a `ProfileUpdated` that must be the author's event number 2, or are blank until then). A second `GroupCreated` is ignored. |
| `MemberJoined` | Author must be owner or admin (approval is authorship). `member` must not be a current member and must never have been removed. Group must have fewer than 20 members. Valid name and phone. The `encryptionKey` must not be a low-order X25519 point (the seven encodings in `LowOrderKeys`; M10 finding F1, owner-approved 10 Nov 2026), otherwise `INVALID`: nobody can seal a group key to such a key, so one such member would make every key change fail. `GroupCreated` with such a key is `INVALID` for the same reason. |
| `ProfileUpdated` | Updates the author's own name and phone. Valid values. |
| `ExpenseAdded` | Any member. Total at most 100,000,000 whole units (10,000,000,000 paise). `paidBy` and all participants must be known members of the event (S7): someone who left at the same time is still valid here, and is then taken out of the expense by the removal rules (section 5). Valid amounts and split. |
| `ExpenseDeleted` | Author is the expense's creator or the group owner (not an admin; owner decision, 4 Nov 2026). Target must exist and not already be deleted. |
| `SettlementRecorded` | Author must be `from` or `to`. `from` and `to` are different known members of the event (S7). If the author is `from`, the settlement is `PAID_MARKED`. If the author is `to`, it is `CONFIRMED` immediately. |
| `SettlementConfirmed` | Author must be the settlement's `to`. Target is `PAID_MARKED` or `DISPUTED`. Becomes `CONFIRMED`. |
| `PaymentDisputed` | Author must be the settlement's `to`. Target is `PAID_MARKED`. Becomes `DISPUTED`. |
| `AdminAdded` | Owner only. Target is a current member with role `MEMBER`. |
| `AdminRemoved` | Owner only. Target has role `ADMIN`. |
| `OwnershipOffered` | Owner only. `to` is a current member other than the owner. Replaces any pending offer. |
| `OwnershipOfferCancelled` | Owner only. There is a pending offer. |
| `OwnershipAccepted` | Author must be the `to` of the pending offer. Author becomes owner, and the previous owner becomes admin. The offer is cleared. |
| `OwnershipDeclined` | Author must be the `to` of the pending offer (`NOT_ALLOWED` otherwise, including when there is no offer). The offer is cleared; nothing else changes. The owner can then offer ownership again. An app that does not know this event keeps it as an unknown event with no effect. |
| `MemberRemoved` | **Only the owner** removes someone else (any current member except themselves; owner decision, 4 Nov 2026). A plain `MEMBER` may remove themselves (leave). **An admin cannot remove anyone, themselves included**: the owner makes them a plain member first, or removes them. The owner cannot be removed and cannot leave while owner. After this, the member is removed and can never rejoin with the same key. The target must be a known member of the event (S7); if they have already gone (they left at the same time), the removal is still valid and still starts the new epoch, and changes nothing else. Key epoch: when someone else is removed, `newEpoch` must be the current epoch plus 1 and `sealedKeys` must hold a key for exactly the known members of the event except the target, else `INVALID`; the removal starts that epoch. A leave must carry the epoch its author had seen and no keys, else `INVALID`, and starts no epoch. |
| `KeyRotated` | **Owner only** (`NOT_ALLOWED` otherwise; owner decision, 4 Nov 2026, so two people can never start the same epoch at once). `newEpoch` must be the current epoch plus 1 and `sealedKeys` must hold a key for exactly the known members of the event (S7), else `INVALID`. Starts that epoch. This is how the owner rotates the key after a leave. If a leave, or a member admitted at the same time, was not seen by the key change, the owner's phone changes the key again (`keyChangeNeeded`, M4 D2 and M5 S7); a member admitted after the key change was seen is not a gap. **A key change the rules refuse (decision K1, 9 Nov 2026):** two phones can each believe they are the owner (an acceptance and the owner's newer offer written at the same time), and each change the key. The rules accept one; the phone whose key change was refused has a key of its own that nobody else holds, and may already have written entries under it. When such a phone learns which key change was accepted it (1) replaces its own key for that epoch by the one in the accepted event, and (2) seals each of its own entries that were under its own key again under the accepted key: **the same entry (same author, counter and content), a new envelope**, so that others can open it and the later entries that depend on it are applied. A key it made for an epoch that no accepted event started is forgotten after the same step (its entries are sealed again under the accepted current epoch). Others hold the first envelope of such an entry, which they cannot open, and the new one; an envelope that cannot be opened but whose entry is held in another version is not counted as unreadable or as waiting for a key. |

**After removal.** An event by a removed member is ignored with `AFTER_REMOVAL` if its counter is greater than `after[member]` in the `MemberRemoved` event (that is, they wrote it without having seen the removal).

## 5. Balances

Balance of a member: a positive number means the group owes them; a negative number means they owe.

For each expense that is not deleted, after applying removals (below):
- the payer's balance increases by the sum of the participants' shares;
- each participant's balance decreases by their share.

Shares:
- `Equal`: `base = total / n`, `rem = total % n` (integer division). Sort the participants by ascending `PublicKey`. Starting at position `counter mod n` (`counter` is the counter of the event that created the expense) and wrapping around, the next `rem` participants pay `base + 1`; the others pay `base`. The starting point moves from expense to expense, so the extra paise do not always fall on the same people.
- `Exact`: the stated amounts.

Settlements: for a settlement from `f` to `t` of amount `a` that is `PAID_MARKED` or `CONFIRMED`, `f` increases by `a` and `t` decreases by `a`. A payment counts as soon as it is recorded, still shown as unconfirmed (owner decision, 3 Nov 2026). `DISPUTED` settlements do not count, so a dispute puts the amount back on the plan.

Balances always sum to 0.

### Removed members

A removed member is taken out of the calculation, as if they had never been in the group:
- an expense they paid is excluded entirely;
- they are dropped from other expenses' participants: `Equal` is re-split among the remaining participants (if none remain, the expense is excluded); for `Exact`, the removed members' shares add up to an amount `r`, which is divided among the remaining participants the same way as an `Equal` split of `r` (same rounding rule), added to their entered shares; if no participant remains, the expense is excluded (owner decision, 3 Nov 2026). The payer's increase stays equal to the total, so balances still sum to 0;
- settlements involving them are excluded;
- their expenses, settlements and profile stay visible in the history, marked as excluded.

`previewRemoval(state, member)` returns, for every remaining member, the balance before and after, and lists the excluded expenses and settlements. It does not change state.

## 6. Settlement plan

Input: balances of current members. Output: a list of payments `(from, to, amount)`.

1. Take the member with the largest positive balance (the creditor) and the member with the most negative balance (the debtor). Ties go to the smaller `PublicKey`.
2. Create a payment from the debtor to the creditor of `min(credit, -debt)`. Reduce both balances by it.
3. Repeat until all balances are 0.

At most (number of members with a non-zero balance) minus 1 payments. This is not always the absolute minimum; the owner has accepted that. The same balances always produce the same list in the same order.

## 7. Encoding

Events are signed and sent as bytes (milestone M2). Proposed: deterministic CBOR (RFC 8949, section 4.2). The library will be chosen in M2. The core model works on typed objects and does not depend on the encoding.

## Changes and additions compared with DESIGN.md

1. **Ids come from events.** Expense and settlement ids are the `EventId` of their creating event, so no separate id field exists.
2. **`lastSeenCounter` is replaced by `after[member]`** in the `MemberRemoved` event. It carries the same information without an extra field.
3. **`OwnershipOfferCancelled` is added.** The ownership screen has a "Cancel request" button, which needs an event.
4. **`SettlementRecorded` can be written by either side.** The Settle screen lets the receiver tap "Mark received" without the payer marking first. `PaymentDisputed`, `SettlementConfirmed` and the statuses are as above.
5. **A removed key can never rejoin.** A person who wants back in must join with a new key and is a new member.
6. **Admins cannot remove other admins.** Only the owner can. DESIGN.md says "owner or any admin removes members"; this draft reads that as members, not admins. **Superseded on 4 Nov 2026: admins cannot remove anyone; only the owner removes members and changes the key. Admins approve joins and invite.**
7. **Group size is limited to 20**, matching the relay spike's room limit.
8. **`PaymentDisputed` has fixed reasons** (the three choices in the mockup, and `DUPLICATE`, added in M8: the receiver says the payer's entry is the same payment as one the receiver recorded; it stops counting like any dispute). Free text is not stored.
9. **Admin takeover when the owner is unreachable is not included** (owner decision).
10. **`GroupCreated` carries the group id and name**, and the creator's name and phone come from a second event.
11. **Exact splits on removal differ from DESIGN.md.** DESIGN.md said the others' shares stay as entered. The owner chose on 3 Nov 2026 to re-split the removed share among the remaining participants, so the payer is repaid in full. DESIGN.md is updated.
12. **Equal-split rounding rotates.** The leftover paise start at position `counter mod n`, not always at the lowest key. No hashing needed.
13. **Payments count at once** (section 5), owner decision of 3 Nov 2026.
14. **One phone per member in v1.** The author key stands in for the device id used in DESIGN.md.
15. **Added:** a `version` field on every event, `DUPLICATE_ID` and `UNKNOWN_TYPE` handling (section 3).
16. **Key epochs (M2, owner-approved 3 Nov 2026).** The group starts in epoch 1. `GroupCreated` and `MemberJoined` carry the member's encryption key. `MemberRemoved` carries the new epoch and the sealed keys, a leave rotates nothing, and `KeyRotated` lets an admin rotate afterwards. The state holds the current `epoch` and which event started each epoch (`epochStarts`). Because a rotation must name exactly the current members, two removals written at the same time leave only the first in the deterministic order; the other is `INVALID` and its author has to send it again.
17. **`Unknown` keeps its tag and content** so an event of a newer type can be passed on byte for byte (docs/WIRE_FORMAT.md).

## Owner answers (3 Nov 2026)

1. Admins cannot remove other admins; only the owner can (change 6). Yes.
2. A removed or departed member keeps a read-only copy of what they already had and gets nothing new (the key rotates in M2). Yes.
3. The group limit of 20 members is accepted for v1. Yes.

Also answered on 3 Nov 2026: payments count at once; exact splits are re-split on removal; one phone per member; a removed key never rejoins (changes 5, 11, 13 and 14).

## Clarifications found while implementing M1 (3 Nov 2026, for the owner to confirm)

None of these changes a decision the owner made. They fill gaps the approved text left open, and each has a test. If any is wrong, tell the builder and the code and tests change.

1. **Ready means applied, not just present (section 3).** An event waits until every event it saw has itself been applied or ignored, which in turn needs what that event saw. For honest authors this is the same thing, because an event's `after` covers everything the events it saw covered. It only matters for hand-made input. **Confirmed by the owner, 4 Nov 2026 (option A: wait until judged).**
2. **Events sharing an id wait for nothing.** Both are ignored (`DUPLICATE_ID`), so their `after` is not used. Events that depend on them still apply. **Confirmed by the owner, 4 Nov 2026 (option C): both ignored, and the app shows a notice such as "Two entries from Rohan clashed and were ignored" (built with the sync status screens, M5/M6).**
3. **`GroupCreated` must have an empty `after`.** A creator who has already seen other events is `INVALID`, and so is any counter other than 1. A second creation by someone who has seen nothing is `ALREADY_DONE`. Until `ProfileUpdated`, the owner's name and phone are empty. **Changed and confirmed by the owner, 4 Nov 2026 (security fix):** the group id must also have been made for the author (8 random bytes + 8 bytes of SHA-256 of the author's key and those bytes), otherwise the event is `INVALID`. Without this, a member holding the group key could write a rival creating event with a key that sorts first and take over the group. Because of it, "a second creation by someone who has seen nothing" is now `INVALID`, not `ALREADY_DONE` (which stays only as a safety net). A rival can still make their own, separate group with their own id.
4. **Reasons chosen where section 4 only said "ignored":** **Confirmed by the owner, 4 Nov 2026 (option A): all eight reasons as listed. The reason never changes a balance; delete is refused unless the author is the creator or the group owner, and a creating event whose group id is not the author's is `INVALID`.**
   - `MemberJoined` for a current member is `ALREADY_DONE`; for a removed key, a plain member author, or a full group it is `NOT_ALLOWED`.
   - `ExpenseAdded` naming a non-member payer or participant is `INVALID`.
   - `SettlementRecorded` by someone who is not `from` or `to` is `NOT_ALLOWED`; naming a non-member is `INVALID`.
   - A confirmation or dispute on the wrong status is `ALREADY_DONE` (except a non-receiver author, which is `NOT_ALLOWED`).
   - `AdminAdded` for a non-member is `UNKNOWN_TARGET`, and for someone already an admin `ALREADY_DONE`. `AdminRemoved` for a plain member is `ALREADY_DONE`, and for the owner `NOT_ALLOWED`.
   - `OwnershipAccepted` without a matching pending offer is `NOT_ALLOWED`. Removing the member an offer was made to cancels the offer.
5. **A removal cancels newer events even if they sort before it.** Events by the removed member with a counter above what the remover saw are `AFTER_REMOVAL`, including ones the deterministic order would have applied first. Replay does this by running again with those events left out until nothing new turns up. The removal event itself is exempt (a member leaving writes an event newer than their own `after`). **Confirmed by the owner, 4 Nov 2026 (option C): as written, and the entries that were cancelled this way are listed to the person who did the removal (and the owner), so someone can re-enter them. They become known when the removed member's late events sync, so the list can grow after the removal. Built with the membership screens (M4) and the sync work (M5).**
6. **Dropped shares and rounding.** The removed members' exact shares, added up, are divided among the remaining participants of that expense by the same rule as an equal split (start position `counter mod n` of the expense's event). If no participant remains, the expense is excluded. **Confirmed by the owner, 4 Nov 2026 (option A): the rotating rule, the same as equal splits.**
7. **Payments with a removed member are excluded in both directions**, and a payment counts unless it is `DISPUTED`. **Confirmed by the owner, 4 Nov 2026 (option A): excluded in both directions, and the remover sees these payments on the before-and-after screen and confirms.**
8. **Invalid data throws.** The event constructors reject bad values (empty names, totals of 0 or less, exact splits that do not add up, a wrong `after`) with `IllegalArgumentException`. The decoder in M2 must catch that and treat the event as invalid. The log never holds an event that broke these checks. **Confirmed by the owner, 4 Nov 2026, with a limit added (option C):** an expense total or a payment amount may be at most **100,000,000 in whole currency units, whatever the currency** (10,000,000,000 paise); anything above is invalid, and a decoder rejects it. Without a limit a member could make the sums overflow and fail on every phone. The app's typed-amount limit is the same number.
9. **Names may not be blank**; phone numbers may contain only ASCII digits, `+` and spaces. **Changed and confirmed by the owner, 4 Nov 2026:** (a) phone numbers also allow `-`, `(`, `)` and `.`, are 5 to 20 characters, and need at least 5 digits (a phone of only spaces is refused); (b) **member names are unique in a group**: a `MemberJoined` or `ProfileUpdated` whose name is already taken is `INVALID`. "The same" ignores capitals and extra or odd spaces and invisible characters ("Rohan", "rohan", " ROHAN " are one name; "Rohan V" is another); lookalike letters are not compared. A removed member's name stays reserved. A member may change the capitals of their own name. When two admins admit the same name at once, the first in the fixed order wins on every phone. (c) The admin sets the name when approving a join, and the joiner is told which name they got.
