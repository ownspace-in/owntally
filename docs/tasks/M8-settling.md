# M8 tasks: settling

Parent: [BUILD_PLAN.md](../BUILD_PLAN.md), milestone M8. Rules: [DESIGN.md](../DESIGN.md) ("Profile and settling"), [PROTOCOL.md](../PROTOCOL.md) (section 6, the plan), [WIRE_FORMAT.md](../WIRE_FORMAT.md) (events 6 to 8), mockups 04, 15 and 15b. Tests first.

Goal (BUILD_PLAN): I paid, Received, Not received and reasons, reminders through the share sheet, the unconfirmed state, plan updates after changes. Acceptance: every state in screens 4 and 15 reachable and persisted; events merge correctly across devices.

**Decisions P1 to P3 are made (9 Nov 2026, below). Nothing is built until the owner approves this plan.**

## Progress

| Task | Status |
|---|---|
| T8.1 plan and decisions | In progress: P1 to P3 decided; the builder's choices below need a look. |
| T8.2 to T8.8 | Not started. |

## What already exists (so it is not rebuilt)

- **Events, replay and rules (M1):** `SettlementRecorded(from, to, amount)`, `SettlementConfirmed(settlement)`, `PaymentDisputed(settlement, reason)` with reasons NOTHING_RECEIVED, DIFFERENT_AMOUNT, NOT_CHECKED. Replay: a payment recorded by its payer (`from`) is `PAID_MARKED`; recorded by its receiver (`to`) it is `CONFIRMED`; only the receiver can confirm or dispute; only a `PAID_MARKED` payment can be disputed; a confirm after a dispute is allowed (so "either of you can change it later"). A payment counts toward the balances unless `DISPUTED`, and is ignored if either person was removed or left.
- **The plan (M1):** `settlementPlan(balances)`, deterministic, at most members minus 1 payments; balances already include every counted payment, so the plan shrinks as payments are marked.
- **The screen (M2):** `SettleUpScreen` shows the plan with the four buttons switched off and a note that recording arrives later. The Settle tab and the Group screen's settled state exist. `GroupSession.append` takes any event body.
- **Sync (M5 to M7):** payments are ordinary events, so they reach the other phones already.

## Decisions made

- **P1 (9 Nov 2026): the person who owes sees I paid, Copy number and Call.** Copy puts the receiver's number on the clipboard. Call opens the phone's dialer with the number (`ACTION_DIAL`, which needs no permission and does not place the call). It is the only other app OwnTally opens; DESIGN.md's "opens no other app" is changed to say so. No Pay button and no payment service. The receiver sees Send reminder and Mark received (or Confirm received and Not received on a payment already marked).
- **P2 (9 Nov 2026): the amount can be edited.** I paid and Mark received open a small amount box filled with the planned amount. A smaller payment counts at once and the plan shows the rest still to pay. The receiver who disputes with "the amount is different" can then record what they really got.
- **P3 (9 Nov 2026): a payment recorded twice is noticed, with one tap to fix.** If the payer and the receiver each recorded the same payment (same two people, same amount) before they synced, the receiver sees "This looks like the same payment recorded twice" and a button that stops the payer's entry counting (it becomes a dispute, reason NOT_CHECKED... see T8.4: a new reason is not added; the existing event is used). Nothing is deleted and the balances come right at once.

## Builder's choices (to confirm in the pull request)

1. **A payment cannot be more than what the plan says that person owes that person.** The amount box caps at the planned amount; at least 1 paise. Over-paying is settled outside the app. (Core accepts any amount up to the limit; the cap is in the screen and the session.)
2. **After "not received" the payment goes back to "Payments to make"** with the payer seeing the receiver's reason in words ("Rohan says he has not received anything", "Rohan says the amount is different", "Rohan has not checked his account yet"); the payer can mark it again (a new payment) and the receiver can still confirm the old one. A disputed payment does not count (core rule), so the plan shows the full amount again.
3. **Reminder text** (share sheet, the receiver sends it): "Hi <name>, OwnTally says you owe me <amount> for <group>. Pay however suits you, then mark it as paid in the app." No number, no link. The share sheet shows no app unless the person picks one.
4. **The list on Settle up** has three parts: Payments to make (the plan), Waiting (marked as paid and not yet confirmed: both sides see it, the receiver can confirm or say not received), and a count of confirmed ones in the footer, as in mockup 04. Payments between two other people are shown without buttons, as today.
5. **Mockup 15's "Tell <name>"** records the dispute event; the screen says "Meera will see your answer" and the next sync carries it. If the payer's phone is offline the wording after sending is the same (it is saved on this phone, as every entry is).

## Tasks

- **T8.2 The payment list (core).** A plain-Kotlin function that turns a group state into what Settle up shows: payments to make (plan), waiting (PAID_MARKED), disputed (with reason), confirmed count, the "possible duplicate" pairs (P3), and per row what the viewer can do. Tests first: every state; a part payment leaves the rest in the plan; a disputed payment returns to the plan; duplicates found only for the same two people and the same amount; removed or left people hidden; the same list on every phone.
- **T8.3 Recording (data).** `GroupSession` calls: `markPaid`, `markReceived`, `confirm`, `dispute`, `cancelDuplicate`, each checking who may do it and the amount cap (choice 1), returning a result the screen can explain. Tests for each rule and for the events written.
- **T8.4 Settle up screen.** The buttons switched on: I paid and Mark received with the amount box (P2), Copy number, Call (P1), Confirm received, Not received, Send reminder, the duplicate notice (P3). Model tests, then emulator tests and screenshots against mockup 04.
- **T8.5 Payment not received screens.** Mockups 15 and 15b: the three reasons, Tell <name>, Cancel; what the payer sees afterwards (choice 2).
- **T8.6 Reminders.** The share-sheet text (choice 3), tested as text.
- **T8.7 Merge and persistence.** The simulation (3 to 5 devices, delays, drops, reordering) with payments: marked, confirmed, disputed, recorded twice, a payment while a member is removed; all devices end with the same list and balances; payments survive a restart (database round trip in an emulator test).
- **T8.8 App wiring and hand back.** The Settle tab and group screen reflect unconfirmed payments, the manifest still asks for nothing new (a test: `ACTION_DIAL` needs no permission), the checklist for two phones, all checks, DESIGN.md updated (P1), the pull request with the choices listed.

## Open questions

None at the moment. The two-phone check (mark on one phone, confirm on the other, a duplicate made on purpose with one phone offline) is the owner's.

## Results

(To be written as each task is done.)
