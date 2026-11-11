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
import owntally.crypto.EventSealing
import owntally.crypto.GroupKey
import owntally.crypto.KeyRotation
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
import owntally.model.EventBody
import owntally.model.GroupId
import owntally.model.Money
import owntally.model.PreviousKey
import owntally.model.Split
import owntally.sync.GroupRoom
import owntally.sync.InMemoryNetwork
import owntally.sync.JoinHost
import owntally.sync.SyncEngine
import owntally.sync.SyncOutcome
import owntally.sync.SyncRole

/**
 * M11, T11.1 (owner decision D1, key chaining): a person admitted after a key change reads the whole group, because each key change is followed
 * by a `KeyLinked` event that hands the previous key to whoever holds the new one. Before this a late member saw an empty group (finding P1).
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

    private suspend fun TestScope.rohanJoinsAndSyncs() {
        val backend = GroupJoinBackend(group, asha.env.identity.groupIdentity(asha.slot)!!, invites, requests, hostSessions)
        val incoming = net.transportFor(asha.key).incoming(room)
        val server = backgroundScope.launch { incoming.collect { link -> launch { JoinHost(backend, room, stallTimeoutMs = 5_000).serve(link) } } }
        val link = invites.create(group)!!.link
        rohan().open(link); rohan().request(group, "Rohan", "98765 43210")
        val member = requests.waiting(group).single().member
        requests.approve(group, member, "Rohan V")
        rohan().check(group)
        server.cancel()
        syncRohan()
    }

    private suspend fun syncRohan() {
        val rohanStore = RepositorySyncStore(group, rohanEnv.groups)
        val ashaStore = RepositorySyncStore(group, asha.env.groups)
        val ours = net.transportFor(rohanEnv.identity.groupIdentity(pendingStore.get(group)?.keySlot ?: rohanEnv.groups.group(group)!!.keySlot)!!.publicKey)
        val inc = net.transportFor(asha.key).incoming(room)
        coroutineScope {
            val srv = launch { val l = inc.first(); SyncEngine(room, ashaStore).run(l, SyncRole.RESPONDER) }
            SyncEngine(room, rohanStore).run(ours.connect(room, asha.key), SyncRole.INITIATOR)
            srv.join()
        }
        rohanSessions.session(group).refresh()
        hostSessions.session(group).refresh()
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

    @Test fun theChainReachesBackThroughSeveralKeyChangesAndARemoval() = runTest {
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
        assertEquals(setOf(1L, 2L, 3L, 4L), rohanEnv.secrets.loadKeyring(group).keyring.epochs())
    }

    @Test fun everyKeyChangeIsFollowedByALinkInTheNewEpoch() = runTest {
        assertTrue(ashaSession.rotateKey() is AddResult.Added)
        val st = hostSessions.session(group).view.value.state
        val link = st.keyLinks.getValue(2L)
        val event = ashaSession.eventOf(link)!!
        assertEquals(EventBody.KeyLinked::class, event.body::class)
        val envelope = asha.env.groups.envelopes(group).envelopes.first { it.author == event.author && it.counter == event.counter }
        assertEquals(2L, envelope.epoch, "written in the new epoch, so encrypted under the new key")
    }

    @Test fun theOwnersPhoneWritesAMissingLinkByItself() = runTest {
        // A key change whose link was never written (the phone stopped in between): made by hand, the way a key change is made.
        val view = ashaSession.view.value
        val fresh = GroupKey.generate()
        val sealed = KeyRotation.seal(view.me, group, 2, fresh, KeyRotation.recipients(view.state, emptySet()))
        asha.env.secrets.saveGroupKey(group, 2, fresh)
        assertTrue(ashaSession.append(EventBody.KeyRotated(2, sealed)) is AddResult.Added)
        assertEquals(emptyMap(), ashaSession.view.value.state.keyLinks, "no link yet")
        ashaSession.refresh()
        assertTrue(2L in ashaSession.view.value.state.keyLinks, "the next time the owner's phone reads the group it writes it")
        val keysBefore = ashaSession.view.value.state.keyLinks.size
        ashaSession.refresh(); ashaSession.refresh()
        assertEquals(keysBefore, ashaSession.view.value.state.keyLinks.size, "and only once")
    }

    @Test fun aLinkWithTheWrongKeyIsNotTakenAndNothingBreaks() = runTest {
        assertTrue(ashaSession.addExpense("Dinner", Money(30_000), asha.key, 20_000, Split.Equal(setOf(asha.key))) is AddResult.Added)
        // A key change with a dishonest link: the key in it opens nothing.
        val view = ashaSession.view.value
        val fresh = GroupKey.generate()
        val sealed = KeyRotation.seal(view.me, group, 2, fresh, KeyRotation.recipients(view.state, emptySet()))
        asha.env.secrets.saveGroupKey(group, 2, fresh)
        assertTrue(ashaSession.append(EventBody.KeyRotated(2, sealed)) is AddResult.Added)
        assertTrue(ashaSession.append(EventBody.KeyLinked(2, PreviousKey(ByteArray(32) { 9 }))) is AddResult.Added)
        rohanJoinsAndSyncs()
        val v = rohanView()
        assertEquals(0, v.state.expenses.size, "the history stays closed: no key was invented")
        assertEquals(setOf(2L), rohanEnv.secrets.loadKeyring(group).keyring.epochs(), "and no wrong key was stored")
    }
}
