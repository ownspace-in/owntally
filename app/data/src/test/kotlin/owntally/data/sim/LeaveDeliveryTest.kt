package owntally.data.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import owntally.crypto.Clock
import owntally.data.session.AddResult
import owntally.data.session.LeaveProof
import owntally.data.session.Sessions
import owntally.data.session.SyncConfig
import owntally.data.session.SyncCoordinator
import owntally.data.session.SyncRecorder
import owntally.data.store.InMemorySyncProblemLog
import owntally.model.Envelope
import owntally.model.PublicKey
import owntally.sync.GroupRoom
import owntally.sync.InMemoryNetwork
import owntally.sync.SyncOutcome
import owntally.sync.SyncResult
import owntally.sync.SyncSummary
import owntally.sync.AuthorState

/** A person who left: the group is hidden and kept until the leave has reached a member, then deleted with its keys (D4, S6). */
@OptIn(ExperimentalCoroutinesApi::class)
class LeaveDeliveryTest {
    private val base = 1_800_000_000L

    private inner class Phone(val device: SimDevice, scope: kotlinx.coroutines.CoroutineScope, val net: InMemoryNetwork, clock: Clock) {
        var sessions = Sessions(device.env.groups, device.env.secrets, device.env.identity, device.env.clock, device.env.settings)
        val session get() = sessions.session(device.group)
        var coordinator = SyncCoordinator(sessions, device.env.groups, { _, self -> net.transportFor(self) }, SyncRecorder(InMemorySyncProblemLog(), clock), clock, scope, SyncConfig())
        val scope = scope
        val clock = clock
        /** The same phone after the app was closed and opened again: new objects over the same stored data. */
        fun restart() {
            sessions = Sessions(device.env.groups, device.env.secrets, device.env.identity, device.env.clock, device.env.settings).also { it.refresh() }
            coordinator = SyncCoordinator(sessions, device.env.groups, { _, self -> net.transportFor(self) }, SyncRecorder(InMemorySyncProblemLog(), clock), clock, scope, SyncConfig())
        }
        fun stored() = device.env.groups.rawEnvelopes(device.group).size
        fun keys() = device.env.secrets.loadKeyring(device.group).keyring.epochs()
        fun listed() = device.env.groups.listGroups().map { it.groupId }
    }

    private fun TestScope.phones(seed: Long): List<Phone> {
        val net = InMemoryNetwork()
        val clock = Clock { base + testScheduler.currentTime / 1000 }
        val asha = SimDevice.owner(seed, "Asha")
        val ben = SimDevice.joined(seed, 1, "Ben", asha)
        val chitra = SimDevice.joined(seed, 2, "Chitra", asha)
        val all = listOf(asha, ben, chitra)
        for (a in all) for (b in all) if (a !== b) a.env.groups.rawEnvelopes(a.group).forEach { Envelope.decode(it).let { e -> b.env.groups.appendEnvelope(b.group, e) } }
        return all.map { Phone(it, backgroundScope, net, clock) }
    }

    @Test fun aGroupThatWasLeftIsHiddenAndKeptWhileNobodyHasReceivedTheLeave() = runTest {
        val (asha, ben, _) = phones(61)
        asha.sessions.refresh()
        assertTrue(ben.sessions.leave(ben.device.group) is AddResult.Added)
        assertEquals(emptyList(), ben.sessions.summaries.value, "gone from the list at once")
        val left = ben.sessions.leftGroups().single()
        assertEquals("Simulated", left.name)
        // Nobody is listening: the attempt fails and nothing is lost.
        ben.coordinator.deliverLeaves()
        runCurrent()
        assertTrue(ben.stored() > 0 && ben.keys().isNotEmpty() && ben.listed() == listOf(ben.device.group), "events, keys and the group row are all still there")
        assertEquals(1, ben.sessions.leftGroups().size)
    }

    @Test fun onceAMemberHasTheLeaveTheGroupIsDeletedWithItsKeysAndTheOwnerChangesTheKey() = runTest {
        val (asha, ben, chitra) = phones(62)
        asha.coordinator.start(asha.device.group); chitra.coordinator.start(chitra.device.group)
        runCurrent()
        assertTrue(ben.sessions.leave(ben.device.group) is AddResult.Added)
        ben.coordinator.deliverLeaves()
        advanceTimeBy(5_000)
        assertEquals(emptyList(), ben.listed(), "the group row is gone")
        assertEquals(0, ben.stored(), "and every envelope")
        assertEquals(emptySet(), ben.keys(), "and every key")
        assertEquals(emptyList(), ben.sessions.leftGroups(), "and the mark that it was left")
        assertEquals(null, ben.device.env.settings.get("group_left_${ben.device.group.toHex()}"))
        assertTrue(ben.device.key !in asha.session.view.value.state.currentMembers, "the member received the leave")
        assertEquals(2L, asha.session.view.value.state.epoch, "and the owner's phone changed the key")
    }

    @Test fun aRestartBetweenKeepsTheGroupHiddenAndStillDelivers() = runTest {
        val (asha, ben, _) = phones(63)
        assertTrue(ben.sessions.leave(ben.device.group) is AddResult.Added)
        ben.coordinator.deliverLeaves() // Asha is not listening yet
        runCurrent()
        assertEquals(1, ben.sessions.leftGroups().size)
        ben.restart()
        assertEquals(emptyList(), ben.sessions.summaries.value, "still hidden after the app was opened again")
        assertEquals(1, ben.sessions.leftGroups().size)
        asha.coordinator.start(asha.device.group)
        runCurrent()
        ben.coordinator.deliverLeaves()
        advanceTimeBy(5_000)
        assertEquals(emptyList(), ben.listed())
        assertEquals(0, ben.stored())
    }

    @Test fun theLeaveIsDeliveredOnItsOwnWhenAMemberComesBackWithinTheNextPass() = runTest {
        val (asha, ben, _) = phones(64)
        assertTrue(ben.sessions.leave(ben.device.group) is AddResult.Added)
        ben.coordinator.startDelivery()
        runCurrent()
        assertEquals(1, ben.sessions.leftGroups().size, "nobody there yet")
        asha.coordinator.start(asha.device.group)
        advanceTimeBy(5 * 60_000 + 5_000)
        assertEquals(emptyList(), ben.listed(), "the next pass delivered it")
        ben.coordinator.stopDelivery()
    }

    // ---- what counts as proof ---------------------------------------------------------------------------------------------

    private fun result(outcome: SyncOutcome, final: SyncSummary?) = SyncResult(outcome, 0, 0, 0, emptyMap(), null, final, emptySet(), null, "")
    private fun summary(who: PublicKey, counter: Long) = SyncSummary(mapOf(who to AuthorState(counter, ByteArray(16))))

    @Test fun onlyAFinishedSyncWithAMemberWhoseAnswerShowsTheLeaveCountsAsProof() = runTest {
        val (asha, ben, _) = phones(65)
        assertTrue(ben.sessions.leave(ben.device.group) is AddResult.Added)
        val state = ben.session.view.value.state
        val me = ben.device.key
        val leave = ben.sessions.leftGroups().single().leaveCounter
        val member = asha.device.key
        assertTrue(LeaveProof.delivered(state, me, leave, member, result(SyncOutcome.COMPLETED, summary(me, leave))), "the real thing")
        assertTrue(LeaveProof.delivered(state, me, leave, member, result(SyncOutcome.COMPLETED, summary(me, leave + 3))), "more than the leave is fine")
        assertFalse(LeaveProof.delivered(state, me, leave + 5, member, result(SyncOutcome.COMPLETED, summary(me, leave + 4))), "an answer that stops short of the leave")
        assertFalse(LeaveProof.delivered(state, me, leave, member, result(SyncOutcome.COMPLETED, SyncSummary(emptyMap()))), "an answer that does not mention us")
        assertFalse(LeaveProof.delivered(state, me, leave, member, result(SyncOutcome.COMPLETED, null)), "no final summary")
        for (outcome in listOf(SyncOutcome.INTERRUPTED, SyncOutcome.TIMED_OUT, SyncOutcome.PEER_ERROR, SyncOutcome.REFUSED)) {
            assertFalse(LeaveProof.delivered(state, me, leave, member, result(outcome, summary(me, leave))), "$outcome is not proof, whatever was claimed")
        }
        assertFalse(LeaveProof.delivered(state, me, leave, PublicKey(ByteArray(32) { 9 }), result(SyncOutcome.COMPLETED, summary(me, leave))), "someone who is not a member")
        assertFalse(LeaveProof.delivered(state, me, leave, me, result(SyncOutcome.COMPLETED, summary(me, leave))), "our own phone")
    }

    @Test fun aMemberWhoGoesSilentAfterSayingItHasTheLeaveDoesNotCountAndNothingIsDeleted() = runTest {
        val (asha, ben, _) = phones(66)
        assertTrue(ben.sessions.leave(ben.device.group) is AddResult.Added)
        val leave = ben.sessions.leftGroups().single().leaveCounter
        // Asha's side is a script: a Hello that claims it already has the leave, then silence.
        val room = GroupRoom.fromGroupId(ben.device.group.bytes())
        val incoming = ben.net.transportFor(asha.device.key).incoming(room)
        backgroundScope.launch {
            incoming.collect { link ->
                link.send(owntally.sync.SyncCodec.encode(owntally.sync.SyncMessage.Hello(room, summary(ben.device.key, leave))))
            }
        }
        ben.coordinator.deliverLeaves()
        advanceTimeBy(40_000)
        assertEquals(listOf(ben.device.group), ben.listed(), "kept: a claim is not proof")
        assertTrue(ben.stored() > 0)
    }

    @Test fun deleteNowRemovesTheEventsAndKeysAndTheGroupDoesNotComeBack() = runTest {
        val (_, ben, _) = phones(67)
        assertTrue(ben.sessions.leave(ben.device.group) is AddResult.Added)
        ben.sessions.deleteLeft(ben.device.group)
        assertEquals(emptyList(), ben.listed())
        assertEquals(0, ben.stored())
        assertEquals(emptySet(), ben.keys())
        assertEquals(emptyList(), ben.sessions.leftGroups())
        ben.restart()
        assertEquals(emptyList(), ben.sessions.summaries.value, "not listed after the app was opened again")
        assertEquals(emptyList(), ben.sessions.leftGroups())
    }

    @Test fun deleteNowOnlyDeletesAGroupThatWasLeft() = runTest {
        val (_, ben, _) = phones(68)
        ben.sessions.deleteLeft(ben.device.group) // not left: nothing happens
        assertEquals(listOf(ben.device.group), ben.listed())
        assertTrue(ben.stored() > 0)
    }

}
