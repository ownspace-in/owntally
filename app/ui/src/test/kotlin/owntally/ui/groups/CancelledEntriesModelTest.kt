package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.model.CancelledEntry
import owntally.model.EventId
import owntally.model.Money
import owntally.model.PublicKey
import owntally.model.Split

/** The late entries of a removed member (D5b) and "Add again" (D5c). Late events come from another phone, so the view's list is set directly here; the data tests check the real arrival. */
class CancelledEntriesModelTest {
    private val w = TestWorld()
    private val s = w.sessions.session(w.newGroup("Goa Trip"))
    private val me get() = s.view.value.me
    private val rohan = w.addFriend(s, "Rohan", 1)
    private val kabir = w.addFriend(s, "Kabir", 2)
    private val meera = w.addFriend(s, "Meera", 3)

    private fun withCancelled(vararg e: CancelledEntry): owntally.data.session.GroupView {
        s.removeMember(kabir)
        return s.view.value.copy(cancelled = e.toList())
    }

    private fun expense(n: Long, desc: String, total: Long, paidBy: PublicKey, split: Split, day: Long = 20_000) =
        CancelledEntry.Expense(EventId(kabir, n), kabir, desc, Money(total * 100), paidBy, day, split)

    @Test fun theOwnerSeesTheLateEntriesOfTheRemovedPersonWithAHeadingCount() {
        val view = withCancelled(
            expense(5, "Dinner", 900, kabir, Split.Equal(setOf(kabir, me))),
            CancelledEntry.Payment(EventId(kabir, 6), kabir, kabir, me, Money(5_000)),
            CancelledEntry.Other(EventId(kabir, 7), kabir, "ProfileUpdated"),
            expense(8, "Cab", 80, me, Split.Equal(setOf(me, kabir))),
        )
        val m = CancelledListModels.build(view, kabir)!!
        assertEquals("Kabir", m.name)
        assertEquals("Late entries not counted: 4", m.heading)
        assertEquals(listOf("Dinner", "Cab"), m.expenses.map { it.description })
        assertEquals(listOf("₹900 · paid by Kabir", "₹80 · paid by You"), m.expenses.map { it.detail })
        assertEquals(listOf("1 payment", "1 other event"), m.others)
    }

    @Test fun onlyTheOwnerAndOnlyForSomeoneWhoWentAndOnlyTheirEntries() {
        val view = withCancelled(expense(5, "Dinner", 900, kabir, Split.Equal(setOf(kabir, me))))
        assertNull(CancelledListModels.build(view.copy(me = rohan), kabir), "a member")
        assertNull(CancelledListModels.build(view, rohan), "someone still in the group")
        assertNull(CancelledListModels.build(view, PublicKey(ByteArray(32) { 9 })), "a stranger")
        s.makeAdmin(rohan)
        assertNull(CancelledListModels.build(s.view.value.copy(me = rohan, cancelled = view.cancelled), kabir), "an admin")
        val none = CancelledListModels.build(view, kabir)!!
        assertEquals(1, none.expenses.size)
    }

    @Test fun nothingCancelledGivesAnEmptyStateNotAScreenOfNothing() {
        val m = CancelledListModels.build(withCancelled(), kabir)!!
        assertEquals("Late entries not counted: 0", m.heading)
        assertEquals("Nothing was written after this person went.", m.empty)
    }

    @Test fun theMembersScreenShowsTheCountToTheOwnerOnly() {
        val view = withCancelled(expense(5, "Dinner", 900, kabir, Split.Equal(setOf(kabir, me))))
        val owner = MembersModels.build(view, 0).removed.single()
        assertEquals(kabir, owner.key)
        assertEquals(1, owner.lateEntries)
        assertTrue(owner.tappable)
        s.makeAdmin(rohan)
        val admin = MembersModels.build(view.copy(me = rohan), 0).removed.single()
        assertFalse(admin.tappable)
        assertEquals(0, admin.lateEntries)
    }

    // ---- Add again (D5c) -------------------------------------------------------------------------------------------------

    private fun again(e: CancelledEntry.Expense) = AddExpenseModel.fromCancelled(withCancelled(e), 7, 20_100, e)

    @Test fun anEqualSplitIsFilledInAndTheRemovedPersonIsTakenOut() {
        val m = again(expense(5, "Dinner", 900, me, Split.Equal(setOf(me, kabir, rohan))))
        assertEquals("Dinner", m.description)
        assertEquals("900", m.amountText)
        assertEquals(me, m.paidBy)
        assertEquals(SplitMode.EQUAL, m.mode)
        assertEquals(setOf(me, rohan), m.selected)
        assertTrue(m.people.none { it.key == kabir })
        assertTrue(m.canSave)
        assertEquals(20_000L, m.day)
    }

    @Test fun anExactSplitKeepsTheOthersAmountsAndLeavesTheRemovedPersonsShareToBeAssigned() {
        val m = again(expense(5, "Hotel", 1000, me, Split.Exact(mapOf(me to Money(30_000), kabir to Money(40_000), rohan to Money(30_000)))))
        assertEquals(SplitMode.EXACT, m.mode)
        assertEquals("300", m.exactText[me])
        assertEquals("300", m.exactText[rohan])
        assertNull(m.exactText[kabir])
        assertFalse(m.canSave, "the amounts do not add up until the owner assigns the rest")
        assertEquals("₹400 left to assign", m.exactStatus)
        val fixed = m.withExact(meera, "400")
        assertTrue(fixed.canSave)
    }

    @Test fun ifTheRemovedPersonPaidTheOwnerIsTheDefaultPayer() {
        val m = again(expense(5, "Taxi", 200, kabir, Split.Equal(setOf(kabir, me))))
        assertEquals(me, m.paidBy)
        assertEquals(setOf(me), m.selected)
    }

    @Test fun aDateInTheFutureIsBroughtBackToToday() {
        val m = again(expense(5, "Taxi", 200, me, Split.Equal(setOf(me)), day = 99_999))
        assertEquals(20_100L, m.day)
    }

    @Test fun theCounterAndTheRoundingComeFromTheNewEventNotTheOldOne() {
        val m = again(expense(5, "Dinner", 100, me, Split.Equal(setOf(me, rohan, meera))))
        assertEquals(7L, m.counter)
    }
}
