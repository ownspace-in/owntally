package owntally.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The removed member's late entries that replay cancelled, so the owner can see and re-enter them (M4, T4.2; clarification P5). */
class CancelledEntriesTest {
    private val a = key(1)
    private val b = key(2)
    private val c = key(3)
    private val d = key(4)
    private val all = setOf(a, b, c, d)

    @Test fun nothingIsCancelledWhenNobodyWasRemoved() {
        val s = standardGroup()
        s.expense(c, rupees(100), c, Split.Equal(all))
        assertEquals(emptyList(), cancelledEntries(s.log(), s.state()))
    }

    @Test fun lateEntriesOfARemovedMemberAreListedWithWhatTheyWere() {
        val s = standardGroup()
        val kept = s.expense(c, rupees(100), c, Split.Equal(all), "Tea")
        val lateExpense = s.expense(b, rupees(600), b, Split.Equal(setOf(b, c)), "Seen by nobody") // b is not removed; control
        val late1 = s.expense(c, rupees(450), b, Split.Equal(setOf(b, c)), "Dinner")
        val latePayment = s.emit(c, EventBody.SettlementRecorded(c, a, rupees(50)))
        val lateProfile = s.emit(c, EventBody.ProfileUpdated("Chitra S", PHONE))
        s.remove(a, c, seen = s.snapshot().toMutableMap().also { it[c] = kept.counter })
        val state = s.state()
        val cancelled = cancelledEntries(s.log(), state)
        assertEquals(listOf(late1.id, latePayment.id, lateProfile.id).sorted(), cancelled.map { it.id }.sorted())
        val dinner = cancelled.filterIsInstance<CancelledEntry.Expense>().single()
        assertEquals("Dinner", dinner.description)
        assertEquals(rupees(450), dinner.total)
        assertEquals(b, dinner.paidBy)
        assertEquals(c, dinner.author)
        assertEquals(Split.Equal(setOf(b, c)), dinner.split)
        val payment = cancelled.filterIsInstance<CancelledEntry.Payment>().single()
        assertEquals(Triple(c, a, rupees(50)), Triple(payment.from, payment.to, payment.amount))
        assertEquals("ProfileUpdated", cancelled.filterIsInstance<CancelledEntry.Other>().single().kind)
        assertTrue(kept.id !in cancelled.map { it.id } && lateExpense.id !in cancelled.map { it.id })
        assertTrue(lateExpense.id in state.expenses, "another member's entry is untouched")
    }

    @Test fun eachEntryNamesTheRemovedMemberWhoWroteIt() {
        val s = standardGroup()
        val firstC = s.expense(c, rupees(10), c, Split.Equal(all))
        val firstD = s.expense(d, rupees(10), d, Split.Equal(all))
        val lateC = s.expense(c, rupees(20), c, Split.Equal(all))
        val lateD = s.expense(d, rupees(20), d, Split.Equal(all))
        s.remove(a, c, seen = s.snapshot().toMutableMap().also { it[c] = firstC.counter })
        s.remove(a, d, seen = s.snapshot().toMutableMap().also { it[d] = firstD.counter })
        val byAuthor = cancelledEntries(s.log(), s.state()).groupBy { it.author }
        assertEquals(setOf(c, d), byAuthor.keys)
        assertEquals(listOf(lateC.id), byAuthor.getValue(c).map { it.id })
        assertEquals(listOf(lateD.id), byAuthor.getValue(d).map { it.id })
    }

    @Test fun theListGrowsWhenAnotherLateEntryArrivesAfterTheRemoval() {
        val s = standardGroup()
        val first = s.expense(c, rupees(10), c, Split.Equal(all))
        s.remove(a, c, seen = s.snapshot().toMutableMap().also { it[c] = first.counter })
        assertEquals(emptyList(), cancelledEntries(s.log(), s.state()))
        val late = s.expense(c, rupees(30), c, Split.Equal(all), "Arrived later")
        assertEquals(listOf(late.id), cancelledEntries(s.log(), s.state()).map { it.id })
    }

    @Test fun entriesThatWereIgnoredForOtherReasonsAreNotListed() {
        val s = standardGroup()
        val refused = s.emit(b, EventBody.ExpenseDeleted(s.expense(c, rupees(10), c, Split.Equal(all)).id)) // NOT_ALLOWED
        assertEquals(IgnoreReason.NOT_ALLOWED, refused.reason(s.state()))
        assertEquals(emptyList(), cancelledEntries(s.log(), s.state()))
    }

    @Test fun aLeaversLateEntriesAreCancelledToo() {
        val s = standardGroup()
        val first = s.expense(c, rupees(10), c, Split.Equal(all))
        s.remove(c, c, seen = s.snapshot().toMutableMap().also { it[c] = first.counter })
        val late = s.expense(c, rupees(15), c, Split.Equal(all))
        assertEquals(listOf(late.id), cancelledEntries(s.log(), s.state()).map { it.id })
    }
}
