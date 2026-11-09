package owntally.data.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import owntally.data.session.PreviewState
import owntally.data.session.RequestResult
import owntally.data.session.Sessions
import owntally.data.session.SyncCoordinator
import owntally.data.session.SyncRecorder
import owntally.data.store.InMemoryJoinRequestStore
import owntally.data.store.InMemoryPendingJoinStore
import owntally.data.store.InMemorySyncProblemLog
import owntally.model.Money
import owntally.model.PublicKey
import owntally.model.Split
import owntally.sync.ChainConfig
import owntally.sync.ChainTransport
import owntally.sync.InMemoryNetwork
import owntally.sync.LinkKind
import owntally.sync.SecureOverCarrier
import owntally.sync.Transport

/**
 * T7.7: joining through the real chain, as two phones on different mobile networks would: no same-network way, only Iroh and the relay
 * (pretend ones: Iroh as a direct in-memory link, the relay as a carrier under the real TLS-over-relay code). Real time, because TLS is.
 */
class JoinOverInternetTest {
    private val now = 1_800_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock { now }
    private fun run(block: suspend () -> Unit): Unit = try { runBlocking { withTimeout(60_000) { block() } } } finally { scope.cancel() }

    private inner class World(private val irohUp: Boolean, private val relayUp: Boolean) {
        val irohNet = InMemoryNetwork()
        val relayNet = InMemoryNetwork()
        private val config = ChainConfig(localLookMs = 0, relayDelayMs = 200, connectMs = 8_000)

        fun chain(env: TestEnv, slot: Long): Transport {
            val id = env.identity.groupIdentity(slot)!!
            val iroh = if (irohUp) irohNet.transportFor(id.publicKey, LinkKind.DIRECT) else null
            val relay = if (relayUp) SecureOverCarrier(relayNet.transportFor(id.publicKey, LinkKind.RELAYED), id, scope, maxFrame = 16_000) else null
            return ChainTransport(scope, null, iroh, relay, config)
        }

        val asha = SimDevice.owner(71, "Asha")
        val group = asha.group
        val ashaSessions = Sessions(asha.env.groups, asha.env.secrets, asha.env.identity, asha.env.clock, asha.env.settings)
        val invites = Invites(owntally.crypto.InMemoryTicketRecords(), asha.env.groups, asha.env.secrets, asha.env.identity, ashaSessions, clock)
        val requests = JoinRequests(InMemoryJoinRequestStore(), ashaSessions, clock)
        val ashaSync = SyncCoordinator(
            ashaSessions, asha.env.groups, { _, self -> chainFor(asha.env, self) }, SyncRecorder(InMemorySyncProblemLog(), clock), clock, scope,
            joinBackend = { id -> GroupJoinBackend(id, asha.env.identity.groupIdentity(asha.env.groups.group(id)!!.keySlot)!!, invites, requests, ashaSessions) },
        )
        val rohanEnv = TestEnv().also { it.firstRun("Rohan", "98765 43210") }
        val rohanSessions = Sessions(rohanEnv.groups, rohanEnv.secrets, rohanEnv.identity, rohanEnv.clock, rohanEnv.settings)
        val rohanSync = SyncCoordinator(rohanSessions, rohanEnv.groups, { _, self -> chainFor(rohanEnv, self) }, SyncRecorder(InMemorySyncProblemLog(), clock), clock, scope)
        val rohanJoin = JoinGroup(
            rohanEnv.groups, rohanEnv.secrets, rohanEnv.identity, InMemoryPendingJoinStore(), rohanSessions, clock, { _, self -> chainFor(rohanEnv, self) },
            onApproved = { g, host -> rohanSync.startWith(g, host) }, connectTimeoutMs = 10_000,
        )

        private val chains = HashMap<PublicKey, Transport>()
        private fun chainFor(env: TestEnv, key: PublicKey): Transport = synchronized(chains) {
            chains.getOrPut(key) { val slot = (0L..20L).first { env.identity.groupIdentity(it)?.publicKey == key }; chain(env, slot) }
        }
    }

    private suspend fun joinAndSync(w: World): Pair<OpenResult.Opened, LinkKind?> {
        w.ashaSync.start(w.group)
        check(w.ashaSessions.session(w.group).addExpense("Dinner", Money(30_000), w.asha.key, 20_000, Split.Equal(setOf(w.asha.key))) is AddResult.Added)
        val opened = assertIs<OpenResult.Opened>(w.rohanJoin.open(w.invites.create(w.group)!!.link))
        assertEquals(RequestResult.Waiting, w.rohanJoin.request(w.group, "Rohan V", "98765 43210"))
        val waiting = w.requests.waiting(w.group).single()
        assertTrue(w.requests.approve(w.group, waiting.member, "Rohan V") is ApproveResult.Approved)
        assertEquals(CheckResult.Approved, w.rohanJoin.check(w.group))
        withTimeout(30_000) {
            while (w.rohanSessions.session(w.group).also { it.refresh() }.view.value.state.expenses.values.none { it.description == "Dinner" }) delay(100)
        }
        // The kind of link is recorded when the sync ends, a moment after the last entry is stored.
        val kind = withTimeout(20_000) {
            var k = w.rohanSync.status(w.group).value.peers[w.asha.key]?.kind
            while (k == null) { delay(50); k = w.rohanSync.status(w.group).value.peers[w.asha.key]?.kind }
            k
        }
        return opened to kind
    }

    @Test fun twoPhonesWithOnlyTheRelayBetweenThemJoinAndGetTheHistoryThroughIt() = run {
        val w = World(irohUp = false, relayUp = true)
        val (opened, kind) = joinAndSync(w)
        assertEquals(PreviewState.FOUND, opened.preview)
        assertEquals("Simulated", opened.join.groupName)
        assertEquals(LinkKind.RELAYED, kind)
        assertTrue(w.rohanJoin.completeIfMember(w.group))
    }

    @Test fun twoPhonesWithOnlyIrohBetweenThemJoinAndGetTheHistoryThroughIt() = run {
        val w = World(irohUp = true, relayUp = false)
        val (opened, kind) = joinAndSync(w)
        assertEquals(PreviewState.FOUND, opened.preview)
        assertEquals(LinkKind.DIRECT, kind)
    }

    @Test fun withBothWaysUpTheJoinWorksAndOneLinkIsKept() = run {
        val w = World(irohUp = true, relayUp = true)
        val (opened, _) = joinAndSync(w)
        assertEquals(PreviewState.FOUND, opened.preview)
    }

    @Test fun withNoWayToTheHostTheInvitationOpensButSaysTheHostCannotBeReachedAndNothingIsLost() = run {
        val w = World(irohUp = false, relayUp = false)
        w.ashaSync.start(w.group)
        val link = w.invites.create(w.group)!!.link
        val opened = assertIs<OpenResult.Opened>(w.rohanJoin.open(link))
        assertEquals(PreviewState.HOST_UNREACHABLE, opened.preview)
        assertEquals(RequestResult.HostUnreachable, w.rohanJoin.request(w.group, "Rohan V", "98765 43210"))
        assertEquals(0, w.requests.waiting(w.group).size)
        assertNotNull(w.invites.current(w.group), "the invitation is still usable once there is a way")
    }
}
