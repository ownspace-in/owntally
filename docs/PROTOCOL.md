# OwnTally protocol: events, rules and calculations

Status: **DRAFT 2 (3 Nov 2026), waiting for owner approval**. Updated with the owner's answers of 3 Nov 2026 (listed at the end). (task T1.2 in [tasks/M1-core-domain.md](tasks/M1-core-domain.md)). No code may depend on anything here until it is approved. Behaviour comes from [DESIGN.md](DESIGN.md); where this draft adds or changes something, it is listed in "Changes and additions" at the end.

This file covers the data and rules in the pure Kotlin core. Encryption, signing, transport and storage are other milestones.

## 1. Basic types

| Type | Definition |
|---|---|
| `Money` | `Long`, paise (1 rupee = 100 paise). No floating point anywhere. |
| `PublicKey` | 32 bytes (the member's per-group key). Compared and sorted as unsigned bytes. |
| `GroupId` | 16 random bytes. |
| `EventId` | `(author: PublicKey, counter: Long)`. |
| Day | `Long`, days since 1970-01-01, for expense dates. |

Limits (proposed): group of at most 20 members; name 1 to 40 characters; phone 5 to 20 characters from digits, `+` and spaces; description 1 to 80 characters; amounts greater than 0.

## 2. Event

Every event has:

| Field | Meaning |
|---|---|
| `version` | Protocol version, 1 for now. |
| `author` | The member's `PublicKey`. One phone per member in v1, so this also identifies the device. |
| `counter` | 1 for the author's first event in this group, then +1 each time. No gaps. A phone restored from the recovery phrase continues from the highest counter its group peers have for that key (needs a sync first; M2). |
| `after` | Map of author to the highest counter of theirs that the author had seen when writing this event. Includes the author's own previous counter. |
| `time` | Wall clock milliseconds. Display only. Never used by any rule. |
| `body` | One of the bodies below. |

`EventId` = `(author, counter)`. Expenses and settlements are identified by the `EventId` of the event that created them. There are no separate ids.

### Bodies

| Body | Fields |
|---|---|
| `GroupCreated` | `groupId`, `name`, `currency` |
| `MemberJoined` | `member`, `name`, `phone` |
| `ProfileUpdated` | `name`, `phone` |
| `ExpenseAdded` | `description`, `total`, `paidBy`, `day`, `split` |
| `ExpenseDeleted` | `expense: EventId` |
| `SettlementRecorded` | `from`, `to`, `amount` |
| `SettlementConfirmed` | `settlement: EventId` |
| `PaymentDisputed` | `settlement: EventId`, `reason` (one of `NOTHING_RECEIVED`, `DIFFERENT_AMOUNT`, `NOT_CHECKED`) |
| `AdminAdded` / `AdminRemoved` | `member` |
| `OwnershipOffered` | `to` |
| `OwnershipOfferCancelled` | none |
| `OwnershipAccepted` | none |
| `MemberRemoved` | `member` |

`split` is either `Equal(participants)` or `Exact(amounts)`, where `amounts` maps each participant to a `Money` greater than 0. For `Exact`, the amounts must sum to `total`. For `Equal`, `participants` is a non-empty set. All participants must be current members when the expense is added.

## 3. Order

A log is a set of events. Two phones with the same set of events must compute the same result.

1. **Dependencies.** An event depends on its own previous counter, and on every `(author, c)` with `c <= after[author]`.
2. **Ready.** An event is applied only when all its dependencies are present. Events whose dependencies are missing are held back and do not affect state until the dependencies arrive.
3. **Order.** Apply ready events with a deterministic topological order: repeatedly take, from all ready events, the one with the smallest `(author bytes, counter)`.
4. **Duplicates.** If two different events have the same `EventId`, both are ignored with `DUPLICATE_ID`, but they still count as present for dependencies.
5. **Unknown types.** An event with an unknown body type or a newer `version` is kept in the log with no effect (`UNKNOWN_TYPE`), and counts as present for dependencies.
6. **Rules** (section 4) are checked against the state at that point in the order. An event that fails a rule stays in the log but has no effect, and is reported with a reason.

## 4. Rules

State: group, members (with name, phone, role), expenses, settlements, pending ownership offer, removed members.

Roles: `OWNER`, `ADMIN`, `MEMBER`. The owner is also an admin.

General: an event's author must be a current member, except `GroupCreated`. Reasons for ignoring an event: `NOT_MEMBER`, `NOT_ALLOWED`, `INVALID`, `UNKNOWN_TARGET`, `ALREADY_DONE`, `AFTER_REMOVAL`, `DUPLICATE_ID`, `UNKNOWN_TYPE`.

| Body | Rule |
|---|---|
| `GroupCreated` | Must be the author's event number 1 with empty `after`, and the only one in the group. The author becomes owner, admin and member (name and phone come from a `ProfileUpdated` that must be the author's event number 2, or are blank until then). A second `GroupCreated` is ignored. |
| `MemberJoined` | Author must be owner or admin (approval is authorship). `member` must not be a current member and must never have been removed. Group must have fewer than 20 members. Valid name and phone. |
| `ProfileUpdated` | Updates the author's own name and phone. Valid values. |
| `ExpenseAdded` | Any member. `paidBy` and all participants must be current members. Valid amounts and split. |
| `ExpenseDeleted` | Author is the expense's creator, or an admin, or the owner. Target must exist and not already be deleted. |
| `SettlementRecorded` | Author must be `from` or `to`. `from` and `to` are different current members. If the author is `from`, the settlement is `PAID_MARKED`. If the author is `to`, it is `CONFIRMED` immediately. |
| `SettlementConfirmed` | Author must be the settlement's `to`. Target is `PAID_MARKED` or `DISPUTED`. Becomes `CONFIRMED`. |
| `PaymentDisputed` | Author must be the settlement's `to`. Target is `PAID_MARKED`. Becomes `DISPUTED`. |
| `AdminAdded` | Owner only. Target is a current member with role `MEMBER`. |
| `AdminRemoved` | Owner only. Target has role `ADMIN`. |
| `OwnershipOffered` | Owner only. `to` is a current member other than the owner. Replaces any pending offer. |
| `OwnershipOfferCancelled` | Owner only. There is a pending offer. |
| `OwnershipAccepted` | Author must be the `to` of the pending offer. Author becomes owner, and the previous owner becomes admin. The offer is cleared. |
| `MemberRemoved` | Owner may remove any current member except themselves. An admin may remove a current `MEMBER` only. A `MEMBER` may remove themselves (leave). The owner cannot be removed and cannot leave while owner. After this, the member is removed and can never rejoin with the same key. |

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
6. **Admins cannot remove other admins.** Only the owner can. DESIGN.md says "owner or any admin removes members"; this draft reads that as members, not admins.
7. **Group size is limited to 20**, matching the relay spike's room limit.
8. **`PaymentDisputed` has fixed reasons** (matching the three choices in the mockup). Free text is not stored.
9. **Admin takeover when the owner is unreachable is not included** (owner decision).
10. **`GroupCreated` carries the group id and name**, and the creator's name and phone come from a second event.
11. **Exact splits on removal differ from DESIGN.md.** DESIGN.md said the others' shares stay as entered. The owner chose on 3 Nov 2026 to re-split the removed share among the remaining participants, so the payer is repaid in full. DESIGN.md is updated.
12. **Equal-split rounding rotates.** The leftover paise start at position `counter mod n`, not always at the lowest key. No hashing needed.
13. **Payments count at once** (section 5), owner decision of 3 Nov 2026.
14. **One phone per member in v1.** The author key stands in for the device id used in DESIGN.md.
15. **Added:** a `version` field on every event, `DUPLICATE_ID` and `UNKNOWN_TYPE` handling (section 3).

## Open questions for the owner

1. Is it right that admins cannot remove other admins (change 6)?
2. Should a member who left, or was removed, be able to see the group's history afterwards? This draft says the removed phone keeps what it already had and gets nothing new (the key rotates in M2).
3. Is the group limit of 20 acceptable for v1?
