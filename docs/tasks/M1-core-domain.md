# M1 tasks: core domain

Parent: [BUILD_PLAN.md](../BUILD_PLAN.md), milestone M1. Pure Kotlin on the JVM: no Android, no network, no database. The rules come from [DESIGN.md](../DESIGN.md). Where this file says "proposed", the builder writes it into `docs/PROTOCOL.md` and the owner approves it before any code depends on it.

Each task ends with "Done when", which must be checkable. Write the tests first.

---

## T1.1 Module and test tooling

Do:
- Create Gradle module `app/core-model/` (Kotlin JVM, no Android plugin).
- Add kotlin-test with JUnit 5, and a property-based testing library (candidate: kotest property, or jqwik).
- Add the module to CI so `./gradlew :app:core-model:test` runs on every pull request.

Done when: an empty test runs green locally and in CI.

## T1.2 Write PROTOCOL.md (proposed rules, owner approves)

Write `docs/PROTOCOL.md` using these proposals, and list anything you changed or could not decide.

Money
- Amounts are `Long` in paise (1 rupee = 100). Never use floating point.

Identity
- A member is identified by a public key (32 bytes). A group has a `GroupId` (random 16 bytes).

Event identity and causality
- Every event has: `author` (public key), `counter` (starts at 1, increases by 1 for each event by that author in that group), `after` (a version summary: for each author, the highest counter the author had seen when writing this event), `time` (wall clock, display only, never used for rules), and a typed `body`.
- Event id = (author, counter).
- Replay order is a deterministic topological order of the `after` relation. Ties are broken by author key bytes, then counter. Rules (who may do what) are checked against the state at that point in the order.

Event bodies (from DESIGN.md)
- `GroupCreated(name, currency)`: the author becomes owner and admin.
- `MemberJoined(member, name, phone, approvedBy)`; `ProfileUpdated(name, phone)`.
- `ExpenseAdded(expenseId, description, total, paidBy, date, split)`, where `split` is `Equal(participants)` or `Exact(map member to amount)`. Exact amounts must sum to `total`.
- `ExpenseDeleted(expenseId)`.
- `SettlementRecorded(settlementId, from, to, amount)`, `SettlementConfirmed(settlementId)`, `PaymentDisputed(settlementId, reason)`.
- `AdminAdded(member)`, `AdminRemoved(member)`, `OwnershipOffered(to)`, `OwnershipAccepted()`.
- `MemberRemoved(member, lastSeenCounter)`: events by that member with a counter above `lastSeenCounter` are ignored.

Authorization (checked at replay)
- Only the owner adds or removes admins and offers ownership. The new owner must accept, and the offer must come from the current owner. Admin takeover when the owner is unreachable is not part of v1. Do not build it.
- Any member adds expenses and settlements. Delete: the creator or any admin or the owner. Remove member: owner or any admin. The owner cannot be removed or leave while owner.
- An event that fails authorization is kept in the log but has no effect.

Counting rules for balances
- An expense counts unless deleted.
- A settlement counts as provisional once recorded (shown as unconfirmed), counts fully when confirmed, and stops counting if disputed. Proposed reading of DESIGN.md: "Not received" returns the payment to "Payments to make".
- Removal: the removed member is taken out of the calculation. Expenses they paid are excluded. Their share is removed from other expenses; for `Equal` splits the amount is re-split among the remaining participants, for `Exact` splits their share is dropped and the others stay as entered. Settlements involving them are excluded.

Rounding
- For `Equal`, divide in paise. The leftover paise (at most participants minus 1) go one each to participants in ascending public key order. Every device must get identical results.

Settlement plan
- Compute net balance per remaining member. Repeatedly match the member who is owed most with the member who owes most (ties broken by public key order), pay `min` of the two, until all are zero. This gives at most (members minus 1) payments. It is not always the absolute minimum. The owner has accepted this; document it in PROTOCOL.md.

Encoding for signing (needed by M2)
- Proposed: deterministic CBOR (RFC 8949 section 4.2). Library to be confirmed in M2.

Done when: the owner has approved `docs/PROTOCOL.md`.

## T1.3 Basic types

Do: `Money` (paise as `Long`, with safe add and subtract, never negative where not allowed), `PublicKey`, `GroupId`, `EventId`, `Member`, `Role` (OWNER, ADMIN, MEMBER).

Done when: tests cover Money arithmetic, equality and ordering of keys.

## T1.4 Event model

Do: a sealed class `EventBody` with the bodies above and an `Event` class with `author`, `counter`, `after`, `time`, `body`. Immutable.

Done when: tests construct each event type, and reject invalid ones (negative total, exact split not summing to total, empty participants).

## T1.5 The log

Do: `EventLog` with `append(event)` (ignore duplicates, accept any order), `summary()` (author to highest counter, counting only gap-free prefixes), `missingFor(otherSummary)` (events the other side lacks), `merge(otherEvents)`.

Done when:
- Appending the same event twice changes nothing.
- Merging two logs in either order gives equal logs (property test).
- `missingFor` followed by `merge` makes two logs equal.
- An event whose earlier counters from the same author are missing is held back until they arrive.

## T1.6 Replay to state

Do: `replay(log): GroupState` applying the deterministic order and the authorization rules, producing members with roles, expenses (with deleted flag), settlements with status, admins, owner, and a list of ignored events with reasons.

Done when:
- Replaying the same events in any arrival order gives identical state (property test: shuffle 1,000 random event sets).
- Every authorization rule in T1.2 has a test that shows both allowed and rejected.
- Events from a removed member beyond `lastSeenCounter` are ignored.
- The owner cannot be removed.

## T1.7 Splits and balances

Do: `balances(state): Map<PublicKey, Money>` (positive means owed to the member). Implement equal and exact splits, deletion, rounding, settlements (provisional, confirmed, disputed).

Done when:
- Balances always sum to zero (property test).
- Equal splits of 10,000 paise among 3 give 3,334, 3,333 and 3,333 to the lowest keys first, on any device.
- A deleted expense disappears from balances but stays in history.
- A disputed settlement stops counting; a confirmed one counts.

## T1.8 Member removal rebalance

Do: apply the removal rules from T1.2 inside `balances`, and provide `previewRemoval(state, member): List<BeforeAfter>` that returns each remaining member's balance before and after, plus the list of excluded expenses and settlements (this feeds the Remove screen).

Done when:
- Write a fixture of your own with 5 members (one of them settled with everyone) whose before and after balances you can state by hand; the numbers in `design/13-remove-member.png` are illustrative, so you do not need to match them.
- The after balances sum to zero.
- Removing a member who has paid expenses and has settlements behaves as T1.2 says, in a test for each case.

## T1.9 Settlement plan

Do: `settlementPlan(balances): List<Payment>` using the greedy rule in T1.2.

Done when:
- Applying the payments clears every balance (property test over random groups of 2 to 30 members).
- At most (members minus 1) payments.
- Same input always gives the same payments in the same order.
- Members with zero balance never appear.

## T1.10 Fixtures and end to end test

Do: put JSON fixtures in `app/core-model/src/test/resources/` (a trip with 5 members, deletions, settlements, a removal). Write one test that loads each fixture, replays it, and asserts the balances and plan.

Done when: the fixtures exist and pass. These double as test vectors for other languages later.

## T1.11 Hand back

Checklist before asking for review:
- `./gradlew :app:core-model:test` passes, and CI is green.
- No Android or network imports anywhere in the module (add a test or a Gradle check that fails if one appears).
- `docs/PROTOCOL.md` is approved by the owner and matches the code.
- Any rule in DESIGN.md that you could not implement or had to interpret is listed in the pull request description.
