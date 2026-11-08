package owntally.data.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import owntally.crypto.Clock
import owntally.data.repo.TestEnv
import owntally.data.session.AddResult
import owntally.data.session.ApproveResult
import owntally.data.session.CheckResult
import owntally.data.session.GroupJoinBackend
import owntally.data.session.Invites
import owntally.data.session.JoinGroup
import owntally.data.session.JoinRequests
import owntally.data.session.OpenResult
import owntally.data.session.RequestResult
import owntally.data.session.Sessions
import owntally.data.session.SyncCoordinator
import owntally.data.session.SyncRecorder
import owntally.data.store.InMemoryJoinRequestStore
import owntally.data.store.InMemoryPendingJoinStore
import owntally.data.store.InMemorySyncProblemLog
import owntally.model.GroupId
import owntally.model.Money
import owntally.model.Split
import owntally.sync.InMemoryNetwork

/**
 * T6.6: joining through the real coordinators. The host phone answers joins and syncs on the same incoming links; a person who joins
 * gets the history by the sync that approval starts. Virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinThroughCoordinatorTest {
    private val base = 1_800_000_000L

    /** A phone that can be called but cannot call out (behind a firewall): so anything it learns, it learns from callers. */
    private fun cannotDial(inner: owntally.sync.Transport) = object : owntally.sync.Transport {
        override suspend fun connect(room: owntally.sync.GroupRoom, peer: owntally.model.PublicKey): owntally.sync.Link = throw owntally.sync.PeerUnreachable(peer)
        override fun incoming(room: owntally.sync.GroupRoom) = inner.incoming(room)
    }

    private inner class World(scope: TestScope, hostMayInvite: Boolean = true, hostCanDial: Boolean = true) {
        val net = InMemoryNetwork()
        val clock = Clock { base + scope.testScheduler.currentTime / 1000 }
        val asha = SimDevice.owner(61, "Asha")
        val group: GroupId = asha.group
        val ashaSessions = Sessions(asha.env.groups, asha.env.secrets, asha.env.identity, asha.env.clock, asha.env.settings)
        val invites = Invites(owntally.crypto.InMemoryTicketRecords(), asha.env.groups, asha.env.secrets, asha.env.identity, ashaSessions, clock)
        val requests = JoinRequests(InMemoryJoinRequestStore(), ashaSessions, clock)
        val ashaSync = SyncCoordinator(
            ashaSessions, asha.env.groups, { _, self -> if (hostCanDial) net.transportFor(self) else cannotDial(net.transportFor(self)) }, SyncRecorder(InMemorySyncProblemLog(), clock), clock, scope.backgroundScope,
            joinBackend = { id ->
                if (!hostMayInvite || !invites.canInvite(id)) null
                else GroupJoinBackend(id, asha.env.identity.groupIdentity(asha.env.groups.group(id)!!.keySlot)!!, invites, requests, ashaSessions)
            },
        )

        val rohanEnv = TestEnv().also { it.firstRun("Rohan", "98765 43210") }
        val rohanSessions = Sessions(rohanEnv.groups, rohanEnv.secrets, rohanEnv.identity, rohanEnv.clock, rohanEnv.settings)
        val rohanSync = SyncCoordinator(rohanSessions, rohanEnv.groups, { _, self -> net.transportFor(self) }, SyncRecorder(InMemorySyncProblemLog(), clock), clock, scope.backgroundScope)
        val rohanJoin = JoinGroup(
            rohanEnv.groups, rohanEnv.secrets, rohanEnv.identity, InMemoryPendingJoinStore(), rohanSessions, clock, { _, self -> net.transportFor(self) },
            onApproved = { g, host -> rohanSync.startWith(g, host) }, connectTimeoutMs = 1_000,
        )
    }

    @Test fun aPersonJoinsThroughTheHostsCoordinatorIsApprovedAndGetsTheHistoryBySyncEvenIfTheHostCannotCallOut() = runTest {
        val w = World(this, hostCanDial = false)
        w.ashaSync.start(w.group)
        assertTrue(w.ashaSessions.session(w.group).addExpense("Dinner", Money(30_000), w.asha.key, 20_000, Split.Equal(setOf(w.asha.key))) is AddResult.Added)
        runCurrent()

        val link = w.invites.create(w.group)!!.link
        val opened = assertIs<OpenResult.Opened>(w.rohanJoin.open(link))
        assertEquals("Simulated", opened.join.groupName)
        assertEquals(RequestResult.Waiting, w.rohanJoin.request(w.group, "Rohan V", "98765 43210"))
        val waiting = w.requests.waiting(w.group).single()
        assertEquals("Rohan V", waiting.name)
        assertEquals(CheckResult.StillWaiting, w.rohanJoin.check(w.group))

        assertTrue(w.requests.approve(w.group, waiting.member, "Rohan V") is ApproveResult.Approved)
        assertEquals(CheckResult.Approved, w.rohanJoin.check(w.group))
        advanceTimeBy(10_000) // the sync that approval starts
        w.rohanSessions.session(w.group).refresh()
        val view = w.rohanSessions.session(w.group).view.value
        assertTrue(view.state.expenses.values.any { it.description == "Dinner" }, "the history arrived")
        assertEquals("Rohan V", view.state.members[view.me]!!.name)
        assertTrue(w.rohanJoin.completeIfMember(w.group))
        assertEquals(w.asha.session.view.value.balances.keys, view.balances.keys)
    }

    @Test fun anExistingMemberSyncsOnTheSameIncomingLinksWhileSomeoneJoins() = runTest {
        val w = World(this)
        val ben = SimDevice.joined(61, 1, "Ben", w.asha)
        w.asha.env.groups.rawEnvelopes(w.group).forEach { ben.env.groups.appendEnvelope(ben.group, owntally.model.Envelope.decode(it)) }
        val benSessions = Sessions(ben.env.groups, ben.env.secrets, ben.env.identity, ben.env.clock, ben.env.settings)
        val benSync = SyncCoordinator(benSessions, ben.env.groups, { _, self -> w.net.transportFor(self) }, SyncRecorder(InMemorySyncProblemLog(), w.clock), w.clock, backgroundScope)
        w.ashaSync.start(w.group); benSync.start(w.group)
        runCurrent()

        val link = w.invites.create(w.group)!!.link
        assertIs<OpenResult.Opened>(w.rohanJoin.open(link))
        assertEquals(RequestResult.Waiting, w.rohanJoin.request(w.group, "Rohan", "98765 43210"))
        assertTrue(w.ashaSessions.session(w.group).addExpense("Taxi", Money(10_000), w.asha.key, 20_000, Split.Equal(setOf(w.asha.key, ben.key))) is AddResult.Added)
        advanceTimeBy(10_000)
        benSessions.session(w.group).refresh()
        assertTrue(benSessions.session(w.group).view.value.state.expenses.values.any { it.description == "Taxi" }, "Ben still synced")
        assertEquals(1, w.requests.waiting(w.group).size)
    }

    @Test fun aPhoneWhereThePersonMayNotInviteNeverAnswersAJoin() = runTest {
        val w = World(this, hostMayInvite = false)
        w.ashaSync.start(w.group)
        val link = w.invites.create(w.group)!!.link
        val opened = assertIs<OpenResult.Opened>(w.rohanJoin.open(link))
        assertEquals(owntally.data.session.PreviewState.HOST_UNREACHABLE, opened.preview)
        assertEquals(RequestResult.HostUnreachable, w.rohanJoin.request(w.group, "Rohan", "98765 43210"))
        assertEquals(0, w.requests.waiting(w.group).size)
        assertNotNull(w.invites.current(w.group), "and the invite is untouched")
    }

    @Test fun aConnectionThatSaysNothingDoesNotTieUpTheHostForever() = runTest {
        val w = World(this)
        w.ashaSync.start(w.group)
        runCurrent()
        val silent = w.net.transportFor(w.rohanEnv.identity.groupIdentity(5)!!.publicKey).connect(owntally.sync.GroupRoom.fromGroupId(w.group.bytes()), w.asha.key)
        advanceTimeBy(60_000)
        // The host gave up on it and still answers the next person.
        val link = w.invites.create(w.group)!!.link
        assertIs<OpenResult.Opened>(w.rohanJoin.open(link))
        assertEquals(RequestResult.Waiting, w.rohanJoin.request(w.group, "Rohan", "98765 43210"))
        assertFalse(silent.toString().isEmpty())
    }

    @Test fun theIntroducerIsTrustedOnlyUntilTheNewMembersOwnAdmissionHasArrived() = runTest {
        val w = World(this)
        val stranger = owntally.model.PublicKey(ByteArray(32) { 9 })
        w.ashaSync.start(w.group)
        runCurrent()
        val link = w.invites.create(w.group)!!.link
        assertIs<OpenResult.Opened>(w.rohanJoin.open(link))
        w.rohanJoin.request(w.group, "Rohan", "98765 43210")
        w.requests.approve(w.group, w.requests.waiting(w.group).single().member, "Rohan")
        w.rohanJoin.check(w.group)
        // Before any history: the phone named in the invitation is let in, a stranger is not.
        val session = w.rohanSessions.session(w.group)
        val store = owntally.data.session.RepositorySyncStore(w.group, w.rohanEnv.groups)
        fun admit(peer: owntally.model.PublicKey) = owntally.data.session.MembershipAccess(session, store, setOf(w.asha.key, stranger)).admit(peer)
        assertEquals(owntally.sync.Admission.Full, admit(w.asha.key))
        assertEquals(owntally.sync.Admission.Full, admit(stranger), "named as an introducer, so trusted while nothing is known")
        assertEquals(owntally.sync.Admission.Refuse, owntally.data.session.MembershipAccess(session, store, emptySet()).admit(w.asha.key), "not named")
        // After the history has arrived and this person is a member, a phone that is not a member is no longer trusted.
        w.rohanSync.startWith(w.group, w.asha.key)
        advanceTimeBy(10_000)
        session.refresh()
        assertTrue(session.view.value.me in session.view.value.state.currentMembers)
        assertEquals(owntally.sync.Admission.Refuse, admit(stranger), "a stranger who was once named is refused once this person is in")
        assertEquals(owntally.sync.Admission.Full, admit(w.asha.key), "and the host is a member now, so as before")
    }
}
