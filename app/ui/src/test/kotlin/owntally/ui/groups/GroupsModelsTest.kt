package owntally.ui.groups

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import owntally.data.session.AddResult
import owntally.model.format.BalanceKind

class GroupsModelsTest {
    @Test fun initials() {
        assertEquals("GT", GroupsModels.initials("Goa Trip"))
        assertEquals("FL", GroupsModels.initials("Flat"))
        assertEquals("OL", GroupsModels.initials("  office   lunches  "))
        assertEquals("A", GroupsModels.initials("a"))
        assertEquals("SF", GroupsModels.initials("Sunday Football Club"))
    }

    @Test fun emptyList() {
        val w = TestWorld()
        val m = GroupsModels.list(w.sessions.summaries.value)
        assertTrue(m.cards.isEmpty())
        assertEquals(BalanceKind.SETTLED, m.owed.kind)
        assertEquals(BalanceKind.SETTLED, m.owe.kind)
    }

    @Test fun cardTextAndPlurals() {
        val w = TestWorld()
        val id = w.newGroup("Goa Trip", 2)
        val c1 = GroupsModels.list(w.sessions.summaries.value).cards.single()
        assertEquals("1 member · 0 expenses", c1.subtitle)
        assertEquals("GT", c1.initials)
        assertEquals(2, c1.colourIndex)
        assertEquals(BalanceKind.SETTLED, c1.balance.kind)
        val s = w.sessions.session(id)
        val ben = w.addFriend(s, "Ben", 2_000_001)
        w.expense(s, "Taxi", w.rupees(200), s.view.value.me, s.view.value.me, ben)
        val c2 = GroupsModels.list(w.sessions.summaries.value).cards.single()
        assertEquals("2 members · 1 expense", c2.subtitle)
    }

    @Test fun totalsAcrossGroups() {
        val w = TestWorld()
        val g1 = w.sessions.session(w.newGroup("One"))
        val g2 = w.sessions.session(w.newGroup("Two"))
        val g3 = w.sessions.session(w.newGroup("Three"))
        val me = g1.view.value.me
        val b1 = w.addFriend(g1, "Ben", 2_000_001)
        val b2 = w.addFriend(g2, "Cy", 2_000_002)
        val b3 = w.addFriend(g3, "Di", 2_000_003)
        w.expense(g1, "a", w.rupees(900), me, me, b1) // I am owed 450
        w.expense(g2, "b", w.rupees(300), b2, g2.view.value.me, b2) // I owe 150
        w.expense(g3, "c", w.rupees(100), b3, g3.view.value.me, b3) // I owe 50
        val m = GroupsModels.list(w.sessions.summaries.value)
        assertEquals("₹450", m.owed.text)
        assertEquals(BalanceKind.OWED, m.owed.kind)
        assertEquals("₹200", m.owe.text)
        assertEquals(BalanceKind.OWES, m.owe.kind)
    }

    @Test fun unreadableGroupIsListedButNotCounted() {
        val s = listOf(
            owntally.data.session.GroupSummary(owntally.model.GroupId(ByteArray(16)), "Lost", 0, 0, 0, owntally.model.Money(500_00), false),
        )
        val m = GroupsModels.list(s)
        assertEquals(BalanceKind.SETTLED, m.owed.kind)
        assertEquals("Waiting for the group key", m.cards.single().subtitle)
    }
}

class GroupScreenModelsTest {
    @Test fun ownerWithNobodyElse() {
        val w = TestWorld()
        val s = w.sessions.session(w.newGroup("Solo"))
        val m = GroupScreenModels.build(s.view.value)
        assertEquals("You are the owner", m.roleText)
        assertTrue(m.memberLines.isEmpty())
        assertTrue(m.allSettled)
        assertEquals("₹0", m.totalSpent)
        assertTrue(m.expenses.isEmpty())
    }

    @Test fun goaTripLikeTheMockup() {
        val w = TestWorld("Asha")
        val s = w.sessions.session(w.newGroup("Goa Trip"))
        val me = s.view.value.me
        val rohan = w.addFriend(s, "Rohan", 2_000_001)
        val meera = w.addFriend(s, "Meera", 2_000_002)
        w.expense(s, "Dinner", w.rupees(900), me, me, rohan, meera, day = 20_001)
        w.expense(s, "Villa", w.rupees(600), meera, me, rohan, meera, day = 20_002)
        val m = GroupScreenModels.build(s.view.value)
        // Asha: paid 900, own shares 300+200 -> +400. Rohan owes 500. Meera: paid 600, shares 300+200 -> +100.
        assertEquals("₹1,500", m.totalSpent)
        assertEquals(BalanceKind.OWED, m.balance.kind)
        assertEquals("₹400", m.balance.text)
        assertEquals(listOf("Meera is owed", "Rohan owes"), m.memberLines.map { it.text })
        assertEquals(listOf("₹100", "₹500"), m.memberLines.map { it.amount })
        assertFalse(m.allSettled)
        // newest first
        assertEquals(listOf("Villa", "Dinner"), m.expenses.map { it.description })
    }

    @Test fun settledNamesAreJoined() {
        val w = TestWorld()
        val s = w.sessions.session(w.newGroup("G"))
        w.addFriend(s, "Kabir", 2_000_001)
        w.addFriend(s, "Isha", 2_000_002)
        val m = GroupScreenModels.build(s.view.value)
        assertEquals(listOf("Isha & Kabir"), m.memberLines.map { it.text })
        assertEquals("settled", m.memberLines.single().amount)
    }

    @Test fun expenseRows() {
        val w = TestWorld()
        val s = w.sessions.session(w.newGroup("G"))
        val me = s.view.value.me
        val rohan = w.addFriend(s, "Rohan", 2_000_001)
        val meera = w.addFriend(s, "Meera", 2_000_002)
        w.expense(s, "Dinner", w.rupees(4800), me, me, rohan, meera, day = 3)
        w.expense(s, "Villa", w.rupees(2100), meera, me, rohan, meera, day = 2)
        w.expense(s, "Rohan only", w.rupees(100), rohan, rohan, meera, day = 1)
        val rows = GroupScreenModels.build(s.view.value).expenses
        assertEquals("You paid ₹4,800 · split 3 ways", rows[0].subtitle)
        assertEquals("you lent", rows[0].caption); assertEquals("₹3,200", rows[0].amount)
        assertEquals("Meera paid ₹2,100 · split 3 ways", rows[1].subtitle)
        assertEquals("you owe", rows[1].caption); assertEquals("₹700", rows[1].amount)
        assertEquals("not involved", rows[2].caption)
    }

    @Test fun soloExpenseWording() {
        val w = TestWorld()
        val s = w.sessions.session(w.newGroup("G"))
        val me = s.view.value.me
        w.expense(s, "Tea", w.rupees(100), me, me)
        val row = GroupScreenModels.build(s.view.value).expenses.single()
        assertEquals("You paid ₹100 · split 1 way", row.subtitle)
        assertEquals("no balance change", row.caption)
        assertEquals("₹0", row.amount)
        assertEquals(BalanceKind.SETTLED, row.kind)
    }

    @Test fun deletedExpenseIsStruckAndNotCounted() {
        val w = TestWorld()
        val s = w.sessions.session(w.newGroup("G"))
        val me = s.view.value.me
        val rohan = w.addFriend(s, "Rohan", 2_000_001)
        val r = w.expense(s, "Scooter", w.rupees(2500), me, me, rohan)
        val id = (r as AddResult.Added).id
        s.deleteExpense(id)
        val m = GroupScreenModels.build(s.view.value)
        val row = m.expenses.single()
        assertTrue(row.deleted)
        assertEquals("Deleted by Asha · not counted", row.subtitle)
        assertNull(row.caption)
        assertEquals("₹2,500", row.amount)
        assertEquals("₹0", m.totalSpent)
        assertTrue(m.allSettled)
    }

    private fun payerGone(left: Boolean): ExpenseRow {
        val w = TestWorld()
        val s = w.sessions.session(w.newGroup("G"))
        val me = s.view.value.me
        val rohan = w.addFriend(s, "Rohan", 2_000_001)
        val kabir = w.addFriend(s, "Kabir", 2_000_002)
        w.expense(s, "Scooter", w.rupees(2500), rohan, me, rohan, kabir)
        val view = s.view.value
        val gone = view.state.copy(
            members = view.state.members.mapValues { if (it.key == rohan) it.value.copy(removed = true) else it.value },
            removals = mapOf(rohan to owntally.model.Removal(rohan, if (left) rohan else me, owntally.model.EventId(me, 99), 0)),
        )
        return GroupScreenModels.build(view.copy(state = gone)).expenses.single()
    }

    @Test fun anExpenseTheRemovedMemberPaidIsExcludedWithTheReasonNotShownAsDeleted() {
        val row = payerGone(left = false)
        assertEquals("Excluded: Rohan was removed", row.subtitle)
        assertTrue(row.excluded)
        assertFalse(row.deleted, "greyed, not struck through")
        assertNull(row.caption)
    }

    @Test fun anExpenseTheMemberWhoLeftPaidSaysLeft() {
        val row = payerGone(left = true)
        assertEquals("Excluded: Rohan left", row.subtitle)
        assertTrue(row.excluded)
    }

    @Test fun aNormalExpenseIsNeitherDeletedNorExcluded() {
        val w = TestWorld()
        val s = w.sessions.session(w.newGroup("G"))
        val me = s.view.value.me
        w.expense(s, "Lunch", w.rupees(100), me, me)
        val row = GroupScreenModels.build(s.view.value).expenses.single()
        assertFalse(row.excluded)
        assertFalse(row.deleted)
    }

    @Test fun roleWording() {
        assertEquals("You are the owner", GroupScreenModels.roleText(owntally.model.Role.OWNER))
        assertEquals("You are an admin", GroupScreenModels.roleText(owntally.model.Role.ADMIN))
        assertEquals("You are a member", GroupScreenModels.roleText(owntally.model.Role.MEMBER))
        assertEquals("You are a member", GroupScreenModels.roleText(null))
    }

    @Test fun tabs() {
        assertEquals(listOf("Groups", "Settle", "Sync", "About"), Tab.entries.map { it.label })
        assertEquals(listOf(true, true, false, true), Tab.entries.map { it.enabled })
        assertTrue(SyncPlaceholder.TEXT.contains("stored only on this phone"))
    }
}
