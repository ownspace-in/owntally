package owntally.data.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import owntally.crypto.Ticket
import owntally.data.repo.TestEnv
import owntally.data.secrets.SecretLoad
import owntally.data.session.CheckResult
import owntally.data.session.GroupJoinBackend
import owntally.data.session.Invites
import owntally.data.session.JoinGroup
import owntally.data.session.JoinRequests
import owntally.data.session.OpenResult
import owntally.data.session.PreviewState
import owntally.data.session.RepositorySyncStore
import owntally.data.session.RequestResult
import owntally.data.session.Sessions
import owntally.data.store.InMemoryJoinRequestStore
import owntally.data.store.InMemoryPendingJoinStore
import owntally.data.store.PendingState
import owntally.model.GroupId
import owntally.sync.GroupRoom
import owntally.sync.InMemoryNetwork
import owntally.sync.JoinHost
import owntally.sync.SyncEngine
import owntally.sync.SyncOutcome
import owntally.sync.SyncRole

/** T6.5: Rohan joins Asha's group, each on a phone with its own database, over the fake network. */
class JoinGroupTest {
    private val asha = SimDevice.owner(21, "Asha")
    private val group: GroupId = asha.group
    private val room = GroupRoom.fromGroupId(group.bytes())
    private val net = InMemoryNetwork()

    private val hostSessions = Sessions(asha.env.groups, asha.env.secrets, asha.env.identity, asha.env.clock, asha.env.settings)
    private val invites = Invites(owntally.crypto.InMemoryTicketRecords(), asha.env.groups, asha.env.secrets, asha.env.identity, hostSessions, asha.env.clock)
    private val requests = JoinRequests(InMemoryJoinRequestStore(), hostSessions, asha.env.clock)
    private fun backend(host: owntally.crypto.GroupIdentity = asha.env.identity.groupIdentity(asha.slot)!!) = GroupJoinBackend(group, host, invites, requests, hostSessions)

    private val rohanEnv = TestEnv().also { it.firstRun("Rohan", "98765 43210") }
    private val pendingStore = InMemoryPendingJoinStore()
    private var approvedCalls = mutableListOf<GroupId>()
    private val rohanSessions = Sessions(rohanEnv.groups, rohanEnv.secrets, rohanEnv.identity, rohanEnv.clock, rohanEnv.settings)
    private fun rohan(store: owntally.data.store.PendingJoinStore = pendingStore, sessions: Sessions = rohanSessions) =
        JoinGroup(rohanEnv.groups, rohanEnv.secrets, rohanEnv.identity, store, sessions, rohanEnv.clock, { _, key -> net.transportFor(key) }, { g, _ -> approvedCalls += g }, connectTimeoutMs = 1_000)

    private fun TestScope.hostServes(backend: GroupJoinBackend = backend()): kotlinx.coroutines.Job {
        val incoming = net.transportFor(asha.key).incoming(room) // listening starts now, not when the background job first runs
        return backgroundScope.launch { incoming.collect { link -> launch { JoinHost(backend, room, stallTimeoutMs = 5_000).serve(link) } } }
    }

    private fun link() = invites.create(group)!!.link
    private fun advance(seconds: Long) { asha.env.nowSeconds += seconds; rohanEnv.nowSeconds += seconds }

    // ---- opening ------------------------------------------------------------------------------------------------------------------

    @Test fun openingAnInviteAsksTheHostWhatTheGroupIsAndKeepsTheKeyWithoutUsingTheInviteUp() = runTest {
        hostServes()
        val text = link()
        val opened = assertIs<OpenResult.Opened>(rohan().open(text))
        assertEquals(PreviewState.FOUND, opened.preview)
        assertEquals("Simulated", opened.join.groupName); assertEquals("Asha", opened.join.ownerName); assertEquals(1, opened.join.memberCount)
        assertEquals(PendingState.OPENED, opened.join.state)
        assertNotNull(rohanEnv.secrets.loadKeyring(group).keyring[1], "the key is kept from now on")
        assertNotNull(invites.current(group), "the invite is not used up")
        assertEquals(opened.join, pendingStore.get(group))
    }

    @Test fun somethingThatIsNotAnInviteAndAnExpiredInviteAreToldApart() = runTest {
        hostServes()
        assertEquals(OpenResult.NotAnInvite, rohan().open("hello"))
        assertEquals(OpenResult.NotAnInvite, rohan().open("https://joinowntally.ownspace.in/j#abc"))
        val text = link()
        advance(Ticket.LIFETIME_SECONDS + 61)
        assertEquals(OpenResult.Expired, rohan().open(text))
        assertNull(pendingStore.get(group), "nothing was kept")
    }

    @Test fun anInviteOpenedWhileTheHostIsOutOfReachCanStillBeUsedLater() = runTest {
        val text = link()
        val opened = assertIs<OpenResult.Opened>(rohan().open(text))
        assertEquals(PreviewState.HOST_UNREACHABLE, opened.preview)
        assertEquals("", opened.join.groupName)
        assertEquals(RequestResult.HostUnreachable, rohan().request(group, "Rohan", "98765 43210"))
        hostServes()
        assertEquals(RequestResult.Waiting, rohan().request(group, "Rohan", "98765 43210"))
        assertEquals(1, requests.waiting(group).size)
    }

    @Test fun openingTheSameInviteAgainKeepsTheSameKeySlot() = runTest {
        hostServes()
        val text = link()
        val a = assertIs<OpenResult.Opened>(rohan().open(text)).join
        val b = assertIs<OpenResult.Opened>(rohan().open(text)).join
        assertEquals(a.keySlot, b.keySlot)
        assertEquals(owntally.crypto.KeySlots.member(group, 0), a.keySlot, "a first join uses generation 0 of the member slots of this group")
    }

    @Test fun anInviteForAGroupAlreadyOnThisPhoneIsRecognised() = runTest {
        hostServes()
        val mine = rohanEnv.groups.createGroup("Mine")
        val other = Invites(owntally.crypto.InMemoryTicketRecords(), rohanEnv.groups, rohanEnv.secrets, rohanEnv.identity, rohanSessions, rohanEnv.clock).create(mine)!!.link
        assertEquals(OpenResult.AlreadyInGroup(mine), rohan().open(other))
    }

    @Test fun aPeerThatIsNotTheHostGetsNothingSentAndIsNotBelieved() = runTest {
        val intruder = SimDevice.owner(22, "Mallory").env.identity.let { it.groupIdentity(0)!! }
        // The invite names Asha's key, but Mallory's phone is the one that answers at Asha's address.
        hostServes(backend(host = intruder))
        val text = link()
        val opened = assertIs<OpenResult.Opened>(rohan().open(text))
        assertEquals(PreviewState.NOT_VERIFIED, opened.preview)
        assertEquals(RequestResult.NotVerified, rohan().request(group, "Rohan", "98765 43210"))
        assertEquals(0, requests.waiting(group).size)
    }

    // ---- asking to join ----------------------------------------------------------------------------------------------------------------

    @Test fun theRequestReachesTheHostsListWithTheNameAndNumberAndTheInviteIsUsedUp() = runTest {
        hostServes()
        val text = link()
        rohan().open(text)
        assertEquals(RequestResult.Waiting, rohan().request(group, "  Rohan V ", " 98765 43210 "))
        val waiting = requests.waiting(group).single()
        assertEquals("Rohan V", waiting.name); assertEquals("98765 43210", waiting.phone)
        assertEquals(rohanEnv.identity.groupIdentity(pendingStore.get(group)!!.keySlot)!!.publicKey, waiting.member)
        assertEquals(PendingState.WAITING, pendingStore.get(group)!!.state)
        assertNull(invites.current(group), "single use")
    }

    @Test fun aNameOrNumberTheGroupWouldNotAcceptIsStoppedBeforeAnythingIsSent() = runTest {
        hostServes()
        rohan().open(link())
        assertEquals(RequestResult.InvalidDetails, rohan().request(group, "   ", "98765 43210"))
        assertEquals(RequestResult.InvalidDetails, rohan().request(group, "x".repeat(41), "98765 43210"))
        assertEquals(RequestResult.InvalidDetails, rohan().request(group, "Rohan", "12"))
        assertEquals(0, requests.waiting(group).size)
        assertNotNull(invites.current(group), "and the invite is still good")
    }

    @Test fun anInviteThatWasUsedBySomeoneElseIsNoLongerValid() = runTest {
        hostServes()
        val text = link()
        rohan().open(text)
        assertTrue(invites.registry(group)!!.accept(Ticket.fromShared(text)!!)) // someone else got there first
        assertEquals(RequestResult.NoLongerValid, rohan().request(group, "Rohan", "98765 43210"))
    }

    @Test fun anInviteRevokedAfterOpeningIsNoLongerValid() = runTest {
        hostServes()
        val invite = invites.create(group)!!
        rohan().open(invite.link)
        invites.revoke(group, invite)
        assertEquals(RequestResult.NoLongerValid, rohan().request(group, "Rohan", "98765 43210"))
    }

    @Test fun ifTheHostKeptTheRequestButTheAnswerWasLostTheNextTryFindsItAndWaits() = runTest {
        hostServes()
        val text = link()
        rohan().open(text)
        // The host took the request (and used the ticket up), but Rohan's phone never heard.
        val slot = pendingStore.get(group)!!.keySlot
        val me = rohanEnv.identity.groupIdentity(slot)!!
        val ticket = Ticket.fromShared(text)!!
        assertTrue(backend().let { it.consume(it.issued(ticket.ticketId())!!) && it.submit(owntally.sync.JoinRequestBody(me.publicKey, me.encryptionKey, "Rohan", "98765 43210")) })
        assertEquals(RequestResult.Waiting, rohan().request(group, "Rohan", "98765 43210"))
        assertEquals(PendingState.WAITING, pendingStore.get(group)!!.state)
    }

    @Test fun asksAgainOnlyOnceTheHostHasTheRequest() = runTest {
        hostServes()
        rohan().open(link())
        assertEquals(RequestResult.Waiting, rohan().request(group, "Rohan", "98765 43210"))
        assertEquals(RequestResult.Waiting, rohan().request(group, "Rohan", "98765 43210"), "a second tap does not send a second request")
        assertEquals(1, requests.waiting(group).size)
    }

    // ---- the answer ----------------------------------------------------------------------------------------------------------------

    @Test fun whileTheHostsPersonHasNotAnsweredTheJoinerKeepsWaitingAndAnUnreachableHostIsToldApart() = runTest {
        hostServes()
        rohan().open(link()); rohan().request(group, "Rohan", "98765 43210")
        assertEquals(CheckResult.StillWaiting, rohan().check(group))
        assertEquals(0, approvedCalls.size)
        assertNull(rohanEnv.groups.group(group), "no group yet")
    }

    @Test fun approvalMakesTheGroupOnThePhoneStartsTheSyncAndTheHistoryArrives() = runTest {
        val joinServer = hostServes()
        rohan().open(link()); rohan().request(group, "Rohan", "98765 43210")
        val member = requests.waiting(group).single().member
        assertTrue(requests.approve(group, member, "Rohan V") is owntally.data.session.ApproveResult.Approved)
        val joiner = rohan()
        assertEquals(CheckResult.Approved, joiner.check(group))
        assertEquals(listOf(group), approvedCalls)
        assertNotNull(rohanEnv.groups.group(group))
        assertFalse(joiner.completeIfMember(group), "not a member until the history arrives")

        // The history: Rohan's phone and Asha's sync (what the coordinator does after approval). Telling joins from syncs on one incoming link is T6.6.
        joinServer.cancel()
        val rohanStore = RepositorySyncStore(group, rohanEnv.groups)
        val ashaStore = RepositorySyncStore(group, asha.env.groups)
        val ours = net.transportFor(rohanEnv.identity.groupIdentity(pendingStore.get(group)!!.keySlot)!!.publicKey)
        val incoming = net.transportFor(asha.key).incoming(room)
        coroutineScope {
            val server = launch { val l = incoming.first(); SyncEngine(room, ashaStore).run(l, SyncRole.RESPONDER) }
            val result = SyncEngine(room, rohanStore).run(ours.connect(room, asha.key), SyncRole.INITIATOR)
            assertEquals(SyncOutcome.COMPLETED, result.outcome)
            server.join()
        }
        rohanSessions.session(group).refresh() // what the coordinator does after every sync
        val view = rohanSessions.session(group).view.value
        assertEquals("Simulated", view.state.name)
        assertEquals("Rohan V", view.state.members[view.me]!!.name)
        assertTrue(joiner.completeIfMember(group))
        assertNull(pendingStore.get(group), "the invitation's record is gone")
    }

    @Test fun aDeclinedRequestIsToldAndAnUnknownOneToo() = runTest {
        hostServes()
        rohan().open(link()); rohan().request(group, "Rohan", "98765 43210")
        requests.decline(group, requests.waiting(group).single().member)
        assertEquals(CheckResult.Declined, rohan().check(group))
        assertEquals(PendingState.DECLINED, pendingStore.get(group)!!.state)
        assertNull(rohanEnv.groups.group(group), "no group")
    }

    @Test fun aHostThatHasNoRecordOfTheRequestSaysUnknown() = runTest {
        hostServes()
        rohan().open(link()); rohan().request(group, "Rohan", "98765 43210")
        val fresh = backend()
        // A host phone that lost its list: ask a backend with no requests.
        net.transportFor(asha.key)
        val lost = JoinRequests(InMemoryJoinRequestStore(), hostSessions, asha.env.clock)
        assertEquals(owntally.sync.JoinStatus.UNKNOWN, GroupJoinBackend(group, asha.env.identity.groupIdentity(asha.slot)!!, invites, lost, hostSessions).statusOf(rohanEnv.identity.groupIdentity(pendingStore.get(group)!!.keySlot)!!.publicKey).status)
        assertNotNull(fresh)
    }

    @Test fun whenTheHostIsAwayTheCheckSaysSoAndNothingChanges() = runTest {
        rohan().open(link())
        // pretend the request was sent earlier, with the host gone now
        pendingStore.put(pendingStore.get(group)!!.copy(state = PendingState.WAITING))
        assertEquals(CheckResult.HostUnreachable, rohan().check(group))
        assertEquals(PendingState.WAITING, pendingStore.get(group)!!.state)
    }

    @Test fun closingTheAppInTheMiddleLosesNothing() = runTest {
        hostServes()
        rohan().open(link()); rohan().request(group, "Rohan", "98765 43210")
        // "Restart": a new JoinGroup and new Sessions over the same stored data.
        val restarted = rohan(pendingStore, Sessions(rohanEnv.groups, rohanEnv.secrets, rohanEnv.identity, rohanEnv.clock, rohanEnv.settings))
        assertEquals(1, restarted.pending().size)
        assertEquals(CheckResult.StillWaiting, restarted.check(group))
        requests.approve(group, requests.waiting(group).single().member, "Rohan")
        assertEquals(CheckResult.Approved, restarted.check(group))
    }

    @Test fun discardingDeletesTheRecordAndTheKeyKeptForIt() = runTest {
        hostServes()
        rohan().open(link())
        assertNotNull(rohanEnv.secrets.loadKeyring(group).keyring[1])
        rohan().discard(group)
        assertNull(pendingStore.get(group))
        assertNull(rohanEnv.secrets.loadKeyring(group).keyring[1], "no key left behind")
    }

    // ---- a restored phone -----------------------------------------------------------------------------------------------------------

    // The M6 test of findMySlot (a search over numbered slots) is gone: finding a restored phone's key is RestoreRejoinTest (M11).

    @Test fun aPhoneWhoseClockIsALittleAheadStillOpensAnInviteThatJustRanOutButNotOneLongGone() = runTest {
        hostServes()
        val text = link()
        rohanEnv.nowSeconds += Ticket.LIFETIME_SECONDS + 30 // the host still counts it as valid by a hair: only this phone's clock is ahead
        assertIs<OpenResult.Opened>(rohan().open(text), "within the 60 seconds the joiner forgives")
        rohanEnv.nowSeconds += 40
        assertEquals(OpenResult.Expired, rohan().open(text), "past it")
    }

    @Test fun theHostAnswersForAMemberARemovedMemberARequestAndAStrangerEachDifferently() = runTest {
        val b = backend()
        val ben = SimDevice.joined(21, 1, "Ben", asha)
        val kate = SimDevice.joined(21, 2, "Kate", asha)
        val stranger = rohanEnv.identity.groupIdentity(40)!!.publicKey
        assertEquals(owntally.sync.JoinStatus.APPROVED, b.statusOf(ben.key).status, "a member")
        assertTrue(asha.session.removeMember(kate.key) is owntally.data.session.AddResult.Added)
        hostSessions.session(group).refresh() // the host phone's own session reads the log again after the change
        assertEquals(owntally.sync.JoinStatus.DECLINED, b.statusOf(kate.key).status, "someone who was removed")
        assertEquals(owntally.sync.JoinStatus.UNKNOWN, b.statusOf(stranger).status, "someone never heard of")
    }

    @Test fun theHostRefusesARequestWithDetailsTheGroupWouldNotAcceptOrFromSomeoneAlreadyInside() = runTest {
        val b = backend()
        val ben = SimDevice.joined(21, 1, "Ben", asha)
        val me = rohanEnv.identity.groupIdentity(41)!!
        fun body(name: String, phone: String, who: owntally.crypto.GroupIdentity = me) = owntally.sync.JoinRequestBody(who.publicKey, who.encryptionKey, name, phone)
        assertFalse(b.submit(body("   ", "98765 43210")), "a blank name, from a client that did not check")
        assertFalse(b.submit(body("x".repeat(41), "98765 43210")))
        assertFalse(b.submit(body("Rohan", "12")))
        assertEquals(0, requests.waiting(group).size)
        assertTrue(b.submit(body(" Rohan ", " 98765 43210 ")))
        assertEquals("Rohan", requests.waiting(group).single().name, "trimmed")
        val benId = ben.env.identity.groupIdentity(ben.slot)!!
        assertTrue(asha.session.view.value.state.currentMembers.containsKey(benId.publicKey))
        assertFalse(b.submit(body("Ben", "98765 00001", benId)), "already a member")
    }

    @Test fun anInviteOpenedWhileTheHostWasAwayGetsItsDescriptionWhenTheHostIsBack() = runTest {
        val text = link()
        assertEquals(PreviewState.HOST_UNREACHABLE, assertIs<OpenResult.Opened>(rohan().open(text)).preview)
        hostServes()
        assertEquals(PreviewState.FOUND, rohan().refreshPreview(group))
        assertEquals("Simulated", pendingStore.get(group)!!.groupName)
        assertNull(rohan().refreshPreview(GroupId(ByteArray(16) { 5 })), "an invitation that was never opened")
    }
}
