package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.data.session.GroupSummary
import owntally.model.EventBody
import owntally.model.Money
import owntally.model.Split

/** M8, T8.8: what the Groups and Settle tabs and the Group screen say about payments that need someone's attention, and that the plan follows the expenses. */
class PaymentNoticesModelTest {
    private fun summary(waitingForMe: Int = 0, iMarked: Int = 0, toSettle: Int = 0) =
        GroupSummary(owntally.model.GroupId(ByteArray(16) { 1 }), "Goa Trip", 0, 4, 2, Money.ZERO, true, paymentsWaitingForMe = waitingForMe, paymentsIMarked = iMarked, paymentsToSettle = toSettle)

    @Test fun aPaymentWaitingForMyAnswerComesFirstAndIsInWords() {
        assertEquals("1 payment is waiting for you to confirm", GroupsModels.paymentLine(summary(waitingForMe = 1, toSettle = 3)))
        assertEquals("2 payments are waiting for you to confirm", GroupsModels.paymentLine(summary(waitingForMe = 2)))
    }

    @Test fun thenWhatIMarkedAndThenWhatIsLeftToSettle() {
        assertEquals("You marked 1 payment as paid, waiting for the other person", GroupsModels.paymentLine(summary(iMarked = 1, toSettle = 2)))
        assertEquals("You marked 2 payments as paid, waiting for the other person", GroupsModels.paymentLine(summary(iMarked = 2)))
        assertEquals("1 payment to settle", GroupsModels.paymentLine(summary(toSettle = 1)))
        assertEquals("3 payments to settle", GroupsModels.paymentLine(summary(toSettle = 3)))
        assertNull(GroupsModels.paymentLine(summary()))
    }

    @Test fun theCardCarriesTheLine() {
        val card = GroupsModels.list(listOf(summary(waitingForMe = 1))).cards.single()
        assertEquals("1 payment is waiting for you to confirm", card.paymentLine)
        assertNull(GroupsModels.list(listOf(summary())).cards.single().paymentLine)
    }

    @Test fun aGroupThatCannotBeOpenedOrWasLeftHasNoLine() {
        val lost = summary(waitingForMe = 1).copy(readable = false)
        assertNull(GroupsModels.list(listOf(lost)).cards.single().paymentLine)
        val removed = summary(waitingForMe = 1).copy(youWereRemoved = true)
        assertNull(GroupsModels.list(listOf(removed)).cards.single().paymentLine)
    }

    @Test fun theGroupScreenNoticeIsOnlyForWhatNeedsMyAnswer() {
        val w = PayWorld(); w.dinner(); w.markPaid(w.rohan, w.asha, 1_000); w.markPaid(w.meera, w.asha, 500)
        assertEquals("2 payments are waiting for you to confirm.", GroupScreenModels.build(w.viewFor(w.asha)).paymentNotice)
        assertNull(GroupScreenModels.build(w.viewFor(w.rohan)).paymentNotice, "the payer has nothing to answer")
    }

    @Test fun theGroupScreenHasNoNoticeWithNoWaitingPayments() {
        val w = PayWorld(); w.dinner()
        assertNull(GroupScreenModels.build(w.viewFor(w.asha)).paymentNotice)
    }

    @Test fun theSummaryCountsComeFromTheSessionsView() {
        val world = TestWorld("Asha")
        val s = world.sessions.session(world.newGroup("Goa Trip"))
        val me = s.view.value.me
        val ben = world.addFriend(s, "Ben", 2_000_001)
        world.expense(s, "Dinner", world.rupees(1_000), me, me, ben)
        val got = world.sessions.summaries.value.single()
        assertEquals(1, got.paymentsToSettle, "Ben owes me")
        assertEquals(0, got.paymentsWaitingForMe)
        s.markReceived(ben, world.rupees(200))
        val after = world.sessions.summaries.value.single()
        assertEquals(1, after.paymentsToSettle, "the rest is still to settle")
        assertEquals(0, after.paymentsWaitingForMe, "a payment I recorded is confirmed, not waiting")
    }

    // --- the plan follows the expenses, and payments already marked stay counted (DESIGN.md) ----------------------------------

    @Test fun aNewExpenseChangesThePlanAndAMarkedPaymentStaysCounted() {
        val w = PayWorld(); w.dinner(); w.markPaid(w.rohan, w.asha, 1_000)
        assertTrue(SettleUpModel.build(w.viewFor(w.rohan)).payments.none { it.role == PaymentRole.FROM_ME }, "Rohan has paid what he owed")
        w.emit(w.asha, EventBody.ExpenseAdded("Taxi", w.rupees(800), w.asha, 20_001, Split.Equal(setOf(w.asha, w.rohan))))
        val rohan = SettleUpModel.build(w.viewFor(w.rohan))
        assertEquals("₹400", rohan.payments.single { it.role == PaymentRole.FROM_ME }.amount, "only the new share is to pay: the marked payment still counts")
        assertEquals(1, rohan.waiting.size, "and it is still waiting to be confirmed")
    }

    @Test fun deletingAnExpenseGivesTheSameWithoutIt() {
        val w = PayWorld(); val dinner = w.dinner(); w.markPaid(w.rohan, w.asha, 1_000)
        w.emit(w.asha, EventBody.ExpenseDeleted(dinner))
        val rohan = SettleUpModel.build(w.viewFor(w.rohan))
        // The dinner no longer counts, so what Rohan marked as paid is now more than he owed: the group owes him that.
        assertTrue(rohan.payments.none { it.role == PaymentRole.FROM_ME })
        assertEquals(1, rohan.waiting.size)
        assertTrue(rohan.payments.any { it.to == w.rohan } || w.viewFor(w.rohan).balances.getValue(w.rohan).isPositive())
    }
}
