package owntally.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BalancesTest {
    private val a = key(1)
    private val b = key(2)
    private val c = key(3)
    private val d = key(4)

    // --- equal split rounding ----------------------------------------------------------------

    @Test
    fun anEqualSplitOf10000AmongThreeGives3334And3333And3333() {
        for (counter in 1L..9L) {
            val shares = equalShares(Money(10_000), listOf(a, b, c), counter)
            assertEquals(Money(10_000), shares.values.fold(Money.ZERO) { x, y -> x + y })
            assertEquals(listOf(3333L, 3333L, 3334L), shares.values.map { it.paise }.sorted())
            // The extra paisa starts at position counter mod 3 in ascending key order.
            val extraTo = listOf(a, b, c)[Math.floorMod(counter, 3)]
            assertEquals(Money(3334), shares.getValue(extraTo), "counter $counter")
        }
    }

    @Test
    fun theExtraPaiseWrapAroundTheEnd() {
        // 100 among 3 leaves 1; 101 among 3 leaves 2. Starting at position 2 the extras go to positions 2 and 0.
        val shares = equalShares(Money(101), listOf(a, b, c), 2)
        assertEquals(mapOf(a to Money(34), b to Money(33), c to Money(34)), shares)
    }

    @Test
    fun theOrderOfTheInputDoesNotMatter() {
        assertEquals(equalShares(Money(10_000), listOf(c, a, b), 7), equalShares(Money(10_000), listOf(a, b, c), 7))
    }

    @Test
    fun anEqualSplitAmongOneGivesThemEverything() {
        assertEquals(mapOf(a to Money(777)), equalShares(Money(777), listOf(a), 5))
    }

    @Test
    fun extraPaiseDoNotAlwaysLandOnTheSamePerson() {
        val got = (1L..30L).map { counter -> equalShares(Money(100), listOf(a, b, c), counter).entries.first { it.value == Money(34) }.key }.toSet()
        assertEquals(setOf(a, b, c), got)
    }

    // --- balances ----------------------------------------------------------------------------

    @Test
    fun theWorkedExampleFromTheProtocol() {
        val s = standardGroup()
        s.expense(a, rupees(900), a, Split.Equal(setOf(a, b, c)), "Dinner")
        s.expense(b, rupees(600), b, Split.Equal(setOf(a, b, c)), "Taxi")
        val balances = balances(s.state())
        assertEquals(rupees(400), balances.getValue(a))
        assertEquals(rupees(100), balances.getValue(b))
        assertEquals(-rupees(500), balances.getValue(c))
        assertEquals(Money.ZERO, balances.getValue(d)) // took no part
    }

    @Test
    fun theLeftoverPaiseGoToTheMembersTheRuleSays() {
        val s = standardGroup()
        val x = s.expense(a, Money(10_000), a, Split.Equal(setOf(a, b, c)))
        val balances = balances(s.state())
        val shares = equalShares(Money(10_000), listOf(a, b, c), x.counter)
        assertEquals(Money(10_000) - shares.getValue(a), balances.getValue(a))
        assertEquals(-shares.getValue(b), balances.getValue(b))
        assertEquals(-shares.getValue(c), balances.getValue(c))
    }

    @Test
    fun theStateOnlyDependsOnTheLogNotOnTheDeviceItRunsOn() {
        val s = standardGroup()
        s.expense(a, Money(10_000), a, Split.Equal(setOf(a, b, c)))
        assertEquals(balances(s.state()), balances(replay(logOf(s.events.reversed()))))
    }

    @Test
    fun anExactSplitChargesEachTheirAmountAndThePayerNeedNotBeInIt() {
        val s = standardGroup()
        s.expense(a, rupees(500), d, Split.Exact(mapOf(a to rupees(200), b to rupees(300))))
        val balances = balances(s.state())
        assertEquals(-rupees(200), balances.getValue(a))
        assertEquals(-rupees(300), balances.getValue(b))
        assertEquals(rupees(500), balances.getValue(d))
    }

    @Test
    fun aDeletedExpenseLeavesTheBalancesButStaysInHistory() {
        val s = standardGroup()
        val x = s.expense(a, rupees(900), a, Split.Equal(setOf(a, b, c)))
        assertEquals(rupees(600), balances(s.state()).getValue(a))
        s.emit(a, EventBody.ExpenseDeleted(x.id))
        val state = s.state()
        assertTrue(balances(state).values.all { it == Money.ZERO })
        assertTrue(state.expenses.getValue(x.id).deleted)
    }

    // --- settlements -------------------------------------------------------------------------

    private fun withDebt(): Pair<Sim, Event> {
        val s = standardGroup()
        s.expense(a, rupees(900), a, Split.Equal(setOf(a, b, c)))
        val pay = s.emit(c, EventBody.SettlementRecorded(c, a, rupees(100)))
        return s to pay
    }

    @Test
    fun aPaymentCountsAtOnceAsSoonAsItIsRecorded() {
        val (s, _) = withDebt()
        val balances = balances(s.state())
        assertEquals(rupees(500), balances.getValue(a)) // 600 owed, 100 already paid
        assertEquals(-rupees(200), balances.getValue(c)) // 300 owed, 100 already paid
    }

    @Test
    fun confirmingKeepsItCountingAndDisputingStopsIt() {
        val (s, pay) = withDebt()
        s.emit(a, EventBody.SettlementConfirmed(pay.id))
        assertEquals(rupees(500), balances(s.state()).getValue(a))

        val (s2, pay2) = withDebt()
        s2.emit(a, EventBody.PaymentDisputed(pay2.id, DisputeReason.NOTHING_RECEIVED))
        val back = balances(s2.state())
        assertEquals(rupees(600), back.getValue(a))
        assertEquals(-rupees(300), back.getValue(c))
    }

    @Test
    fun aDisputedPaymentCountsAgainOnceConfirmed() {
        val (s, pay) = withDebt()
        s.emit(a, EventBody.PaymentDisputed(pay.id, DisputeReason.NOT_CHECKED))
        s.emit(a, EventBody.SettlementConfirmed(pay.id))
        assertEquals(rupees(500), balances(s.state()).getValue(a))
    }

    @Test
    fun aPaymentRecordedByTheReceiverCountsFully() {
        val s = standardGroup()
        s.expense(a, rupees(900), a, Split.Equal(setOf(a, b, c)))
        s.emit(a, EventBody.SettlementRecorded(c, a, rupees(300)))
        val balances = balances(s.state())
        assertEquals(rupees(300), balances.getValue(a))
        assertEquals(Money.ZERO, balances.getValue(c))
    }

    // --- property ----------------------------------------------------------------------------

    @Test
    fun balancesAlwaysSumToZero() = runBlocking<Unit> {
        checkAll(500, Arb.long()) { seed ->
            val state = replay(logOf(randomScenario(Random(seed), steps = 60)))
            val balances = balances(state)
            assertEquals(Money.ZERO, balances.values.fold(Money.ZERO) { x, y -> x + y }, "seed $seed")
            assertEquals(state.currentMembers.keys, balances.keys)
        }
    }
}

class SharesTest {
    private val a = key(1)
    private val b = key(2)
    private val c = key(3)

    @Test
    fun anEqualExpenseGivesEachParticipantTheSameShareAsTheBalancesCount() {
        val s = standardGroup()
        val x = s.expense(a, Money(10_000), a, Split.Equal(setOf(a, b, c)))
        val state = s.state()
        val shares = sharesOf(state, state.expenses.getValue(x.id))!!
        assertEquals(equalShares(Money(10_000), listOf(a, b, c), x.counter), shares)
        assertEquals(Money(10_000), shares.values.fold(Money.ZERO) { p, q -> p + q })
    }

    @Test
    fun anExactExpenseGivesTheEnteredAmounts() {
        val s = standardGroup()
        val x = s.expense(a, rupees(500), c, Split.Exact(mapOf(a to rupees(200), b to rupees(300))))
        val state = s.state()
        assertEquals(mapOf(a to rupees(200), b to rupees(300)), sharesOf(state, state.expenses.getValue(x.id)))
    }

    @Test
    fun aDeletedExpenseHasNoShares() {
        val s = standardGroup()
        val x = s.expense(a, rupees(100), a, Split.Equal(setOf(a, b)))
        s.emit(a, EventBody.ExpenseDeleted(x.id))
        val state = s.state()
        assertNull(sharesOf(state, state.expenses.getValue(x.id)))
        assertEquals(Money.ZERO, totalSpent(state))
    }

    @Test
    fun anExpenseTheRemovedMemberPaidHasNoShares() {
        val s = standardGroup()
        val x = s.expense(c, rupees(300), c, Split.Equal(setOf(a, b, c)))
        s.remove(a, c)
        val state = s.state()
        assertNull(sharesOf(state, state.expenses.getValue(x.id)))
    }

    @Test
    fun aRemovedMembersShareIsDividedAmongTheOthers() {
        val s = standardGroup()
        val x = s.expense(a, rupees(900), a, Split.Equal(setOf(a, b, c)))
        s.remove(a, c)
        val state = s.state()
        assertEquals(mapOf(a to rupees(450), b to rupees(450)), sharesOf(state, state.expenses.getValue(x.id)))
    }

    @Test
    fun theTotalSpentCountsOnlyWhatCounts() {
        val s = standardGroup()
        s.expense(a, rupees(900), a, Split.Equal(setOf(a, b, c)))
        val gone = s.expense(b, rupees(600), b, Split.Equal(setOf(a, b, c)))
        s.expense(c, rupees(300), c, Split.Equal(setOf(a, b, c)))
        s.emit(a, EventBody.ExpenseDeleted(gone.id))
        assertEquals(rupees(1200), totalSpent(s.state()))
        s.remove(a, c) // C's own expense stops counting
        assertEquals(rupees(900), totalSpent(s.state()))
    }

    @Test
    fun theSharesOfEveryCountedExpenseAddUpToItsTotal() = runBlocking<Unit> {
        checkAll(300, Arb.long()) { seed ->
            val state = replay(logOf(randomScenario(Random(seed), steps = 60)))
            for (e in state.expenses.values) {
                val shares = sharesOf(state, e)
                if (shares != null) assertEquals(e.total, shares.values.fold(Money.ZERO) { p, q -> p + q }, "seed $seed")
            }
        }
    }

    @kotlin.test.Ignore // KNOWN GAP, awaiting the owner's decision (clarification P8): see docs/tasks/M3-storage-app-shell.md, "Security finding 2".
    @Test
    fun hugeAmountsFromAMemberCannotCrashTheBalancesForEveryone() {
        // A member writes expenses so large that adding them would overflow 64 bits. Phones must still work out balances.
        val s = standardGroup()
        val d = key(4)
        val everyone = setOf(a, b, c, d)
        s.expense(b, Money(Long.MAX_VALUE), b, Split.Equal(everyone))
        s.expense(c, Money(Long.MAX_VALUE), c, Split.Equal(everyone))
        s.expense(d, Money(Long.MAX_VALUE), d, Split.Equal(everyone))
        val state = s.state()
        assertEquals(3, state.expenses.size, "the huge expenses were accepted: ${state.ignored}")
        val result = runCatching { balances(state) }
        assertTrue(result.isSuccess, "balances must not throw: ${result.exceptionOrNull()}")
        assertTrue(runCatching { settlementPlan(result.getOrThrow()) }.isSuccess)
        assertTrue(runCatching { totalSpent(state) }.isSuccess)
    }
}
