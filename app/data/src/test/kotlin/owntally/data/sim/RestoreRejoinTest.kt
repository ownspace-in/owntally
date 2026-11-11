package owntally.data.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import owntally.crypto.Bip39
import owntally.crypto.KeySlots
import owntally.crypto.Ticket
import owntally.data.repo.TestEnv
import owntally.data.secrets.SecretLoad
import owntally.data.session.AddResult
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
import owntally.model.GroupIds
import owntally.model.Money
import owntally.model.Split
import owntally.sync.GroupRoom
import owntally.sync.InMemoryNetwork
import owntally.sync.JoinHost
import owntally.sync.SyncEngine
import owntally.sync.SyncOutcome
import owntally.sync.SyncRole

/**
 * M11, T11.4 (docs/tasks/M11-restore.md): a phone restored from the recovery phrase is recognised by the host from its key alone and rejoins with
 * no approval; someone who left comes back under the next generation of keys; a stranger and another person's phrase join as usual.
 */
class RestoreRejoinTest {
    private val net = InMemoryNetwork()
    private val approved = mutableListOf<GroupId>()

    /** A phone that can host invitations for [group]: its own sessions, invitations and join requests, and a way to serve joins. */
    private inner class Host(val device: SimDevice) {
        val sessions = Sessions(device.env.groups, device.env.secrets, device.env.identity, device.env.clock, device.env.settings)
        val invites = Invites(owntally.crypto.InMemoryTicketRecords(), device.env.groups, device.env.secrets, device.env.identity, sessions, device.env.clock)
        val requests = JoinRequests(InMemoryJoinRequestStore(), sessions, device.env.clock)
        val backend = GroupJoinBackend(device.group, device.env.identity.groupIdentity(device.env.groups.group(device.group)!!.keySlot)!!, invites, requests, sessions)
        fun link() = invites.create(device.group)!!.link
        fun TestScope.serve(): kotlinx.coroutines.Job {
            val room = GroupRoom.fromGroupId(device.group.bytes())
            val incoming = net.transportFor(device.key).incoming(room)
            return backgroundScope.launch { incoming.collect { l -> launch { JoinHost(backend, room, stallTimeoutMs = 5_000).serve(l) } } }
        }
    }

    private val phrase = Bip39.fromEntropy(ByteArray(16) { 11 })
    private val asha = SimDevice.owner(31, "Asha")
    private val group: GroupId = asha.group
    private val ashaHost = Host(asha)

    private fun phone(p: owntally.crypto.RecoveryPhrase = phrase) = TestEnv().also { it.identity.completeFirstRun(p, "Rohan", "98765 43210") }
    private fun joinOf(env: TestEnv, store: owntally.data.store.PendingJoinStore = InMemoryPendingJoinStore()) =
        JoinGroup(env.groups, env.secrets, env.identity, store, Sessions(env.groups, env.secrets, env.identity, env.clock, env.settings), env.clock, { _, key -> net.transportFor(key) }, { g, _ -> approved += g }, connectTimeoutMs = 1_000)

    /** Rohan's old phone: admitted by Asha under generation [generation] of the member slots of this group. */
    private fun admitRohan(generation: Long = 0): owntally.crypto.GroupIdentity {
        val old = phone()
        val k = old.identity.groupIdentity(KeySlots.member(group, generation))!!
        assertTrue(asha.session.approveJoin(k.publicKey, k.encryptionKey, "Rohan", "98765 43210") is AddResult.Added)
        return k
    }

    @Test fun aRestoredMemberIsRecognisedAndRejoinsWithNoApprovalAndTheSameBalances() = runTest {
        val joinServer = with(ashaHost) { serve() }
        assertTrue(asha.session.addExpense("Dinner", Money(30_000), asha.key, 20_000, Split.Equal(setOf(asha.key))) is AddResult.Added)
        val rohanKey = admitRohan()
        val env = phone() // the new phone: the phrase and nothing else
        val result = joinOf(env).open(ashaHost.link())
        assertEquals(OpenResult.Restored(group), result)
        assertEquals(KeySlots.member(group, 0), env.groups.group(group)!!.keySlot)
        assertEquals(rohanKey.publicKey, env.identity.groupIdentity(env.groups.group(group)!!.keySlot)!!.publicKey, "the same member as before")
        assertEquals(listOf(group), approved, "the sync with the host is started")
        assertEquals(emptyList(), ashaHost.requests.waiting(group), "no request, so nobody has to approve")
        assertNotNull(env.secrets.loadKeyring(group).keyring[1])
        // The history arrives by sync (the join server stops listening first, as the coordinator tells joins from syncs on a link) and the balances are the same as Asha's.
        joinServer.cancel()
        val sessions = Sessions(env.groups, env.secrets, env.identity, env.clock, env.settings)
        syncWithAsha(env)
        sessions.session(group).refresh()
        val v = sessions.session(group).view.value
        assertEquals(rohanKey.publicKey, v.me)
        assertTrue(v.me in v.state.currentMembers)
        assertEquals(1, v.state.expenses.size)
        assertEquals(ashaHost.sessions.session(group).view.value.balances, v.balances)
    }

    private suspend fun syncWithAsha(env: TestEnv) {
        val room = GroupRoom.fromGroupId(group.bytes())
        val slot = env.groups.group(group)!!.keySlot
        val ours = net.transportFor(env.identity.groupIdentity(slot)!!.publicKey)
        val incoming = net.transportFor(asha.key).incoming(room)
        kotlinx.coroutines.coroutineScope {
            val server = launch { SyncEngine(room, RepositorySyncStore(group, asha.env.groups)).run(incoming.first(), SyncRole.RESPONDER) }
            SyncEngine(room, RepositorySyncStore(group, env.groups)).run(ours.connect(room, asha.key), SyncRole.INITIATOR)
            server.join()
        }
        ashaHost.sessions.session(group).refresh()
    }

    @Test fun aRestoredCreatorIsRecognisedByTheKeyTheGroupIdVouchesFor() = runTest {
        // Ben, an admin, hosts; Asha, who made the group, lost her phone.
        val ben = SimDevice.joined(31, 1, "Ben", asha)
        assertTrue(asha.session.makeAdmin(ben.key) is AddResult.Added)
        asha.env.groups.rawEnvelopes(group).forEach { ben.env.groups.appendEnvelope(group, owntally.model.Envelope.decode(it)) }
        ben.session.refresh()
        val benHost = Host(ben)
        with(benHost) { serve() }
        val ashasPhrase = Bip39.fromEntropy(kotlin.random.Random(31 * 1_000 + 0).nextBytes(16))
        val env = phone(ashasPhrase)
        val result = joinOf(env).open(benHost.link())
        assertEquals(OpenResult.Restored(group), result)
        val slot = env.groups.group(group)!!.keySlot
        assertEquals(KeySlots.creator(GroupIds.randomPart(group)), slot)
        assertEquals(asha.key, env.identity.groupIdentity(slot)!!.publicKey, "the key the group's creator had")
    }

    @Test fun someoneWhoLeftComesBackUnderTheNextGenerationOfKeys() = runTest {
        with(ashaHost) { serve() }
        val first = admitRohan(0)
        assertTrue(asha.session.removeMember(first.publicKey) is AddResult.Added)
        val env = phone()
        val store = InMemoryPendingJoinStore()
        val join = joinOf(env, store)
        val opened = assertIs<OpenResult.Opened>(join.open(ashaHost.link()))
        assertEquals(KeySlots.member(group, 1), opened.join.keySlot, "generation 0 is a removed key: it is skipped")
        assertEquals(RequestResult.Waiting, join.request(group, "Rohan", "98765 43210"))
        val member = ashaHost.requests.waiting(group).single().member
        assertTrue(ashaHost.requests.approve(group, member, "Rohan R") is owntally.data.session.ApproveResult.Approved)
        assertEquals(CheckResult.Approved, join.check(group))
        assertEquals(KeySlots.member(group, 1), env.groups.group(group)!!.keySlot)
        // A phone restored from the same phrase later finds generation 1, past the removed generation 0.
        val later = phone()
        assertEquals(OpenResult.Restored(group), joinOf(later).open(ashaHost.link()))
        assertEquals(KeySlots.member(group, 1), later.groups.group(group)!!.keySlot)
    }

    @Test fun aPhoneThatWasNeverInTheGroupJoinsAsNewUnderGenerationZero() = runTest {
        with(ashaHost) { serve() }
        val env = phone()
        val opened = assertIs<OpenResult.Opened>(joinOf(env).open(ashaHost.link()))
        assertEquals(KeySlots.member(group, 0), opened.join.keySlot)
        assertNull(env.groups.group(group))
        assertEquals(emptyList(), approved)
    }

    @Test fun anotherPersonsPhraseIsNotRecognisedEvenWhenRohanIsAMember() = runTest {
        with(ashaHost) { serve() }
        admitRohan()
        val eve = phone(Bip39.fromEntropy(ByteArray(16) { 12 }))
        val opened = assertIs<OpenResult.Opened>(joinOf(eve).open(ashaHost.link()))
        assertEquals(KeySlots.member(group, 0), opened.join.keySlot)
        assertNotEquals(KeyDerivationOf(phone()).publicKey, eve.identity.groupIdentity(opened.join.keySlot)!!.publicKey, "a different person has a different key for the same group")
        assertNull(eve.groups.group(group), "nothing was joined without a request")
    }

    private fun KeyDerivationOf(env: TestEnv) = env.identity.groupIdentity(KeySlots.member(group, 0))!!

    @Test fun whenTheHostIsAwayTheInviteOpensUnderGenerationZeroAndARestoredMemberStillGetsInWhenTheHostIsBack() = runTest {
        admitRohan()
        val env = phone()
        val join = joinOf(env)
        val link = ashaHost.link()
        val opened = assertIs<OpenResult.Opened>(join.open(link)) // no host is serving
        assertEquals(PreviewState.HOST_UNREACHABLE, opened.preview)
        with(ashaHost) { serve() }
        assertEquals(RequestResult.Restored(group), join.request(group, "Rohan", "98765 43210"))
        assertNotNull(env.groups.group(group))
        assertEquals(emptyList(), ashaHost.requests.waiting(group))
        assertEquals(listOf(group), approved)
    }

    /** A transport whose host never answers: every connect waits until the join gives up (the real timeout is 15 seconds). */
    private object HangingTransport : owntally.sync.Transport {
        override suspend fun connect(room: GroupRoom, peer: owntally.model.PublicKey): owntally.sync.Link = kotlinx.coroutines.awaitCancellation()
        override fun incoming(room: GroupRoom) = kotlinx.coroutines.flow.emptyFlow<owntally.sync.Link>()
    }

    @Test fun aHostThatNeverAnswersCostsOneWaitNotTwoWhenTheInviteIsOpened() = runTest {
        val env = phone()
        val join = JoinGroup(env.groups, env.secrets, env.identity, InMemoryPendingJoinStore(), Sessions(env.groups, env.secrets, env.identity, env.clock, env.settings), env.clock, { _, _ -> HangingTransport }, connectTimeoutMs = 1_000)
        val opened = assertIs<OpenResult.Opened>(join.open(ashaHost.link()))
        assertEquals(PreviewState.HOST_UNREACHABLE, opened.preview)
        assertTrue(testScheduler.currentTime <= 1_200, "the person waited ${testScheduler.currentTime} ms: the host is asked about the key and then again for the description, but a host that did not answer the first is not asked the second")
        // The description can still be asked for later, and the request asks again which key to use.
        assertEquals(KeySlots.member(group, 0), opened.join.keySlot)
    }
}
