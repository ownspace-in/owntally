package owntally.data.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import owntally.crypto.Clock
import owntally.crypto.KeyDerivation
import owntally.crypto.MasterSeed
import owntally.data.session.AddResult
import owntally.data.session.PeerState
import owntally.data.session.Sessions
import owntally.data.session.SyncConfig
import owntally.data.session.SyncCoordinator
import owntally.data.session.SyncRecorder
import owntally.data.store.InMemorySyncProblemLog
import owntally.data.store.ProblemKind
import owntally.model.Money
import owntally.model.Split
import owntally.sync.GroupRoom
import owntally.sync.InMemoryNetwork
import owntally.sync.JoinCodec
import owntally.sync.JoinMessage
import owntally.sync.SyncCodec
import owntally.sync.SyncMessage
import owntally.sync.SyncSummary

/**
 * F8 (docs/SECURITY_REVIEW.md; owner decision 11 Nov 2026): people who are not in a group (anyone who knows a member's public key can connect) must not make
 * a phone read the group's history from its database, nor hold up the members who are syncing. Real sessions and databases on a fake network, virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrangerLimitsTest {
    private val base = 1_800_000_000L
    private val strangers = MasterSeed(ByteArray(64) { 9 })

    private inner class World(private val scope: kotlinx.coroutines.CoroutineScope, val testClock: Clock) {
        val net = InMemoryNetwork()
        val asha = SimDevice.owner(61, "Asha")
        val ben = SimDevice.joined(61, 1, "Ben", asha).also { b -> asha.env.groups.rawEnvelopes(asha.group).forEach { owntally.model.Envelope.decode(it).let { e -> b.env.groups.appendEnvelope(b.group, e) } } }
        val room = GroupRoom.fromGroupId(asha.group.bytes())
        fun coordinator(d: SimDevice): SyncCoordinator {
            val sessions = Sessions(d.env.groups, d.env.secrets, d.env.identity, d.env.clock, d.env.settings)
            return SyncCoordinator(sessions, d.env.groups, { _, self -> net.transportFor(self) }, SyncRecorder(InMemorySyncProblemLog(), testClock), testClock, scope)
        }
        // Asha may invite, so she answers people who hold an invitation; the join conversations strangers start are what the gate limits.
        private val ashaSessions = Sessions(asha.env.groups, asha.env.secrets, asha.env.identity, asha.env.clock, asha.env.settings)
        private val invites = owntally.data.session.Invites(owntally.crypto.InMemoryTicketRecords(), asha.env.groups, asha.env.secrets, asha.env.identity, ashaSessions, asha.env.clock)
        private val requests = owntally.data.session.JoinRequests(owntally.data.store.InMemoryJoinRequestStore(), ashaSessions, asha.env.clock)
        private val backend = owntally.data.session.GroupJoinBackend(asha.group, asha.env.identity.groupIdentity(asha.env.groups.group(asha.group)!!.keySlot)!!, invites, requests, ashaSessions)
        val ashaCoordinator = SyncCoordinator(ashaSessions, asha.env.groups, { _, self -> net.transportFor(self) }, SyncRecorder(InMemorySyncProblemLog(), testClock), testClock, scope, joinBackend = { backend })
        val benCoordinator = coordinator(ben)
        fun stranger(n: Long) = KeyDerivation.groupIdentity(strangers, n).publicKey
        /** A stranger's connection to Asha; [closed] turns true when Asha ends it. */
        inner class Caller(val link: owntally.sync.Link) { @Volatile var closed = false }
        suspend fun callAsha(n: Long, send: ByteArray?, scope: kotlinx.coroutines.CoroutineScope): Caller {
            val link = net.transportFor(stranger(n)).connect(room, asha.key)
            val c = Caller(link)
            scope.launch { link.received.toList(); c.closed = true }
            if (send != null) link.send(send)
            return c
        }
    }

    private fun TestScope.world() = World(backgroundScope, Clock { base + testScheduler.currentTime / 1000 })
    private val syncHello get() = SyncCodec.encode(SyncMessage.Hello(GroupRoom(ByteArray(16)), SyncSummary(emptyMap())))

    @Test fun aStrangersSyncAttemptIsRefusedAtOnceWithoutReadingTheGroupsHistory() = runTest {
        val w = world(); w.ashaCoordinator.start(w.asha.group); runCurrent()
        val before = w.asha.env.events.envelopeReads
        val hello = SyncCodec.encode(SyncMessage.Hello(w.room, SyncSummary(emptyMap())))
        val calls = (1L..20L).map { w.callAsha(it, hello, backgroundScope) }
        advanceTimeBy(1_000); runCurrent()
        assertTrue(calls.all { it.closed }, "every stranger was shown the door within a second")
        // One look at the group to make sure the stranger was not just admitted (allowed once in a while), never one per stranger.
        assertTrue(w.asha.env.events.envelopeReads - before <= 2, "the history was read ${w.asha.env.events.envelopeReads - before} times for 20 strangers")
    }

    @Test fun aMemberIsStillServedWhileStrangersKnockAndSilentOnesAreBounded() = runTest {
        val w = world(); w.ashaCoordinator.start(w.asha.group); runCurrent()
        val hello = SyncCodec.encode(SyncMessage.Hello(w.room, SyncSummary(emptyMap())))
        val silent = (100L..199L).map { w.callAsha(it, null, backgroundScope) } // 100 connections that say nothing
        val knocking = (200L..239L).map { w.callAsha(it, hello, backgroundScope) }
        runCurrent()
        assertTrue(silent.count { it.closed } >= 84, "at most 16 strangers may wait for a first message at once: ${silent.count { it.closed }} of 100 were turned away")
        assertTrue(knocking.count { it.closed } >= 30, "and the ones that did speak but are not members are sent away")
        // Asha's friend Ben is a member: his change reaches her through all of this.
        assertTrue(w.ben.session.addExpense("Taxi", Money(10_000), w.ben.key, 20_000, Split.Equal(setOf(w.ben.key, w.asha.key))) is AddResult.Added)
        w.benCoordinator.syncNow(w.ben.group)
        advanceTimeBy(5_000); runCurrent()
        w.asha.session.refresh()
        assertTrue(w.asha.session.view.value.state.expenses.values.any { it.description == "Taxi" }, "the member's entry arrived")
        // Ten seconds later the silent ones that were let wait have given up too.
        advanceTimeBy(15_000); runCurrent()
        assertTrue(silent.all { it.closed }, "none of them is kept for ever")
    }

    @Test fun strangersCannotHoldMoreThanAFewJoinConversationsAtOnce() = runTest {
        val w = world(); w.ashaCoordinator.start(w.asha.group); runCurrent()
        val joinHello = JoinCodec.encode(JoinMessage.Hello(w.room, ByteArray(16) { 7 }))
        val calls = (300L..329L).map { w.callAsha(it, joinHello, backgroundScope) } // each starts a join conversation and then says nothing
        runCurrent()
        assertTrue(calls.count { it.closed } >= 22, "at most eight such conversations at once: ${calls.count { it.closed }} of 30 were turned away")
        assertTrue(calls.count { !it.closed } in 1..8, "and some were served: ${calls.count { !it.closed }}")
        advanceTimeBy(40_000); runCurrent()
        assertTrue(calls.all { it.closed }, "the ones served give up when the stranger stays silent")
    }
}
