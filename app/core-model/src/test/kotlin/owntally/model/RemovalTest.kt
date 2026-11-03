package owntally.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RemovalTest {
    private val a = key(1)
    private val b = key(2)
    private val c = key(3)
    private val d = key(4)
    private val e = key(5)

    /**
     * Five members. Worked out by hand (rupees):
     *  1. A pays 1000, equal among all five: 200 each.
     *  2. B pays 600, equal among B, C, D: 200 each.
     *  3. C pays 300, exact: A 100, B 100, E 100.
     *  4. D pays 500, exact: C 200, D 100, E 200.
     * Before payments: A +700, B +100, C -300, D 0, E -500.
     * E then pays A 200, B 100, C 100 and D 100, so E is settled with everyone:
     * Before: A +500, B 0, C -400, D -100, E 0.
     */
    private fun trip(): Sim {
        val s = standardGroup()
        s.join(a, e, "Eve")
        s.expense(a, rupees(1000), a, Split.Equal(setOf(a, b, c, d, e)), "Hotel")
        s.expense(b, rupees(600), b, Split.Equal(setOf(b, c, d)), "Taxi")
        s.expense(c, rupees(300), c, Split.Exact(mapOf(a to rupees(100), b to rupees(100), e to rupees(100))), "Snacks")
        s.expense(d, rupees(500), d, Split.Exact(mapOf(c to rupees(200), d to rupees(100), e to rupees(200))), "Boat")
        s.emit(e, EventBody.SettlementRecorded(e, a, rupees(200)))
        s.emit(e, EventBody.SettlementRecorded(e, b, rupees(100)))
        s.emit(e, EventBody.SettlementRecorded(e, c, rupees(100)))
        s.emit(e, EventBody.SettlementRecorded(e, d, rupees(100)))
        return s
    }

    @Test
    fun theFixtureBalancesBeforeRemovalMatchTheHandCalculation() {
        val before = balances(trip().state())
        assertEquals(
            mapOf(a to rupees(500), b to Money.ZERO, c to -rupees(400), d to -rupees(100), e to Money.ZERO),
            before,
        )
    }

    @Test
    fun removingEveMatchesTheHandCalculation() {
        // After removing E: hotel is split four ways (250 each); the exact shares of E (100 and 200) are re-split
        // among the others on those expenses; E's payments are excluded.
        //  A +1000 -250 -150 = +600   B -250 +600 -200 -150 = 0   C -250 -200 +300 -300 = -450   D -250 -200 +500 -200 = -150
        val s = trip()
        s.emit(a, EventBody.MemberRemoved(e))
        val after = balances(s.state())
        assertEquals(mapOf(a to rupees(600), b to Money.ZERO, c to -rupees(450), d to -rupees(150)), after)
        assertEquals(Money.ZERO, after.values.fold(Money.ZERO) { x, y -> x + y })
    }

    @Test
    fun thePreviewShowsBeforeAndAfterForEveryRemainingMember() {
        val state = trip().state()
        val preview = previewRemoval(state, e)
        assertEquals(
            listOf(
                BeforeAfter(a, rupees(500), rupees(600)),
                BeforeAfter(b, Money.ZERO, Money.ZERO),
                BeforeAfter(c, -rupees(400), -rupees(450)),
                BeforeAfter(d, -rupees(100), -rupees(150)),
            ),
            preview.changes,
        )
        assertEquals(4, preview.excludedSettlements.size)
        assertEquals(emptyList(), preview.excludedExpenses)
        assertEquals(3, preview.resplitExpenses.size) // hotel, snacks, boat
    }

    @Test
    fun thePreviewDoesNotChangeAnything() {
        val s = trip()
        val state = s.state()
        val before = balances(state)
        previewRemoval(state, e)
        assertEquals(before, balances(state))
        assertEquals(setOf(a, b, c, d, e), state.currentMembers.keys)
    }

    @Test
    fun theActualRemovalGivesTheBalancesThePreviewPromised() {
        val s = trip()
        val promised = previewRemoval(s.state(), c).changes.associate { it.member to it.after }
        s.emit(a, EventBody.MemberRemoved(c))
        assertEquals(promised, balances(s.state()))
    }

    // --- one case at a time ------------------------------------------------------------------

    @Test
    fun expensesTheRemovedMemberPaidAreExcludedSoNobodyOwesThem() {
        val s = standardGroup()
        s.expense(c, rupees(300), c, Split.Equal(setOf(a, b, c)))
        assertEquals(-rupees(100), balances(s.state()).getValue(a))
        s.emit(a, EventBody.MemberRemoved(c))
        val after = balances(s.state())
        assertEquals(Money.ZERO, after.getValue(a))
        assertEquals(Money.ZERO, after.getValue(b))
        assertEquals(listOf(s.events.first { it.body is EventBody.ExpenseAdded }.id), previewRemoval(trip1(), c).excludedExpenses)
    }

    private fun trip1(): GroupState {
        val s = standardGroup()
        s.expense(c, rupees(300), c, Split.Equal(setOf(a, b, c)))
        return s.state()
    }

    @Test
    fun anEqualSplitIsRedividedAmongTheRemainingParticipants() {
        val s = standardGroup()
        s.expense(a, rupees(900), a, Split.Equal(setOf(a, b, c)))
        s.emit(a, EventBody.MemberRemoved(c))
        val after = balances(s.state())
        assertEquals(rupees(450), after.getValue(a))
        assertEquals(-rupees(450), after.getValue(b))
    }

    @Test
    fun anExactSplitRedividesTheRemovedShareSoThePayerIsRepaidInFull() {
        val s = standardGroup()
        s.expense(a, rupees(600), a, Split.Exact(mapOf(a to rupees(100), b to rupees(200), c to rupees(300))))
        // C leaves: their 300 is shared equally by A and B (150 each), so A owes 250 and B owes 350.
        s.emit(a, EventBody.MemberRemoved(c))
        val after = balances(s.state())
        assertEquals(rupees(350), after.getValue(a)) // paid 600, own share 250
        assertEquals(-rupees(350), after.getValue(b))
    }

    @Test
    fun theRemovedSharesLeftoverPaiseAreGivenOutByTheRule() {
        val s = standardGroup()
        // C's share of 501 paise goes to A and B: 250 each, the odd paisa to the member the rule picks.
        val x = s.expense(a, Money(1001), a, Split.Exact(mapOf(a to Money(100), b to Money(400), c to Money(501))))
        s.emit(a, EventBody.MemberRemoved(c))
        val extra = equalShares(Money(501), listOf(a, b), x.counter)
        val after = balances(s.state())
        assertEquals(Money(1001) - Money(100) - extra.getValue(a), after.getValue(a))
        assertEquals(-(Money(400) + extra.getValue(b)), after.getValue(b))
        assertEquals(Money.ZERO, after.values.fold(Money.ZERO) { p, q -> p + q })
    }

    @Test
    fun aRemovedMembersPaymentsAreExcluded() {
        val s = standardGroup()
        s.expense(a, rupees(300), a, Split.Equal(setOf(a, b, c)))
        s.emit(c, EventBody.SettlementRecorded(c, a, rupees(100)))
        s.emit(b, EventBody.SettlementRecorded(b, c, rupees(40)))
        s.emit(a, EventBody.MemberRemoved(c))
        val after = balances(s.state())
        // Only A and B remain: A paid 300 for two, so B owes 150. The payments with C do not count.
        assertEquals(rupees(150), after.getValue(a))
        assertEquals(-rupees(150), after.getValue(b))
    }

    @Test
    fun aMemberWhoAlreadyPaidTheRemovedMemberOwesThatAmountAgain() {
        val s = standardGroup()
        s.expense(c, rupees(300), c, Split.Equal(setOf(c, d)), "Taxi")   // C +150, D -150
        s.expense(a, rupees(300), a, Split.Equal(setOf(a, b)), "Lunch")  // A +150, B -150
        // The group-level plan can ask B to pay C, although B never spent with C.
        s.emit(b, EventBody.SettlementRecorded(b, c, rupees(150)))
        val before = balances(s.state())
        assertEquals(Money.ZERO, before.getValue(b))
        assertEquals(Money.ZERO, before.getValue(c))
        s.emit(a, EventBody.MemberRemoved(c))
        val after = balances(s.state())
        // C's taxi and B's payment to C both drop out, so B owes the 150 for lunch again.
        assertEquals(-rupees(150), after.getValue(b))
        assertEquals(rupees(150), after.getValue(a))
        assertEquals(Money.ZERO, after.getValue(d))
    }

    @Test
    fun anExpenseWithNoRemainingParticipantsIsExcluded() {
        val s = standardGroup()
        s.expense(a, rupees(300), a, Split.Exact(mapOf(b to rupees(100), c to rupees(200))))
        s.emit(a, EventBody.MemberRemoved(b))
        s.emit(a, EventBody.MemberRemoved(c))
        val after = balances(s.state())
        assertEquals(Money.ZERO, after.getValue(a)) // paid 300 for B and C, who are gone: nobody owes A
    }

    @Test
    fun removalsInSequenceGiveTheSameResultAsTheOrderOfRemovalsDoesNotMatter() {
        val first = trip().also { it.emit(a, EventBody.MemberRemoved(e)); it.emit(a, EventBody.MemberRemoved(d)) }
        val second = trip().also { it.emit(a, EventBody.MemberRemoved(d)); it.emit(a, EventBody.MemberRemoved(e)) }
        assertEquals(balances(first.state()), balances(second.state()))
    }

    @Test
    fun removedMembersKeepTheirEntriesInHistory() {
        val s = trip()
        s.emit(a, EventBody.MemberRemoved(e))
        val state = s.state()
        assertEquals(4, state.expenses.size)
        assertEquals(4, state.settlements.size)
        assertTrue(e !in balances(state))
    }

    @Test
    fun previewOfSomeoneWhoIsNotAMemberFails() {
        val state = trip().state()
        assertFailsWith<IllegalArgumentException> { previewRemoval(state, key(9)) }
    }

    @Test
    fun afterBalancesAlwaysSumToZero() = runBlocking<Unit> {
        checkAll(300, Arb.long()) { seed ->
            val state = replay(logOf(randomScenario(Random(seed), steps = 60)))
            for (member in state.currentMembers.keys) {
                val preview = previewRemoval(state, member)
                assertEquals(Money.ZERO, preview.changes.fold(Money.ZERO) { x, ba -> x + ba.after }, "seed $seed member $member")
                assertEquals(state.currentMembers.keys - member, preview.changes.map { it.member }.toSet())
            }
        }
    }
}
