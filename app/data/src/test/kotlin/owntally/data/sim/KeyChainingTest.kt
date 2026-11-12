package owntally.data.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import owntally.crypto.EventSealing
import owntally.crypto.GroupKey
import owntally.data.repo.TestEnv
import owntally.data.session.AddResult
import owntally.data.session.CheckResult
import owntally.data.session.GroupJoinBackend
import owntally.data.session.Invites
import owntally.data.session.JoinGroup
import owntally.data.session.JoinRequests
import owntally.data.session.RepositorySyncStore
import owntally.data.session.Sessions
import owntally.data.store.InMemoryJoinRequestStore
import owntally.data.store.InMemoryPendingJoinStore
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.GroupId
import owntally.model.Money
import owntally.model.PublicKey
import owntally.model.Split
import owntally.sync.GroupRoom
import owntally.sync.InMemoryNetwork
import owntally.sync.JoinHost
import owntally.sync.SyncEngine
import owntally.sync.SyncRole
import owntally.sync.SyncStore

/**
 * M11 (owner decision D1), redesigned 12 Nov 2026 after finding R7: a person admitted after a key change reads the whole group because the host hands
 * them every epoch key, sealed to them, with the answer that they are approved (docs/CRYPTO.md section 6). Keys never come from an event body, so no
 * member can plant one. Before this a late member saw an empty group (finding P1), and in between a dishonest member could blind them for ever (R7).
 */
class KeyChainingTest {
    private val asha = SimDevice.owner(21, "Asha")
    private val group: GroupId = asha.group
    private val room = GroupRoom.fromGroupId(group.bytes())
    private val net = InMemoryNetwork()
    private val hostSessions = Sessions(asha.env.groups, asha.env.secrets, asha.env.identity, asha.env.clock, asha.env.settings)
    private val invites = Invites(owntally.crypto.InMemoryTicketRecords(), asha.env.groups, asha.env.secrets, asha.env.identity, hostSessions, asha.env.clock)
    private val requests = JoinRequests(InMemoryJoinRequestStore(), hostSessions, asha.env.clock)
    private val rohanEnv = TestEnv().also { it.firstRun("Rohan", "98765 43210") }
    private val pendingStore = InMemoryPendingJoinStore()
    private val rohanSessions = Sessions(rohanEnv.groups, rohanEnv.secrets, rohanEnv.identity, rohanEnv.clock, rohanEnv.settings)
    private fun rohan() = JoinGroup(rohanEnv.groups, rohanEnv.secrets, rohanEnv.identity, pendingStore, rohanSessions, rohanEnv.clock, { _, key -> net.transportFor(key) }, { _, _ -> }, connectTimeoutMs = 1_000)
    private val ashaSession get() = hostSessions.session(group)
    private fun rohanKeyring() = rohanEnv.secrets.loadKeyring(group).keyring
    private fun rohanKey(): PublicKey = rohanEnv.identity.groupIdentity(pendingStore.get(group)?.keySlot ?: rohanEnv.groups.group(group)!!.keySlot)!!.publicKey

    private fun TestScope.hostServes(): kotlinx.coroutines.Job {
        val backend = GroupJoinBackend(group, asha.env.identity.groupIdentity(asha.slot)!!, invites, requests, hostSessions)
        val incoming = net.transportFor(asha.key).incoming(room)
        return backgroundScope.launch { incoming.collect { link -> launch { JoinHost(backend, room, stallTimeoutMs = 5_000).serve(link) } } }
    }

    /** Rohan opens an invitation and asks to join; he is now waiting, not yet approved. */
    private suspend fun rohanAsks() {
        val link = invites.create(group)!!.link
        rohan().open(link); rohan().request(group, "Rohan", "98765 43210")
    }

    private suspend fun TestScope.rohanJoinsAndSyncs() {
        val server = hostServes()
        rohanAsks()
        val member = requests.waiting(group).single().member
        requests.approve(group, member, "Rohan V")
        assertEquals(CheckResult.Approved, rohan().check(group))
        server.cancel()
        rohanSyncsWith(asha.key, RepositorySyncStore(group, asha.env.groups))
        hostSessions.session(group).refresh()
    }

    private suspend fun rohanSyncsWith(peerKey: PublicKey, peerStore: SyncStore) {
        val rohanStore = RepositorySyncStore(group, rohanEnv.groups)
        val ours = net.transportFor(rohanKey())
        val inc = net.transportFor(peerKey).incoming(room)
        coroutineScope {
            val srv = launch { val l = inc.first(); SyncEngine(room, peerStore).run(l, SyncRole.RESPONDER) }
            SyncEngine(room, rohanStore).run(ours.connect(room, peerKey), SyncRole.INITIATOR)
            srv.join()
        }
        rohanSessions.session(group).refresh()
    }

    private fun rohanView() = rohanSessions.session(group).view.value

    @Test fun aMemberAdmittedAfterAKeyChangeReadsTheWholeHistoryAndGetsTheSameBalances() = runTest {
        assertTrue(ashaSession.addExpense("Dinner", Money(30_000), asha.key, 20_000, Split.Equal(setOf(asha.key))) is AddResult.Added)
        assertTrue(ashaSession.rotateKey() is AddResult.Added)
        rohanJoinsAndSyncs()
        val v = rohanView()
        assertEquals(2L, v.state.epoch)
        assertEquals("Simulated", v.state.name)
        assertEquals(1, v.state.expenses.size, "the expense written before the key change")
        assertTrue(v.me in v.state.currentMembers)
        assertEquals(hostSessions.session(group).view.value.balances, v.balances)
        assertEquals(0, v.counts.waitingForKey.size)
    }

    @Test fun theKeysReachBackThroughSeveralKeyChanges() = runTest {
        assertTrue(ashaSession.addExpense("Lunch", Money(10_000), asha.key, 20_000, Split.Equal(setOf(asha.key))) is AddResult.Added)
        assertTrue(ashaSession.rotateKey() is AddResult.Added)
        assertTrue(ashaSession.addExpense("Taxi", Money(5_000), asha.key, 20_001, Split.Equal(setOf(asha.key))) is AddResult.Added)
        assertTrue(ashaSession.rotateKey() is AddResult.Added)
        assertTrue(ashaSession.addExpense("Tea", Money(2_000), asha.key, 20_002, Split.Equal(setOf(asha.key))) is AddResult.Added)
        assertTrue(ashaSession.rotateKey() is AddResult.Added)
        rohanJoinsAndSyncs()
        val v = rohanView()
        assertEquals(4L, v.state.epoch)
        assertEquals(3, v.state.expenses.size, "one written in each of the first three epochs")
        assertEquals(hostSessions.session(group).view.value.balances, v.balances)
        assertEquals(setOf(1L, 2L, 3L, 4L), rohanKeyring().epochs())
    }

    @Test fun theKeysArriveOnlyWithTheApprovalNeverWithTheInvitation() = runTest {
        assertTrue(ashaSession.rotateKey() is AddResult.Added)
        assertTrue(ashaSession.rotateKey() is AddResult.Added)
        val server = hostServes()
        rohanAsks()
        assertEquals(setOf(3L), rohanKeyring().epochs(), "an invitation holder who is not approved has the ticket's key and nothing older")
        assertEquals(CheckResult.StillWaiting, rohan().check(group))
        assertEquals(setOf(3L), rohanKeyring().epochs(), "asking while still waiting hands over nothing")
        val member = requests.waiting(group).single().member
        requests.approve(group, member, "Rohan V")
        assertEquals(CheckResult.Approved, rohan().check(group))
        assertEquals(setOf(1L, 2L, 3L), rohanKeyring().epochs(), "approved: every epoch's key, from the host, before any history has arrived")
        server.cancel()
    }

    /**
     * Finding R7 (12 Nov 2026), as the regression test: Mallory, a plain member, crafts an "epoch 1" entry sealed under a key of her choosing. With the
     * earlier design (keys taken from an event body when they opened any entry) a late joiner who synced with her first adopted WRONG as epoch 1's key
     * for ever. Now keys come only from the host's sealed answer, so the crafted entry is merely unreadable and changes no key.
     */
    @Test fun aCraftedEntryUnderAWrongKeyNeverChangesWhichKeyAJoinerHoldsForAnEpoch() = runTest {
        val mallory = SimDevice.joined(21, 1, "Mallory", asha)
        assertTrue(ashaSession.addExpense("Dinner", Money(30_000), asha.key, 20_000, Split.Equal(setOf(asha.key))) is AddResult.Added)
        assertTrue(ashaSession.rotateKey() is AddResult.Added)
        // Mallory syncs with Asha first: she holds k1 and k2. (Before the join host listens, so the two do not compete for Asha's connections.)
        syncPair(mallory, asha)
        val server = hostServes()
        rohanAsks()
        val member = requests.waiting(group).single().member
        requests.approve(group, member, "Rohan V")
        val malloryId = mallory.env.identity.groupIdentity(mallory.slot)!!
        val wrong = GroupKey.generate()
        val c = (mallory.env.groups.envelopes(group).envelopes.filter { it.author == mallory.key }.maxOfOrNull { it.counter } ?: 0L) + 1
        val bait = Event(1, mallory.key, c, if (c > 1) mapOf(mallory.key to c - 1) else emptyMap(), 0, EventBody.ProfileUpdated("Bait", "98765 99999"))
        mallory.env.groups.appendEnvelope(group, EventSealing.seal(malloryId, group, 1, wrong, bait)) // "epoch 1", but under WRONG

        assertEquals(CheckResult.Approved, rohan().check(group)) // the host's answer carries the real k1 and k2
        server.cancel()
        val realK1 = asha.env.secrets.loadKeyring(group).keyring[1]!!
        rohanSyncsWith(mallory.key, RepositorySyncStore(group, mallory.env.groups)) // Mallory first, bait included
        assertTrue(rohanKeyring()[1]!!.bytes().contentEquals(realK1.bytes()), "epoch 1's key is the host's, not Mallory's")
        assertEquals(1, rohanView().state.expenses.size, "Dinner is read")
        assertEquals(1, rohanView().counts.unreadable, "the bait is the one thing that cannot be read")
        assertEquals("Mallory", rohanView().state.members[mallory.key]?.name, "the bait's profile never took effect")
    }

    private suspend fun syncPair(a: SimDevice, b: SimDevice) {
        val sa = RepositorySyncStore(group, a.env.groups); val sb = RepositorySyncStore(group, b.env.groups)
        val inc = net.transportFor(b.key).incoming(room)
        coroutineScope {
            val srv = launch { val l = inc.first(); SyncEngine(room, sb).run(l, SyncRole.RESPONDER) }
            SyncEngine(room, sa).run(net.transportFor(a.key).connect(room, b.key), SyncRole.INITIATOR)
            srv.join()
        }
        a.session.refresh(); b.session.refresh()
    }
}
