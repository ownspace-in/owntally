package owntally.data.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Random scenarios (T5.4). By default a modest number of seeds runs, so the normal test run stays quick. For a long run set
 * OWNTALLY_SIM_SEEDS (how many) and optionally OWNTALLY_SIM_FIRST (the first seed); to repeat one failing seed, OWNTALLY_SIM_SEED.
 */
class SimulationTest {
    private fun env(name: String) = System.getenv(name)?.toLongOrNull()

    @Test
    fun everyScenarioEndsWithPhonesThatAgree() {
        val single = env("OWNTALLY_SIM_SEED")
        val seeds = if (single != null) listOf(single) else (env("OWNTALLY_SIM_FIRST") ?: 1L).let { first -> (first until first + (env("OWNTALLY_SIM_SEEDS") ?: 25L)).toList() }
        var syncs = 0
        var completed = 0
        var interrupted = 0
        var forks = 0
        val failures = ArrayList<Pair<Long, String>>()
        for (seed in seeds) {
            try {
                var r: SimReport? = null
                runTest(timeout = kotlin.time.Duration.parse("10m")) { r = Simulation(seed).run(this) }
                val report = r!!
                syncs += report.syncs
                forks += report.forks
                completed += report.outcomes[owntally.sync.SyncOutcome.COMPLETED] ?: 0
                interrupted += report.outcomes.filterKeys { it != owntally.sync.SyncOutcome.COMPLETED }.values.sum()
            } catch (e: Throwable) {
                failures += seed to (if (e is SimFailure) e.message!! else "${e::class.simpleName}: ${e.message}\n${e.stackTraceToString().take(1500)}")
            }
        }
        if (failures.isNotEmpty()) {
            val first = failures.first()
            throw AssertionError("${failures.size} of ${seeds.size} scenarios failed: seeds ${failures.map { it.first }}\nFirst failure:\n${first.second}\nRepeat with OWNTALLY_SIM_SEED=${first.first}")
        }
        println("simulation: ${seeds.size} seeds, $syncs syncs ($completed sides completed, $interrupted sides interrupted, refused or timed out), $forks forked events")
        assertTrue(syncs > 0)
        assertEquals(true, interrupted > 0 || seeds.size < 5, "the faults really interrupted syncs")
    }
}
