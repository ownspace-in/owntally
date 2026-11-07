package owntally.data.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import owntally.crypto.Clock
import owntally.data.session.AddResult
import owntally.data.session.PeerState
import owntally.data.session.SyncConfig
import owntally.data.session.SyncCoordinator
import owntally.data.session.SyncRecorder
import owntally.data.session.Sessions
import owntally.data.store.InMemorySyncProblemLog
import owntally.data.store.ProblemKind
import owntally.model.Money
import owntally.model.Split
import owntally.sync.GroupRoom
import owntally.sync.InMemoryNetwork
import owntally.sync.SyncCodec
import owntally.sync.SyncMessage
import owntally.sync.SyncSummary

/** The sync coordinator (T5.8): real sessions and databases on one fake network, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncCoordinatorTest {
    private val base = 1_800_000_000L

    private inner class Phone(val device: SimDevice, scope: kotlinx.coroutines.CoroutineScope, net: InMemoryNetwork, clock: Clock, config: SyncConfig, slowConnectMs: Long = 0) {
        val sessions = Sessions(device.env.groups, device.env.secrets, device.env.identity, device.env.clock, device.env.settings)
        val session get() = sessions.session(device.group)
        val log = InMemorySyncProblemLog()
        val coordinator = SyncCoordinator(sessions, device.env.groups, { _, self -> slow(net.transportFor(self), slowConnectMs) }, SyncRecorder(log, clock), clock, scope, config)
        val status get() = coordinator.status(device.group).value
        fun peer(other: Phone) = status.peers[other.device.key]
        fun epochs() = device.env.secrets.loadKeyring(device.group).keyring.epochs()
    }

    /** A transport whose connecting takes [ms] of virtual time, so a change can be made while a sync is under way. */
    private fun slow(inner: owntally.sync.Transport, ms: Long): owntally.sync.Transport = if (ms == 0L) inner else object : owntally.sync.Transport {
        override suspend fun connect(room: GroupRoom, peer: owntally.model.PublicKey) = kotlinx.coroutines.delay(ms).let { inner.connect(room, peer) }
        override fun incoming(room: GroupRoom) = inner.incoming(room)
    }

    private fun TestScope.phones(seed: Long, count: Int, config: SyncConfig = SyncConfig(), slowConnectMs: Long = 0): List<Phone> {
        val net = InMemoryNetwork()
        val clock = Clock { base + testScheduler.currentTime / 1000 }
        val asha = SimDevice.owner(seed, "Asha")
        val others = (1 until count).map { SimDevice.joined(seed, it, listOf("Ben", "Chitra", "Dev")[it - 1], asha) }
        val all = listOf(asha) + others
        for (a in all) for (b in all) if (a !== b) a.env.groups.rawEnvelopes(a.group).forEach { owntally.model.Envelope.decode(it).let { e -> b.env.groups.appendEnvelope(b.group, e) } }
        return all.map { Phone(it, backgroundScope, net, clock, config, slowConnectMs) }
    }

    private fun Phone.writeExpense(text: String, with: Phone): AddResult =
        session.addExpense(text, Money(10_000), device.key, 20_000, Split.Equal(setOf(device.key, with.device.key)))

    private fun Phone.has(text: String) = session.view.value.state.expenses.values.any { it.description == text }

    @Test fun aChangeOnEitherPhoneReachesTheOtherWithoutAnyoneAskingForIt() = runTest {
        val (asha, ben) = phones(41, 2)
        asha.coordinator.start(asha.device.group); ben.coordinator.start(ben.device.group)
        runCurrent()
        assertTrue(asha.writeExpense("Lunch", ben) is AddResult.Added)
        advanceTimeBy(5_000)
        assertTrue(ben.has("Lunch"), "after Asha's change")
        assertTrue(ben.writeExpense("Taxi", asha) is AddResult.Added)
        advanceTimeBy(5_000)
        assertTrue(asha.has("Taxi"), "after Ben's change")
        assertEquals(asha.session.view.value.balances, ben.session.view.value.balances)
    }

    @Test fun twoChangesInQuickSuccessionBothArrive() = runTest {
        val (asha, ben) = phones(42, 2)
        asha.coordinator.start(asha.device.group); ben.coordinator.start(ben.device.group)
        runCurrent()
        asha.writeExpense("One", ben); asha.writeExpense("Two", ben)
        advanceTimeBy(1)
        asha.writeExpense("Three", ben)
        advanceTimeBy(10_000)
        assertTrue(ben.has("One") && ben.has("Two") && ben.has("Three"))
        assertTrue(asha.peer(ben)!!.attempts <= 6, "not a sync for every keystroke: ${asha.peer(ben)!!.attempts}")
    }

    @Test fun aChangeMadeWhileASyncIsUnderWayIsSentByTheNextOneAndNotLost() = runTest {
        val (asha, ben) = phones(50, 2, slowConnectMs = 100)
        ben.coordinator.start(ben.device.group)
        asha.coordinator.start(asha.device.group)
        advanceTimeBy(1_000) // both phones' start-up syncs are over
        asha.writeExpense("Before", ben) // starts a sync that takes 100 ms to connect
        advanceTimeBy(40)
        asha.writeExpense("During", ben) // arrives while it is under way: after the snapshot the sync works from
        advanceTimeBy(2_000)
        assertTrue(ben.has("Before"))
        assertTrue(ben.has("During"), "the change made during the sync is not lost")
    }

    @Test fun everyFewMinutesWhileOpenAPhoneChecksAgain() = runTest {
        val (asha, ben) = phones(43, 2)
        asha.coordinator.start(asha.device.group); ben.coordinator.start(ben.device.group)
        runCurrent()
        val first = asha.peer(ben)!!.lastAttemptSeconds!!
        advanceTimeBy(5 * 60_000 + 1_000)
        assertTrue(asha.peer(ben)!!.lastAttemptSeconds!! >= first + 300, "a second pass after five minutes")
        advanceTimeBy(5 * 60_000)
        assertTrue(asha.peer(ben)!!.lastAttemptSeconds!! >= first + 600, "and a third")
    }

    @Test fun aPeerThatCannotBeReachedIsTriedAgainAfterOneFiveAndThirtyMinutesThenEveryThirty() = runTest {
        val (asha, ben) = phones(44, 2)
        asha.coordinator.start(asha.device.group) // Ben's phone is not listening
        runCurrent()
        fun p() = asha.peer(ben)!!
        assertEquals(1, p().attempts); assertEquals(PeerState.UNREACHABLE, p().state); assertEquals(base + 60, p().nextTrySeconds)
        advanceTimeBy(59_000); assertEquals(1, p().attempts, "not before a minute")
        advanceTimeBy(2_000); assertEquals(2, p().attempts); assertEquals(base + 60 + 300, p().nextTrySeconds, "then five minutes")
        advanceTimeBy(301_000); assertEquals(3, p().attempts); assertEquals(base + 360 + 1_800, p().nextTrySeconds, "then thirty")
        advanceTimeBy(1_801_000); assertEquals(4, p().attempts); assertEquals(base + 2_160 + 1_800, p().nextTrySeconds, "and again thirty")
        // Ben comes back: the next try works and the failures are forgotten.
        ben.coordinator.start(ben.device.group)
        runCurrent()
        advanceTimeBy(1_801_000)
        assertEquals(PeerState.OK, p().state)
        assertEquals(0, p().failures)
        assertNull(p().nextTrySeconds)
    }

    @Test fun syncNowIgnoresTheWaitAndWorksAsSoonAsThePeerIsThere() = runTest {
        val (asha, ben) = phones(45, 2)
        asha.coordinator.start(asha.device.group)
        runCurrent()
        assertEquals(PeerState.UNREACHABLE, asha.peer(ben)!!.state)
        ben.coordinator.start(ben.device.group)
        runCurrent()
        asha.writeExpense("Lunch", ben)
        asha.coordinator.syncNow(asha.device.group)
        runCurrent()
        assertTrue(ben.has("Lunch"), "without waiting for the retry")
        assertEquals(PeerState.OK, asha.peer(ben)!!.state)
    }

    @Test fun aLeaveWrittenOnOnePhoneMakesTheOwnersPhoneChangeTheKeyByItselfAndTheNewKeyReachesOnlyThoseWhoStay() = runTest {
        val (asha, ben, chitra) = phones(46, 3)
        listOf(asha, ben, chitra).forEach { it.coordinator.start(it.device.group) }
        runCurrent()
        assertTrue(ben.session.leave() is AddResult.Added)
        advanceTimeBy(30_000)
        assertEquals(2L, asha.session.view.value.state.epoch, "the owner's phone changed the key by itself")
        assertTrue(ben.device.key !in asha.session.view.value.state.currentMembers)
        assertEquals(setOf(1L, 2L), chitra.epochs(), "Chitra received the new key")
        assertEquals(setOf(1L), ben.epochs(), "the leaver did not")
        asha.writeExpense("After", chitra)
        advanceTimeBy(30_000)
        assertTrue(chitra.has("After"))
        assertTrue(!ben.has("After"), "and the leaver reads nothing written after")
    }

    @Test fun theStatusShowsWhoWasReachedAndWhenAndWhatIsNotShared() = runTest {
        val (asha, ben) = phones(47, 2)
        assertTrue(asha.status.peers.isEmpty() || asha.status.peers.values.all { it.state == PeerState.NEVER })
        asha.coordinator.start(asha.device.group); ben.coordinator.start(ben.device.group)
        runCurrent()
        advanceTimeBy(2_000)
        val p = asha.peer(ben)!!
        assertEquals(PeerState.OK, p.state)
        assertNotNull(p.lastSuccessSeconds)
        assertEquals(0, p.notShared, "everything is shared")
        assertEquals(0, asha.status.syncingCount)
    }

    @Test fun envelopesThatWereDroppedProduceOneNoticeAndALogLineAndTheNoticeCanBeDismissed() = runTest {
        val net = InMemoryNetwork()
        val clock = Clock { base + testScheduler.currentTime / 1000 }
        val asha = SimDevice.owner(48, "Asha")
        val ben = SimDevice.joined(48, 1, "Ben", asha)
        asha.env.groups.rawEnvelopes(asha.group).forEach { owntally.model.Envelope.decode(it).let { e -> ben.env.groups.appendEnvelope(ben.group, e) } }
        val phone = Phone(asha, backgroundScope, net, clock, SyncConfig())
        phone.coordinator.start(asha.group)
        runCurrent()
        // Ben's phone (not running a coordinator) connects and sends a forged envelope and junk.
        val room = GroupRoom.fromGroupId(asha.group.bytes())
        val link = net.transportFor(ben.key).connect(room, asha.key)
        val real = asha.env.groups.rawEnvelopes(asha.group).first()
        val forged = real.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        backgroundScope.launch {
            link.send(SyncCodec.encode(SyncMessage.Hello(room, SyncSummary(emptyMap()))))
            link.send(SyncCodec.encode(SyncMessage.Envelopes(listOf(forged, ByteArray(60) { 3 }))))
            link.send(SyncCodec.encode(SyncMessage.Done(SyncSummary(emptyMap()))))
        }
        advanceTimeBy(5_000)
        val notice = phone.status.notice
        assertNotNull(notice)
        assertEquals(2, notice.total)
        assertEquals(ben.key, notice.peer)
        assertTrue(phone.log.recent(asha.group, 10).any { it.kind == ProblemKind.BAD_SIGNATURE })
        phone.coordinator.dismissNotice(asha.group)
        assertNull(phone.status.notice)
    }

    @Test fun aBackgroundPassSyncsEveryGroupOnceAndReturns() = runTest {
        val (asha, ben) = phones(49, 2)
        ben.coordinator.start(ben.device.group) // Ben is open and listening; Asha's phone only wakes for one pass
        runCurrent()
        asha.writeExpense("Lunch", ben)
        asha.coordinator.runOnce()
        advanceTimeBy(2_000)
        assertTrue(ben.has("Lunch"))
        assertEquals(PeerState.OK, asha.peer(ben)!!.state)
    }


    @Test fun withNoWayToReachOtherPhonesYetEveryMemberIsNotReachableYetAndNothingIsRetried() = runTest {
        val net = InMemoryNetwork()
        val clock = Clock { base + testScheduler.currentTime / 1000 }
        val asha = SimDevice.owner(51, "Asha")
        SimDevice.joined(51, 1, "Ben", asha)
        val sessions = Sessions(asha.env.groups, asha.env.secrets, asha.env.identity, asha.env.clock, asha.env.settings)
        val coordinator = SyncCoordinator(sessions, asha.env.groups, { _, _ -> owntally.sync.NoTransport }, SyncRecorder(InMemorySyncProblemLog(), clock), clock, backgroundScope, SyncConfig())
        sessions.session(asha.group).refresh()
        coordinator.start(asha.group)
        runCurrent()
        coordinator.syncNow(asha.group)
        advanceTimeBy(2 * 3_600_000L)
        val peer = coordinator.status(asha.group).value.peers.values.single()
        assertEquals(PeerState.NEVER, peer.state)
        assertNull(peer.nextTrySeconds, "not a failure: no waiting time")
        assertEquals(0, peer.failures)
        // Only the start, "Sync now" and the five-minute passes try; no retry job is scheduled (a retry would add tries between the passes).
        assertTrue(peer.attempts <= 2 + 2 * 60 / 5 + 1, "tries: ${peer.attempts}")
    }

    @Test fun theStatusRemembersWhichKindOfLinkWasUsed() = runTest {
        val (asha, ben) = phones(52, 2)
        asha.coordinator.start(asha.device.group); ben.coordinator.start(ben.device.group)
        runCurrent()
        advanceTimeBy(2_000)
        assertEquals(owntally.sync.LinkKind.DIRECT, asha.peer(ben)!!.kind)
        assertEquals(owntally.sync.LinkKind.DIRECT, ben.peer(asha)!!.kind)
    }

    @Test fun theLatestDroppedEntriesCanBeReadBackForTheNote() = runTest {
        val (asha, _) = phones(53, 2)
        assertEquals(emptyList(), asha.coordinator.problems(asha.device.group))
    }
}

