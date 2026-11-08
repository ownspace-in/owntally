package owntally.data.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import owntally.crypto.Clock
import owntally.data.session.AddResult
import owntally.data.session.NearbyPeer
import owntally.data.session.NearbySession
import owntally.data.session.Sessions
import owntally.data.session.SyncCoordinator
import owntally.data.session.SyncRecorder
import owntally.data.store.InMemorySyncProblemLog
import owntally.model.Money
import owntally.model.PublicKey
import owntally.model.Split
import owntally.sync.GroupRoom
import owntally.sync.InMemoryNetwork
import owntally.sync.Link
import owntally.sync.LinkKind
import owntally.sync.PeerUnreachable
import owntally.sync.Transport
import owntally.sync.TransportUnavailable

/** T6.11: the Nearby session on real sessions and databases, over a pretend air with the real "smaller key calls" rule. */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbySessionTest {
    private val base = 1_800_000_000L

    /** Nearby's rule: of two phones the one with the smaller key opens the connection. */
    private class Rule(private val inner: Transport, private val self: PublicKey, private val absent: Set<PublicKey> = emptySet()) : Transport {
        override suspend fun connect(room: GroupRoom, peer: PublicKey): Link {
            if (self > peer) throw TransportUnavailable()
            if (peer in absent) throw PeerUnreachable(peer)
            // Like Nearby, wait for the other phone to show up (up to 15 seconds) before giving up.
            var waited = 0L
            while (true) {
                try {
                    return inner.connect(room, peer)
                } catch (e: PeerUnreachable) {
                    if (waited >= 15_000) throw e
                    kotlinx.coroutines.delay(250); waited += 250
                }
            }
        }
        override fun incoming(room: GroupRoom) = inner.incoming(room)
    }

    private inner class Phone(val device: SimDevice, scope: kotlinx.coroutines.CoroutineScope, net: InMemoryNetwork, clock: Clock, absent: Set<PublicKey> = emptySet()) {
        val sessions = Sessions(device.env.groups, device.env.secrets, device.env.identity, device.env.clock, device.env.settings)
        val coordinator = SyncCoordinator(sessions, device.env.groups, { _, self -> Rule(net.transportFor(self, LinkKind.NEARBY), self, absent) }, SyncRecorder(InMemorySyncProblemLog(), clock), clock, scope)
        val nearby = NearbySession(device.group, sessions, coordinator, Rule(net.transportFor(device.key, LinkKind.NEARBY), device.key, absent), scope, timeoutMs = 15_000)
        fun has(text: String) = sessions.session(device.group).view.value.state.expenses.values.any { it.description == text }
    }

    private fun TestScope.phones(count: Int, absentFor: (Int) -> Set<PublicKey> = { emptySet() }): List<Phone> {
        val net = InMemoryNetwork()
        val clock = Clock { base + testScheduler.currentTime / 1000 }
        val asha = SimDevice.owner(81, "Asha")
        val others = (1 until count).map { SimDevice.joined(81, it, listOf("Ben", "Chitra")[it - 1], asha) }
        val all = listOf(asha) + others
        for (a in all) for (b in all) if (a !== b) a.env.groups.rawEnvelopes(a.group).forEach { owntally.model.Envelope.decode(it).let { e -> b.env.groups.appendEnvelope(b.group, e) } }
        return all.mapIndexed { i, d -> Phone(d, backgroundScope, net, clock, absentFor(i)) }
    }

    private fun Phone.write(text: String, with: Phone) = sessions.session(device.group).addExpense(text, Money(10_000), device.key, 20_000, Split.Equal(setOf(device.key, with.device.key)))

    @Test fun twoPhonesThatBothStartNearbyFindEachOtherSyncAndSayDone() = runTest {
        val (a, b) = phones(2)
        assertTrue(a.write("Dinner", b) is AddResult.Added); assertTrue(b.write("Taxi", a) is AddResult.Added)
        a.nearby.start(); b.nearby.start()
        runCurrent(); advanceTimeBy(5_000)
        assertEquals(mapOf(b.device.key to NearbyPeer.DONE), a.nearby.status.value.peers)
        assertEquals(mapOf(a.device.key to NearbyPeer.DONE), b.nearby.status.value.peers)
        a.sessions.session(a.device.group).refresh(); b.sessions.session(b.device.group).refresh()
        assertTrue(a.has("Taxi") && b.has("Dinner"), "both entries crossed")
    }

    @Test fun aMemberWhoNeverShowsUpIsMarkedNotFoundAfterTheWaitAndTheSessionKeepsListening() = runTest {
        val (a, b) = phones(2)
        a.nearby.start()
        runCurrent(); advanceTimeBy(14_000)
        val first = a.nearby.status.value
        assertTrue(first.running); assertEquals(mapOf(b.device.key to NearbyPeer.LOOKING), first.peers)
        advanceTimeBy(2_000)
        assertEquals(NearbyPeer.NOT_FOUND, a.nearby.status.value.peers[b.device.key])
        assertTrue(a.nearby.status.value.running, "still running")
        // They turn Nearby on late: found after all.
        b.nearby.start()
        a.nearby.lookAgain()
        assertEquals(NearbyPeer.LOOKING, a.nearby.status.value.peers[b.device.key])
        advanceTimeBy(5_000)
        assertEquals(NearbyPeer.DONE, a.nearby.status.value.peers[b.device.key])
    }

    @Test fun aMemberNotFoundIsMarkedNotFoundWhicheverPhoneHasTheLargerKey() = runTest {
        val (x, y) = phones(2)
        // One of them holds the larger key and so waits to be called: it can only learn "not found" from the wait running out.
        for (lonely in listOf(x, y)) {
            lonely.nearby.start()
            runCurrent(); advanceTimeBy(16_000)
            assertEquals(setOf(NearbyPeer.NOT_FOUND), lonely.nearby.status.value.peers.values.toSet(), lonely.device.name)
            lonely.nearby.stop()
            lonely.nearby.lookAgain() // stopped: does nothing, not even for a member who was not found
            assertFalse(lonely.nearby.status.value.running)
            assertEquals(setOf(NearbyPeer.NOT_FOUND), lonely.nearby.status.value.peers.values.toSet())
        }
    }

    @Test fun aSyncThatDoesNotFinishIsMarkedFailedAndCanBeTriedAgain() = runTest {
        val net = InMemoryNetwork()
        val clock = Clock { base + testScheduler.currentTime / 1000 }
        val asha = SimDevice.owner(82, "Asha")
        val ben = SimDevice.joined(82, 1, "Ben", asha)
        asha.env.groups.rawEnvelopes(asha.group).forEach { ben.env.groups.appendEnvelope(ben.group, owntally.model.Envelope.decode(it)) }
        val smaller = if (asha.key < ben.key) asha else ben
        val sessions = Sessions(smaller.env.groups, smaller.env.secrets, smaller.env.identity, smaller.env.clock, smaller.env.settings)
        val coordinator = SyncCoordinator(sessions, smaller.env.groups, { _, self -> net.transportFor(self) }, SyncRecorder(InMemorySyncProblemLog(), clock), clock, backgroundScope)
        val other = if (smaller === asha) ben else asha
        var cut = true
        val broken = object : Transport {
            override suspend fun connect(room: GroupRoom, peer: PublicKey): Link = if (cut) object : Link {
                override val peer = peer
                override val kind = LinkKind.NEARBY
                override suspend fun send(bytes: ByteArray) { throw java.io.IOException("the connection dropped") }
                override val received = kotlinx.coroutines.flow.emptyFlow<ByteArray>()
                override suspend fun close() = Unit
            } else net.transportFor(smaller.key, LinkKind.NEARBY).connect(room, peer)
            override fun incoming(room: GroupRoom) = net.transportFor(smaller.key, LinkKind.NEARBY).incoming(room)
        }
        val session = NearbySession(smaller.group, sessions, coordinator, broken, backgroundScope, timeoutMs = 15_000)
        session.start(); runCurrent(); advanceTimeBy(2_000)
        assertEquals(NearbyPeer.FAILED, session.status.value.peers[other.key], "a sync that did not finish is not done")
        // They come back: look again.
        cut = false
        val answering = Phone(other, backgroundScope, net, clock)
        answering.nearby.start()
        session.lookAgain(); runCurrent(); advanceTimeBy(5_000)
        assertEquals(NearbyPeer.DONE, session.status.value.peers[other.key])
    }

    @Test fun lookingAgainDoesNothingWhenStoppedOrWhenEveryoneIsDone() = runTest {
        val (a, b) = phones(2)
        a.nearby.lookAgain() // never started
        assertFalse(a.nearby.status.value.running)
        a.nearby.start(); b.nearby.start(); runCurrent(); advanceTimeBy(5_000)
        a.nearby.lookAgain()
        assertEquals(NearbyPeer.DONE, a.nearby.status.value.peers[b.device.key], "done stays done")
        a.nearby.stop()
        a.nearby.lookAgain()
        assertFalse(a.nearby.status.value.running)
    }

    @Test fun stoppingEndsTheSessionAndNothingMoreIsSynced() = runTest {
        val (a, b) = phones(2)
        a.nearby.start(); b.nearby.start(); runCurrent(); advanceTimeBy(5_000)
        a.nearby.stop(); b.nearby.stop()
        assertFalse(a.nearby.status.value.running)
        assertTrue(b.write("After", a) is AddResult.Added)
        advanceTimeBy(60_000)
        assertFalse(a.has("After"))
    }

    @Test fun threePhonesAllSyncWithEachOther() = runTest {
        val all = phones(3)
        all[1].write("From Ben", all[0])
        all.forEach { it.nearby.start() }
        runCurrent(); advanceTimeBy(10_000)
        all.forEach { p -> assertEquals(setOf(NearbyPeer.DONE), p.nearby.status.value.peers.values.toSet(), p.device.name) }
        all.forEach { it.sessions.session(it.device.group).refresh() }
        assertTrue(all.all { it.has("From Ben") })
    }

    @Test fun whenThereIsNothingToSyncWithAndNoGroupTheSessionDoesNotStart() = runTest {
        val (a) = phones(1)
        a.nearby.start()
        assertEquals(emptyMap(), a.nearby.status.value.peers)
    }
}
