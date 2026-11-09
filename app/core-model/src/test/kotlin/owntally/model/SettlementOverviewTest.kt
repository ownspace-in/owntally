package owntally.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8, T8.2: what Settle up lists, from the group's state alone, the same on every phone. Ben owes Asha after Asha pays for a dinner of four. */
class SettlementOverviewTest {
    private val a = key(1); private val b = key(2); private val c = key(3); private val d = key(4)
    private fun rupees(n: Long) = Money(n * 100)

    /** Asha paid 4,000 for the four of them: Ben, Chitra and Dev each owe her 1,000. */
    private fun dinner(): Sim = standardGroup().also { it.expense(a, rupees(4_000), a, Split.Equal(setOf(a, b, c, d))) }

    @Test fun withNothingRecordedTheListIsThePlanAndNothingElse() {
        val o = settleOverview(dinner().state())
        assertEquals(listOf(Payment(b, a, rupees(1_000)), Payment(c, a, rupees(1_000)), Payment(d, a, rupees(1_000))).toSet(), o.toMake.toSet())
        assertTrue(o.waiting.isEmpty() && o.disputed.isEmpty() && o.confirmed.isEmpty() && o.duplicates.isEmpty())
    }

    @Test fun aPaymentMarkedByThePayerWaitsAndLeavesThePlan() {
        val s = dinner(); val paid = s.emit(b, EventBody.SettlementRecorded(b, a, rupees(1_000)))
        val o = settleOverview(s.state())
        assertEquals(listOf(paid.id), o.waiting.map { it.id })
        assertTrue(o.toMake.none { it.from == b }, "it counts at once, so Ben owes nothing more")
    }

    @Test fun aPartPaymentLeavesTheRestInThePlan() {
        val s = dinner(); s.emit(b, EventBody.SettlementRecorded(b, a, rupees(400)))
        val o = settleOverview(s.state())
        assertEquals(Payment(b, a, rupees(600)), o.toMake.single { it.from == b })
        assertEquals(1, o.waiting.size)
    }

    @Test fun aPaymentAbovePlanMakesThePayerSomeoneWhoIsOwedTheExtra() {
        val s = dinner(); s.emit(b, EventBody.SettlementRecorded(b, a, rupees(1_050)))
        val o = settleOverview(s.state())
        assertTrue(o.toMake.none { it.from == b }, "Ben pays nothing more")
        assertEquals(rupees(50), o.toMake.filter { it.to == b }.fold(Money.ZERO) { x, p -> x + p.amount }, "and is owed the extra 50 by someone who still owes (decision P5)")
    }

    @Test fun aConfirmedPaymentIsCountedNotListed() {
        val s = dinner(); val paid = s.emit(b, EventBody.SettlementRecorded(b, a, rupees(1_000)))
        s.emit(a, EventBody.SettlementConfirmed(paid.id))
        val o = settleOverview(s.state())
        assertEquals(listOf(paid.id), o.confirmed.map { it.id })
        assertTrue(o.waiting.isEmpty())
    }

    @Test fun aPaymentTheReceiverRecordsIsConfirmedFromTheStart() {
        val s = dinner(); val got = s.emit(a, EventBody.SettlementRecorded(b, a, rupees(1_000)))
        assertEquals(listOf(got.id), settleOverview(s.state()).confirmed.map { it.id })
    }

    @Test fun aDisputedPaymentGoesBackToThePlanAndShowsItsReason() {
        val s = dinner(); val paid = s.emit(b, EventBody.SettlementRecorded(b, a, rupees(1_000)))
        s.emit(a, EventBody.PaymentDisputed(paid.id, DisputeReason.NOTHING_RECEIVED))
        val o = settleOverview(s.state())
        assertEquals(listOf(DisputeReason.NOTHING_RECEIVED), o.disputed.map { it.disputeReason })
        assertEquals(Payment(b, a, rupees(1_000)), o.toMake.single { it.from == b }, "the full amount is due again")
        assertTrue(o.waiting.isEmpty())
    }

    @Test fun aDisputeThatIsConfirmedLaterIsConfirmed() {
        val s = dinner(); val paid = s.emit(b, EventBody.SettlementRecorded(b, a, rupees(1_000)))
        s.emit(a, EventBody.PaymentDisputed(paid.id, DisputeReason.NOT_CHECKED))
        s.emit(a, EventBody.SettlementConfirmed(paid.id))
        val o = settleOverview(s.state())
        assertEquals(listOf(paid.id), o.confirmed.map { it.id })
        assertTrue(o.disputed.isEmpty() && o.toMake.none { it.from == b })
    }

    @Test fun theSamePaymentRecordedByBothSidesIsFoundAsADuplicate() {
        val s = dinner()
        val byPayer = s.emit(b, EventBody.SettlementRecorded(b, a, rupees(1_000)))
        val byReceiver = s.emit(a, EventBody.SettlementRecorded(b, a, rupees(1_000)), seen = s.snapshot().filterKeys { it != b })
        val o = settleOverview(s.state())
        val pair = o.duplicates.single()
        assertEquals(byPayer.id, pair.payerEntry.id); assertEquals(byReceiver.id, pair.receiverEntry.id)
        assertEquals(rupees(1_000), o.toMake.filter { it.to == b }.fold(Money.ZERO) { x, p -> x + p.amount }, "counted twice, Ben is owed 1,000 he never paid in: why the notice exists")
    }

    @Test fun aDuplicateNeedsTheSamePeopleAndTheSameAmount() {
        val s = dinner()
        s.emit(b, EventBody.SettlementRecorded(b, a, rupees(1_000)))
        s.emit(a, EventBody.SettlementRecorded(b, a, rupees(900)))      // another amount
        s.emit(a, EventBody.SettlementRecorded(c, a, rupees(1_000)))    // another person
        assertTrue(settleOverview(s.state()).duplicates.isEmpty())
    }

    @Test fun aPaymentToSomeoneElseIsNotTheSamePayment() {
        val s = dinner()
        s.emit(b, EventBody.SettlementRecorded(b, c, rupees(1_000)))    // Ben pays Chitra
        s.emit(a, EventBody.SettlementRecorded(b, a, rupees(1_000)))    // Asha says Ben paid her, the same amount
        assertTrue(settleOverview(s.state()).duplicates.isEmpty())
    }

    @Test fun oneReceiverEntryMatchesOnePayerEntryOnly() {
        val s = dinner()
        val p1 = s.emit(b, EventBody.SettlementRecorded(b, a, rupees(1_000)))
        s.emit(b, EventBody.SettlementRecorded(b, a, rupees(1_000)))
        s.emit(a, EventBody.SettlementRecorded(b, a, rupees(1_000)))
        val pairs = settleOverview(s.state()).duplicates
        assertEquals(1, pairs.size, "one receiver entry cannot excuse two payer entries")
        assertEquals(p1.id, pairs.single().payerEntry.id, "the earlier entry is matched, so every phone picks the same")
    }

    @Test fun afterTheDuplicateIsDisputedItIsNoLongerAnythingToFix() {
        val s = dinner()
        val byPayer = s.emit(b, EventBody.SettlementRecorded(b, a, rupees(1_000)))
        s.emit(a, EventBody.SettlementRecorded(b, a, rupees(1_000)))
        s.emit(a, EventBody.PaymentDisputed(byPayer.id, DisputeReason.DUPLICATE))
        val o = settleOverview(s.state())
        assertTrue(o.duplicates.isEmpty())
        assertEquals(listOf(DisputeReason.DUPLICATE), o.disputed.map { it.disputeReason })
        assertTrue(o.toMake.none { it.from == b || it.to == b }, "the balances are right again: one payment counts")
    }

    @Test fun paymentsWithSomeoneWhoWasRemovedAreNotListed() {
        val s = dinner(); s.emit(b, EventBody.SettlementRecorded(b, a, rupees(1_000)))
        s.remove(a, b)
        val o = settleOverview(s.state())
        assertTrue(o.waiting.isEmpty() && o.confirmed.isEmpty() && o.disputed.isEmpty())
        assertTrue(o.toMake.none { it.from == b || it.to == b })
    }

    @Test fun everyListIsInTheOrderOfTheEventsSoEveryPhoneShowsTheSame() {
        val s = dinner()
        val p1 = s.emit(c, EventBody.SettlementRecorded(c, a, rupees(100)))
        val p2 = s.emit(b, EventBody.SettlementRecorded(b, a, rupees(100)))
        val p3 = s.emit(d, EventBody.SettlementRecorded(d, a, rupees(100)))
        val ids = settleOverview(s.state()).waiting.map { it.id }
        assertEquals(listOf(p1.id, p2.id, p3.id).sorted(), ids)
        assertEquals(settleOverview(s.state()), settleOverview(replay(logOf(s.events.reversed()))), "arrival order does not matter")
    }

    @Test fun aPaymentRemembersWhenItWasRecordedAndAConfirmationDoesNotChangeIt() {
        val s = dinner(); val paid = s.emit(b, EventBody.SettlementRecorded(b, a, rupees(1_000)))
        val at = s.events.last().time
        s.emit(a, EventBody.SettlementConfirmed(paid.id))
        assertEquals(at, s.state().settlements.getValue(paid.id).recordedAtMillis)
    }
}
