package owntally.data.sim

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import owntally.crypto.EventSealing
import owntally.crypto.OpenResult
import owntally.data.session.AddResult
import owntally.model.Envelope
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.IgnoreReason
import owntally.model.Money
import owntally.model.Split
import owntally.sync.GroupRoom
import owntally.sync.SyncEngine
import owntally.sync.SyncOutcome
import owntally.sync.SyncRole

/** T5.5 (decision S1): two versions of one event on two phones that each believe they are up to date. */
class ForkTest {
    private suspend fun TestScope.sync(a: SimDevice, b: SimDevice) {
        val (la, lb) = SimNetwork(this, Random(1)).link(a.key, b.key)
        val room = GroupRoom.fromGroupId(a.group.bytes())
        coroutineScope {
            val x = async { SyncEngine(room, a.syncStore()).run(la, SyncRole.INITIATOR) }
            val y = async { SyncEngine(room, b.syncStore()).run(lb, SyncRole.RESPONDER) }
            assertEquals(SyncOutcome.COMPLETED, x.await().outcome)
            assertEquals(SyncOutcome.COMPLETED, y.await().outcome)
        }
        a.session.refresh()
        b.session.refresh()
    }

    @Test fun twoVersionsOfOneEventAreFoundExchangedAndBothIgnoredSoEveryoneAgrees() = runTest {
        val asha = SimDevice.owner(7, "Asha")
        val ben = SimDevice.joined(7, 1, "Ben", asha)
        val chitra = SimDevice.joined(7, 2, "Chitra", asha)
        sync(asha, ben); sync(asha, chitra); sync(ben, chitra)

        // Ben writes an expense of 300 rupees, paid by Ben and shared by all three; only Chitra gets it.
        val everyone = setOf(asha.key, ben.key, chitra.key)
        val added = ben.session.addExpense("Dinner", Money(30_000), ben.key, 20_000, Split.Equal(everyone))
        val id = (added as AddResult.Added).id
        sync(ben, chitra)

        // A different, really signed version of the same event (3,000 rupees) goes only to Asha: Ben's phone is cheating or broken.
        val ring = ben.env.secrets.loadKeyring(ben.group).keyring
        val own = ben.env.groups.envelopes(ben.group).envelopes.first { it.author == ben.key && it.counter == id.counter }
        val original = (EventSealing.open(own, ring) as OpenResult.Opened).event
        val forged = Event(original.version, original.author, original.counter, original.after, original.time, (original.body as EventBody.ExpenseAdded).copy(total = Money(300_000)))
        val secondVersion: Envelope = EventSealing.seal(ben.env.identity.groupIdentity(ben.slot)!!, ben.group, own.epoch, ring[own.epoch]!!, forged)
        asha.env.groups.appendEnvelope(asha.group, secondVersion)
        asha.session.refresh()

        // Before any sync: both phones are "up to date" with Ben, and they disagree about the money.
        assertEquals(asha.session.view.value.state.members.size, chitra.session.view.value.state.members.size)
        val owedBefore = asha.session.view.value.balances.getValue(ben.key)
        assertNotEquals(owedBefore, chitra.session.view.value.balances.getValue(ben.key), "the phones count different amounts")

        // One sync between them finds it: counters are equal, fingerprints are not.
        sync(asha, chitra)
        for (d in listOf(asha, chitra)) {
            val v = d.session.view.value
            assertEquals(IgnoreReason.DUPLICATE_ID, v.state.ignored.single { it.id == id }.reason, "${d.name} ignores the event")
            assertEquals(Money.ZERO, v.balances.getValue(ben.key), "nobody owes Ben for it")
            assertEquals(0, v.counts.damaged)
        }
        assertEquals(asha.session.view.value.balances, chitra.session.view.value.balances)
        assertEquals(2, asha.syncStore().versionsOf(ben.key).count { Envelope.decode(it).counter == id.counter })
        assertEquals(asha.syncStore().summary(), chitra.syncStore().summary(), "the same fingerprints now")

        // Ben's own phone, which holds only the first version, catches up through the others.
        sync(ben, asha)
        assertEquals(asha.syncStore().summary(), ben.syncStore().summary())
        assertEquals(asha.session.view.value.balances, ben.session.view.value.balances)
        assertTrue(ben.session.view.value.state.ignored.any { it.id == id && it.reason == IgnoreReason.DUPLICATE_ID })
    }
}
