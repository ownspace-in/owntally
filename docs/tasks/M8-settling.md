# M8 tasks: settling

Parent: [BUILD_PLAN.md](../BUILD_PLAN.md), milestone M8. Rules: [DESIGN.md](../DESIGN.md) ("Profile and settling"), [PROTOCOL.md](../PROTOCOL.md) (section 6, the plan), [WIRE_FORMAT.md](../WIRE_FORMAT.md) (events 6 to 8), mockups 04, 15 and 15b. Tests first.

Goal (BUILD_PLAN): I paid, Received, Not received and reasons, reminders through the share sheet, the unconfirmed state, plan updates after changes. Acceptance: every state in screens 4 and 15 reachable and persisted; events merge correctly across devices.

**Decisions P1 to P6 are made (9 Nov 2026, below). The plan is approved except for what the owner says about the remaining builder's choices; T8.2 starts on "next".**

## Progress

| Task | Status |
|---|---|
| T8.1 plan and decisions | Done (9 Nov 2026): P1 to P6 decided. |
| T8.2 the payment list and the wire change | Done (9 Nov 2026), see below. |
| T8.3 to T8.8 | Not started. |

## What already exists (so it is not rebuilt)

- **Events, replay and rules (M1):** `SettlementRecorded(from, to, amount)`, `SettlementConfirmed(settlement)`, `PaymentDisputed(settlement, reason)` with reasons NOTHING_RECEIVED, DIFFERENT_AMOUNT, NOT_CHECKED. Replay: a payment recorded by its payer (`from`) is `PAID_MARKED`; recorded by its receiver (`to`) it is `CONFIRMED`; only the receiver can confirm or dispute; only a `PAID_MARKED` payment can be disputed; a confirm after a dispute is allowed (so "either of you can change it later"). A payment counts toward the balances unless `DISPUTED`, and is ignored if either person was removed or left.
- **The plan (M1):** `settlementPlan(balances)`, deterministic, at most members minus 1 payments; balances already include every counted payment, so the plan shrinks as payments are marked.
- **The screen (M2):** `SettleUpScreen` shows the plan with the four buttons switched off and a note that recording arrives later. The Settle tab and the Group screen's settled state exist. `GroupSession.append` takes any event body.
- **Sync (M5 to M7):** payments are ordinary events, so they reach the other phones already.

## Decisions made

- **P1 (9 Nov 2026): the person who owes sees I paid, Copy number and Call.** Copy puts the receiver's number on the clipboard. Call opens the phone's dialer with the number (`ACTION_DIAL`, which needs no permission and does not place the call). It is the only other app OwnTally opens; DESIGN.md's "opens no other app" is changed to say so. No Pay button and no payment service. The receiver sees Send reminder and Mark received (or Confirm received and Not received on a payment already marked).
- **P2 (9 Nov 2026): the amount can be edited.** I paid and Mark received open a small amount box filled with the planned amount. A smaller payment counts at once and the plan shows the rest still to pay. The receiver who disputes with "the amount is different" can then record what they really got.
- **P3 (9 Nov 2026): a payment recorded twice is noticed, with one tap to fix.** If the payer and the receiver each recorded the same payment (same two people, same amount) before they synced, the receiver sees "This looks like the same payment recorded twice" and a button that stops the payer's entry counting (it becomes a dispute with a new reason, DUPLICATE: see the wire change below; the payer sees "Rohan recorded the same payment, so yours no longer counts"). Nothing is deleted and the balances come right at once.

- **P4 (9 Nov 2026): the wire format gains dispute reason 3, duplicate** (approved). Details below.
- **P5 (9 Nov 2026): a payment may be more than the plan says is owed.** The amount box has no cap at the planned amount (the limit of 100,000,000 whole units of the core stays). Consequence to show plainly: if Meera records ₹1,400 against a planned ₹1,350, Meera is then owed ₹50 by the group and the plan has someone who still owes pay her (not necessarily Rohan), so the plan can change; the box says "This is more than the plan asks for. The extra will show as owed back to you by the group."
- **P6 (9 Nov 2026): the reminder says the name and the amount only**, never the group name: "Hi <name>, OwnTally says you owe me <amount>. Pay however suits you, then mark it as paid in the app."

## The wire change P4 makes

`PaymentDisputed` has three reasons today (0 nothing received, 1 different amount, 2 not checked). To say "this is the same payment recorded twice" honestly, a fourth reason, **3 duplicate**, is added to WIRE_FORMAT.md, the core enum, the decoder (still strict: 0 to 3) and the test vectors (an independent Python vector as for the other events). Older phones would refuse the event as invalid, so a group's phones must all run the M8 build before anyone uses it; there are no released phones yet, so nothing is lost. Approved by the owner on 9 Nov 2026.

## Builder's choices (to confirm in the pull request)

1. **After "not received" the payment goes back to "Payments to make"** with the payer seeing the receiver's reason in words ("Rohan says he has not received anything", "Rohan says the amount is different", "Rohan has not checked his account yet"); the payer can mark it again (a new payment) and the receiver can still confirm the old one. A disputed payment does not count (core rule), so the plan shows the full amount again.
2. **The list on Settle up** has three parts: Payments to make (the plan), Waiting (marked as paid and not yet confirmed: both sides see it, the receiver can confirm or say not received), and a count of confirmed ones in the footer, as in mockup 04. Payments between two other people are shown without buttons, as today.
3. **Mockup 15's "Tell <name>"** records the dispute event; the screen says "Meera will see your answer" and the next sync carries it. If the payer's phone is offline the wording after sending is the same (it is saved on this phone, as every entry is).

## Tasks

- **T8.2 The payment list (core).** A plain-Kotlin function that turns a group state into what Settle up shows: payments to make (plan), waiting (PAID_MARKED), disputed (with reason), confirmed count, the "possible duplicate" pairs (P3), and per row what the viewer can do. Tests first: every state; a part payment leaves the rest in the plan; a disputed payment returns to the plan; duplicates found only for the same two people and the same amount; removed or left people hidden; the same list on every phone.
- **T8.3 Recording (data).** `GroupSession` calls: `markPaid`, `markReceived`, `confirm`, `dispute`, `cancelDuplicate` (a dispute with reason DUPLICATE), each checking who may do it and that the amount is positive and within the limit (P5: no cap at the plan), returning a result the screen can explain. Tests for each rule and for the events written.
- **T8.4 Settle up screen.** The buttons switched on: I paid and Mark received with the amount box (P2), Copy number, Call (P1), Confirm received, Not received, Send reminder, the duplicate notice (P3). Model tests, then emulator tests and screenshots against mockup 04.
- **T8.5 Payment not received screens.** Mockups 15 and 15b: the three reasons, Tell <name>, Cancel; what the payer sees afterwards (choice 1).
- **T8.6 Reminders.** The share-sheet text (P6), tested as text (it never contains the group name or a number).
- **T8.7 Merge and persistence.** The simulation (3 to 5 devices, delays, drops, reordering) with payments: marked, confirmed, disputed, recorded twice, a payment while a member is removed; all devices end with the same list and balances; payments survive a restart (database round trip in an emulator test).
- **T8.8 App wiring and hand back.** The Settle tab and group screen reflect unconfirmed payments, the manifest still asks for nothing new (a test: `ACTION_DIAL` needs no permission), the checklist for two phones, all checks, DESIGN.md updated (P1), the pull request with the choices listed.

## Open questions

None at the moment. The two-phone check (mark on one phone, confirm on the other, a duplicate made on purpose with one phone offline) is the owner's.

## Results

**T8.2 result (9 Nov 2026).** **Wire change (P4):** `DisputeReason.DUPLICATE` (code 3); WIRE_FORMAT.md (event 8 and the strict-decoding rule: reasons 0 to 3) and PROTOCOL.md updated; the independent Python vector generator makes a `payment-disputed-duplicate` vector and a `bad-dispute-reason-four` invalid one (the first code after the last known), and `wire-vectors.json` was regenerated from it; the Kotlin codec encodes and decodes the vector byte for byte. **The list (`settleOverview(state)`, plain Kotlin in `core-model`):** `toMake` (the plan), `waiting` (marked by the payer, not confirmed), `disputed` (with the reason), `confirmed`, and `duplicates` (a waiting payer entry paired with a confirmed entry for the same two people and the same amount; each confirmed entry excuses one waiting entry, the earliest first, so every phone finds the same pairs); payments with someone removed or left are left out; every list is in event-id order. **Tests (14):** the plan with nothing recorded; a marked payment waits and leaves the plan; a part payment leaves the rest; **a payment above the plan makes the payer owed the extra by someone who still owes** (P5: not always the receiver; the P5 text is corrected); a confirmed one is counted not listed; a receiver-recorded one is confirmed from the start; a disputed one returns to the plan with its reason; a dispute later confirmed is confirmed; a payment recorded by both sides is found as a duplicate (and the balances show why); not a duplicate with another amount, another payer or another receiver; one receiver entry excuses only one payer entry; after the duplicate dispute nothing is left to fix and the balances are right; removed people's payments are hidden; the same lists whatever order the events arrive in. **Mutations (5)** each fail a test: matching without the amount, without the receiver (found by review: a test was added), one confirmed entry excusing many, removed people not hidden, no ordering. **Not yet:** nothing in the app uses the list (T8.4).
