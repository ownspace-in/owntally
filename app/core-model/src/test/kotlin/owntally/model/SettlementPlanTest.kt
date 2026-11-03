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

class SettlementPlanTest {
    private val a = key(1)
    private val b = key(2)
    private val c = key(3)
    private val d = key(4)

    private fun randomBalances(rnd: Random): Map<PublicKey, Money> {
        val n = rnd.nextInt(2, 31)
        val amounts = (1 until n).map { rnd.nextLong(-50_000, 50_001) }
        val result = (1..n).map { key(it) }.zip(amounts + (-amounts.sum())).toMap()
        return result.mapValues { Money(it.value) }
    }

    @Test
    fun theWorkedExampleNeedsTwoPayments() {
        val plan = settlementPlan(mapOf(a to rupees(400), b to rupees(100), c to -rupees(500)))
        assertEquals(listOf(Payment(c, a, rupees(400)), Payment(c, b, rupees(100))), plan)
    }

    @Test
    fun nothingToPayWhenEveryoneIsSquare() {
        assertEquals(emptyList(), settlementPlan(emptyMap()))
        assertEquals(emptyList(), settlementPlan(mapOf(a to Money.ZERO, b to Money.ZERO)))
    }

    @Test
    fun membersWithZeroBalanceNeverAppear() {
        val plan = settlementPlan(mapOf(a to rupees(10), b to Money.ZERO, c to -rupees(10), d to Money.ZERO))
        assertEquals(listOf(Payment(c, a, rupees(10))), plan)
    }

    @Test
    fun theLargestCreditorIsMatchedWithTheLargestDebtorAndTiesGoToTheSmallerKey() {
        val plan = settlementPlan(mapOf(a to rupees(100), b to rupees(100), c to -rupees(100), d to -rupees(100)))
        assertEquals(listOf(Payment(c, a, rupees(100)), Payment(d, b, rupees(100))), plan)
    }

    @Test
    fun aPaymentIsTheSmallerOfWhatIsOwedAndWhatIsDue() {
        val plan = settlementPlan(mapOf(a to rupees(70), b to rupees(30), c to -rupees(60), d to -rupees(40)))
        // A (+70) with C (-60); then B (+30) with D (-40); then A (+10) with D (-10).
        assertEquals(listOf(Payment(c, a, rupees(60)), Payment(d, b, rupees(30)), Payment(d, a, rupees(10))), plan)
    }

    @Test
    fun balancesThatDoNotSumToZeroAreRefused() {
        assertFailsWith<IllegalArgumentException> { settlementPlan(mapOf(a to rupees(10), b to -rupees(5))) }
    }

    @Test
    fun theInputOrderDoesNotChangeThePlan() {
        val entries = listOf(a to rupees(70), b to rupees(30), c to -rupees(60), d to -rupees(40))
        assertEquals(settlementPlan(entries.toMap()), settlementPlan(entries.reversed().toMap()))
        assertEquals(settlementPlan(entries.toMap()), settlementPlan(entries.toMap()))
    }

    @Test
    fun applyingThePaymentsClearsEveryBalanceWithAtMostMembersMinusOnePayments() = runBlocking<Unit> {
        checkAll(1000, Arb.long()) { seed ->
            val balances = randomBalances(Random(seed))
            val plan = settlementPlan(balances)
            val left = balances.toMutableMap()
            for (p in plan) {
                assertTrue(p.amount.isPositive())
                assertTrue(p.from != p.to)
                left[p.from] = left.getValue(p.from) + p.amount
                left[p.to] = left.getValue(p.to) - p.amount
            }
            assertTrue(left.values.all { it == Money.ZERO }, "seed $seed")
            val involved = balances.values.count { it != Money.ZERO }
            assertTrue(plan.size <= maxOf(0, involved - 1), "seed $seed: ${plan.size} payments for $involved members")
            val appearing = plan.flatMap { listOf(it.from, it.to) }.toSet()
            assertTrue(balances.filterValues { it == Money.ZERO }.keys.none { it in appearing })
            assertEquals(plan, settlementPlan(balances.entries.shuffled(Random(seed)).associate { it.key to it.value }))
        }
    }

    @Test
    fun aRealGroupSettlesToZeroAfterThePlanIsPaid() = runBlocking<Unit> {
        checkAll(200, Arb.long()) { seed ->
            val scenario = randomScenario(Random(seed), steps = 50)
            val state = replay(logOf(scenario))
            val plan = settlementPlan(balances(state))
            // Record every suggested payment as paid by its payer, then everything must be square.
            val extended = scenario.toMutableList()
            val counters = scenario.groupBy { it.author }.mapValues { (_, v) -> v.maxOf { it.counter } }.toMutableMap()
            val seen = counters.toMap()
            for (p in plan) {
                val n = (counters[p.from] ?: 0L) + 1
                val after = seen.toMutableMap().also { if (n > 1) it[p.from] = n - 1 else it.remove(p.from) }
                extended += Event(1, p.from, n, after, 0, EventBody.SettlementRecorded(p.from, p.to, p.amount))
                counters[p.from] = n
            }
            val settled = balances(replay(logOf(extended)))
            assertTrue(settled.values.all { it == Money.ZERO }, "seed $seed: $settled")
        }
    }
}
