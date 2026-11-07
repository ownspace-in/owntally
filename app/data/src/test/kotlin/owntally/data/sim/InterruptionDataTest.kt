package owntally.data.sim

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import owntally.data.session.AddResult
import owntally.data.session.GroupSession
import owntally.model.Money
import owntally.model.Split
import owntally.sync.GroupRoom
import owntally.sync.Offered
import owntally.sync.SyncEngine
import owntally.sync.SyncOutcome
import owntally.sync.SyncRole
import owntally.sync.SyncStore

/** T5.11 on real phones (sessions and repositories): the app is killed while envelopes arrive, restarted, and nothing is lost or half-done. */
class InterruptionDataTest {
    private class Killed : RuntimeException()

    private class Dying(val inner: SyncStore, val dieAfter: Int) : SyncStore by inner {
        var stored = 0
        override fun offer(bytes: ByteArray): Offered {
            val r = inner.offer(bytes)
            if (r == Offered.Stored && ++stored >= dieAfter) throw Killed()
            return r
        }
    }

    private suspend fun TestScope.run(a: SyncStore, b: SyncStore, group: ByteArray, seed: Int): Pair<SyncOutcome?, SyncOutcome?> {
        val (la, lb) = SimNetwork(this, Random(seed)).link(owner.key, ben.key)
        val room = GroupRoom.fromGroupId(group)
        return coroutineScope {
            val x = async { runCatching { SyncEngine(room, a).run(la, SyncRole.INITIATOR).outcome }.getOrNull() }
            val y = async { runCatching { SyncEngine(room, b).run(lb, SyncRole.RESPONDER).outcome }.getOrNull() }
            x.await() to y.await()
        }
    }

    private val owner = SimDevice.owner(11, "Asha")
    private val ben = SimDevice.joined(11, 1, "Ben", owner)

    private fun write(d: SimDevice, n: Int) {
        val who = setOf(owner.key, ben.key)
        repeat(n) { i -> check(d.session.addExpense("E$i by ${d.name}", Money(10_000L + i), d.key, 20_000L + i, Split.Equal(who)) is AddResult.Added) }
    }

    @Test fun benIsKilledAfterEveryEnvelopeHeReceivesAndAfterTheRestartBothPhonesAgree() = runTest {
        write(owner, 6)
        val groupBytes = owner.group.bytes()
        val total = owner.env.groups.envelopes(owner.group).envelopes.size - ben.env.groups.envelopes(ben.group).envelopes.size
        assertTrue(total >= 6)
        var kill = 1
        while (kill <= total) {
            val phone = SimDevice.copyOf(11, ben, ben.env.groups.envelopes(ben.group).envelopes.map { it.encode() })
            val dying = Dying(phone.syncStore(), kill)
            run(owner.syncStore(), dying, groupBytes, kill)
            // The app was killed: a new process builds a new session from what is in the database.
            val restarted = GroupSession(phone.group, phone.env.groups, phone.env.secrets, phone.env.identity, phone.env.clock)
            restarted.refresh()
            val whole = restarted.view.value.state.expenses.size
            assertTrue(whole <= 6, "a half-stored envelope must not show as anything")
            val (x, y) = run(owner.syncStore(), phone.syncStore(), groupBytes, 100 + kill)
            assertEquals(SyncOutcome.COMPLETED, x)
            assertEquals(SyncOutcome.COMPLETED, y)
            restarted.refresh()
            assertEquals(owner.env.groups.envelopes(owner.group).envelopes.map { it.encode().toList() }.toSet(), phone.env.groups.envelopes(phone.group).envelopes.map { it.encode().toList() }.toSet(), "kill $kill")
            assertEquals(6, restarted.view.value.state.expenses.size, "kill $kill")
            kill += if (kill < 8) 1 else 4
        }
    }
}
