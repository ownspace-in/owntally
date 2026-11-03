package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.data.session.AddResult
import owntally.model.Expense
import owntally.model.Money
import owntally.model.PublicKey
import owntally.model.Split
import owntally.model.equalShares
import owntally.model.sharesOf

class AddExpenseModelTest {
    private val w = TestWorld("Asha")
    private val s = w.sessions.session(w.newGroup("Goa Trip"))
    private val me = s.view.value.me
    private val rohan = w.addFriend(s, "Rohan", 2_000_001)
    private val meera = w.addFriend(s, "Meera", 2_000_002)

    private fun model() = AddExpenseModel.start(s.view.value, s.nextCounter(), today = 20_000)

    @Test fun startsWithEveryoneSelectedPaidByMeToday() {
        val m = model()
        assertEquals(listOf("You", "Meera", "Rohan"), m.people.map { it.label }.let { l -> listOf(l[0]) + l.drop(1).sorted() })
        assertEquals(me, m.paidBy)
        assertEquals(20_000, m.day)
        assertEquals(3, m.selected.size)
        assertEquals(SplitMode.EQUAL, m.mode)
        assertFalse(m.canSave)
        assertEquals("You", m.people.first().label, "I am listed first")
    }

    @Test fun equalSharesShownAreExactlyWhatTheCoreWillCompute() {
        // 100.01 rupees among 3: the leftover paise must land where the core puts them, for the counter this event will get.
        val m = model().withAmount("100.01").withDescription("Lunch")
        val shown = m.shareTexts
        val expected = equalShares(Money(10001), m.selected, s.nextCounter())
        for (p in m.people) assertEquals(owntally.model.format.MoneyFormat.format(expected.getValue(p.key)), shown[p.key])
        // Save it, then the stored shares are the same as the ones shown.
        val id = (s.addExpense("Lunch", Money(10001), me, 20_000, m.toSplit()!!) as AddResult.Added).id
        val e: Expense = s.view.value.state.expenses.getValue(id)
        val stored = sharesOf(s.view.value.state, e)!!
        for (p in m.people) assertEquals(owntally.model.format.MoneyFormat.format(stored.getValue(p.key)), shown[p.key])
    }

    @Test fun theRoundingFollowsTheCounterAcrossSeveralExpenses() {
        repeat(4) { n ->
            val m = model().withAmount("100.01").withDescription("E$n")
            val r = s.addExpense("E$n", Money(10001), me, 20_000, m.toSplit()!!) as AddResult.Added
            val stored = sharesOf(s.view.value.state, s.view.value.state.expenses.getValue(r.id))!!
            for (p in m.people) assertEquals(owntally.model.format.MoneyFormat.format(stored.getValue(p.key)), m.shareTexts[p.key], "expense $n")
        }
    }

    @Test fun unselectedPeopleShowADashAndPayNothing() {
        val m = model().withAmount("2400").toggle(rohan)
        assertEquals("–", m.shareTexts[rohan])
        assertEquals("₹1,200", m.shareTexts[me])
        assertEquals(setOf(me, meera), (m.toSplit() as Split.Equal).participants)
    }

    @Test fun savingRules() {
        val m = model()
        assertFalse(m.withAmount("100").canSave, "no description")
        assertFalse(m.withDescription("x").canSave, "no amount")
        assertTrue(m.withAmount("100").withDescription("x").canSave)
        assertFalse(m.withAmount("0").withDescription("x").canSave)
        assertEquals("The amount must be more than ₹0", m.withAmount("0").problem)
        assertFalse(m.withAmount("1.234").withDescription("x").canSave)
        assertNotNull(m.withAmount("1.234").problem)
        assertFalse(m.withAmount("abc").withDescription("x").canSave)
        assertFalse(m.withAmount("100").withDescription("   ").canSave)
        assertFalse(m.withAmount("100").withDescription("x".repeat(81)).canSave)
        assertEquals("Use at most 80 letters", m.withAmount("100").withDescription("x".repeat(81)).problem)
        assertTrue(m.withAmount("100").withDescription("x".repeat(80)).canSave)
        // nobody selected
        var none = m.withAmount("100").withDescription("x")
        for (p in none.people) none = none.toggle(p.key)
        assertFalse(none.canSave)
        assertEquals("Choose at least one person", none.problem)
        assertNull(none.toSplit())
    }

    @Test fun descriptionIsTrimmedAndInputCapped() {
        val m = model().withAmount("5").withDescription("  Tea  ")
        assertEquals("Tea", m.trimmedDescription)
        assertEquals(AddExpenseModel.INPUT_LIMIT, model().withDescription("x".repeat(5000)).description.length)
        assertTrue(model().withAmount("9".repeat(500)).amountText.length <= AddExpenseModel.AMOUNT_LIMIT)
    }

    @Test fun exactSplitMustAddUpAndSaysByHowMuch() {
        var m = model().withAmount("1000").withDescription("Dinner").withMode(SplitMode.EXACT)
        assertFalse(m.canSave)
        m = m.withExact(me, "400").withExact(rohan, "300")
        assertEquals("₹300 left to assign", m.exactStatus)
        assertFalse(m.canSave)
        assertEquals("Amounts add up to ₹700, not ₹1,000", m.problem)
        m = m.withExact(meera, "300")
        assertEquals("All assigned", m.exactStatus)
        assertTrue(m.canSave)
        assertNull(m.problem)
        val split = m.toSplit() as Split.Exact
        assertEquals(Money(400_00), split.amounts[me])
        assertEquals(3, split.amounts.size)
        // too much
        m = m.withExact(meera, "400")
        assertEquals("₹100 too much", m.exactStatus)
        assertFalse(m.canSave)
        // the same exact split is accepted by the core
        assertTrue(s.addExpense("Dinner", Money(1000_00), me, 20_000, m.withExact(meera, "300").toSplit()!!) is AddResult.Added)
    }

    @Test fun exactLeavingSomeoneBlankMeansTheyDoNotTakePart() {
        val m = model().withAmount("500").withDescription("x").withMode(SplitMode.EXACT).withExact(me, "200").withExact(meera, "300")
        assertTrue(m.canSave)
        assertEquals(setOf(me, meera), (m.toSplit() as Split.Exact).amounts.keys)
        assertEquals("–", m.shareTexts[rohan])
    }

    @Test fun badExactAmountBlocksSavingAndNamesThePerson() {
        val m = model().withAmount("500").withDescription("x").withMode(SplitMode.EXACT).withExact(me, "250").withExact(rohan, "2.555")
        assertFalse(m.canSave)
        assertEquals("Check the amount for Rohan", m.problem)
        assertNull(m.toSplit())
    }

    @Test fun exactPaiseAddUp() {
        val m = model().withAmount("0.10").withDescription("x").withMode(SplitMode.EXACT).withExact(me, "0.03").withExact(rohan, "0.07")
        assertTrue(m.canSave)
    }

    @Test fun payerAndDay() {
        val m = model().withPaidBy(rohan)
        assertEquals(rohan, m.paidBy)
        assertEquals(me, m.withPaidBy(PublicKey(ByteArray(32) { 9 })).paidBy.takeIf { it == me } ?: me, "a stranger cannot be chosen")
        assertEquals(me, model().withPaidBy(PublicKey(ByteArray(32) { 9 })).paidBy)
        assertEquals(19_999, model().earlier().day)
        assertEquals(20_000, model().earlier().later().day)
        assertEquals(20_000, model().later().day, "not in the future")
    }

    @Test fun paidByLabelsAndNames() {
        val m = model()
        assertEquals("You", m.labelOf(me))
        assertEquals("Rohan", m.labelOf(rohan))
    }
}

class DeletePermissionTest {
    private val w = TestWorld("Asha")
    private val s = w.sessions.session(w.newGroup("G"))
    private val me = s.view.value.me
    private val rohan = w.addFriend(s, "Rohan", 2_000_001)

    private fun expense(): Expense {
        val r = w.expense(s, "Dinner", w.rupees(100), me, me, rohan) as AddResult.Added
        return s.view.value.state.expenses.getValue(r.id)
    }

    @Test fun creatorAndOwnerMayDelete() {
        assertTrue(DeleteRules.canDelete(s.view.value, expense()))
    }

    @Test fun aPlainMemberMayNotDeleteSomeoneElsesExpense() {
        val e = expense()
        val asRohan = s.view.value.copy(me = rohan) // Rohan is a plain member
        assertFalse(DeleteRules.canDelete(asRohan, e))
    }

    @Test fun aPlainMemberMayDeleteTheirOwnExpenseButNotMine() {
        val mine = expense()
        val asRohan = s.view.value.copy(me = rohan)
        assertTrue(DeleteRules.canDelete(asRohan, mine.copy(creator = rohan)), "creator may delete")
        assertFalse(DeleteRules.canDelete(asRohan, mine), "someone else's: not allowed")
    }

    @Test fun oneSideOfAPaymentOnTheExpenseIsEnoughToWarn() {
        val r = w.expense(s, "Solo", w.rupees(10), me, me) as AddResult.Added
        assertTrue(s.append(owntally.model.EventBody.SettlementRecorded(me, rohan, Money(1_00))) is AddResult.Added)
        val st = s.view.value
        assertTrue(DeleteRules.changesSettledAmounts(st, st.state.expenses.getValue(r.id)), "I am on it and paid Rohan")
    }

    @Test fun deletedExpensesCannotBeDeletedAgain() {
        val e = expense()
        s.deleteExpense(e.id)
        assertFalse(DeleteRules.canDelete(s.view.value, s.view.value.state.expenses.getValue(e.id)))
    }

    @Test fun warningOnlyWhenPaymentsAlreadyInvolveSomeoneOnTheExpense() {
        val e = expense()
        assertFalse(DeleteRules.changesSettledAmounts(s.view.value, e))
        s.append(owntally.model.EventBody.SettlementRecorded(rohan, me, Money(50_00)))
        assertTrue(DeleteRules.changesSettledAmounts(s.view.value, s.view.value.state.expenses.getValue(e.id)))
    }

    @Test fun aPaymentBetweenOtherPeopleDoesNotWarn() {
        val carol = w.addFriend(s, "Carol", 2_000_002)
        val r = w.expense(s, "Biscuits", w.rupees(10), rohan, rohan) as AddResult.Added
        assertTrue(s.append(owntally.model.EventBody.SettlementRecorded(me, carol, Money(5_00))) is AddResult.Added)
        val st = s.view.value
        assertFalse(DeleteRules.changesSettledAmounts(st, st.state.expenses.getValue(r.id)), "Rohan is in no payment")
    }
}
