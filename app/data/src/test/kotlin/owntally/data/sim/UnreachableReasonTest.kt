package owntally.data.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import owntally.crypto.Clock
import owntally.data.session.PeerState
import owntally.data.session.Sessions
import owntally.data.session.SyncCoordinator
import owntally.data.session.SyncRecorder
import owntally.data.store.InMemorySyncProblemLog
import owntally.model.PublicKey
import owntally.sync.ChainUnreachable
import owntally.sync.GroupRoom
import owntally.sync.InMemoryNetwork
import owntally.sync.RelayResult
import owntally.sync.Transport

/** T7.6: the status remembers why a member could not be reached (the relay's part in it), and "Try now" tries one member at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnreachableReasonTest {
    private val base = 1_800_000_000L

    private class Flaky(private val inner: Transport) : Transport {
        @Volatile var up = false
        @Volatile var reason = RelayResult.PEER_NOT_THERE
        var attempts = 0
        override suspend fun connect(room: GroupRoom, peer: PublicKey) = if (up) inner.connect(room, peer) else { attempts++; throw ChainUnreachable(peer, reason, null) }
        override fun incoming(room: GroupRoom) = inner.incoming(room)
    }

    private class World(val coordinator: SyncCoordinator, val flaky: Flaky, val asha: SimDevice, val ben: SimDevice, val benCoordinator: SyncCoordinator) {
        val group get() = asha.group
        val status get() = coordinator.status(group).value.peers[ben.key]
    }

    private fun TestScope.world(): World {
        val net = InMemoryNetwork()
        val clock = Clock { base + testScheduler.currentTime / 1000 }
        val asha = SimDevice.owner(95, "Asha"); val ben = SimDevice.joined(95, 1, "Ben", asha)
        for (a in listOf(asha, ben)) for (b in listOf(asha, ben)) if (a !== b) a.env.groups.rawEnvelopes(a.group).forEach { owntally.model.Envelope.decode(it).let { e -> b.env.groups.appendEnvelope(b.group, e) } }
        val flaky = Flaky(net.transportFor(asha.key))
        fun coordinator(d: SimDevice, t: (PublicKey) -> Transport) = SyncCoordinator(
            Sessions(d.env.groups, d.env.secrets, d.env.identity, d.env.clock, d.env.settings), d.env.groups, { _, self -> t(self) },
            SyncRecorder(InMemorySyncProblemLog(), clock), clock, backgroundScope,
        )
        val ca = coordinator(asha) { flaky }
        val cb = coordinator(ben) { net.transportFor(it) }
        return World(ca, flaky, asha, ben, cb)
    }

    @Test fun theStatusRemembersWhetherTheRelayWasDownOrTheFriendWasNotThere() = runTest {
        val w = world()
        w.flaky.reason = RelayResult.RELAY_DOWN
        w.coordinator.start(w.group); runCurrent()
        assertEquals(PeerState.UNREACHABLE, w.status!!.state)
        assertEquals(RelayResult.RELAY_DOWN, w.status!!.relay)
        w.flaky.reason = RelayResult.PEER_NOT_THERE
        w.coordinator.syncNow(w.group, w.ben.key)
        assertEquals(RelayResult.PEER_NOT_THERE, w.status!!.relay, "the latest reason replaces the earlier one")
    }

    @Test fun theReasonIsForgottenOnceTheFriendIsReached() = runTest {
        val w = world()
        w.flaky.reason = RelayResult.RELAY_DOWN
        w.coordinator.start(w.group); runCurrent()
        w.benCoordinator.start(w.group); runCurrent()
        w.flaky.up = true
        w.coordinator.syncNow(w.group, w.ben.key); advanceTimeBy(5_000)
        assertEquals(PeerState.OK, w.status!!.state)
        assertNull(w.status!!.relay)
    }

    @Test fun tryNowForOneMemberIgnoresTheWaitAndTriesOnlyThatMember() = runTest {
        val w = world()
        w.coordinator.start(w.group); runCurrent()
        val before = w.flaky.attempts
        assertTrue(w.status!!.nextTrySeconds!! > base, "the next try is minutes away")
        w.coordinator.syncNow(w.group, w.ben.key)
        assertEquals(before + 1, w.flaky.attempts, "tried at once, once")
        // Someone who is not a member of the group is ignored.
        w.coordinator.syncNow(w.group, PublicKey(ByteArray(32) { 7 }))
        assertEquals(before + 1, w.flaky.attempts)
    }
}
