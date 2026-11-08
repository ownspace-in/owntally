package owntally.data.sim

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import owntally.crypto.Clock
import owntally.data.session.AddResult
import owntally.data.session.PeerState
import owntally.data.session.Sessions
import owntally.data.session.SyncConfig
import owntally.data.session.SyncCoordinator
import owntally.data.session.SyncRecorder
import owntally.data.store.InMemorySyncProblemLog
import owntally.model.Money
import owntally.model.Split
import owntally.sync.InMemoryLocalDiscovery
import owntally.sync.LinkSecurity
import owntally.sync.LocalConfig
import owntally.sync.LocalNode

/** T6.10: two real coordinators over real local links on loopback (TLS first), real time. */
class CoordinatorOverLocalLinksTest {
    private fun phonesOver(offerTlsOnBen: Boolean): Triple<Pair<SyncCoordinator, SimDevice>, Pair<SyncCoordinator, SimDevice>, () -> Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val discovery = InMemoryLocalDiscovery()
        val asha = SimDevice.owner(71, "Asha")
        val ben = SimDevice.joined(71, 1, "Ben", asha)
        asha.env.groups.rawEnvelopes(asha.group).forEach { ben.env.groups.appendEnvelope(ben.group, owntally.model.Envelope.decode(it)) }
        fun coordinator(d: SimDevice, offerTls: Boolean): Pair<SyncCoordinator, LocalNode> {
            val node = LocalNode(discovery, scope, LocalConfig(handshakeTimeoutMs = 3_000, findTimeoutMs = 300, tlsAttemptMs = 15_000, offerTls = offerTls, bind = InetAddress.getLoopbackAddress()))
            val sessions = Sessions(d.env.groups, d.env.secrets, d.env.identity, d.env.clock, d.env.settings)
            val c = SyncCoordinator(
                sessions, d.env.groups, { _, self -> node.secureFirstTransportFor(d.env.identity.groupIdentity(d.slot)!!.also { check(it.publicKey == self) }) },
                SyncRecorder(InMemorySyncProblemLog(), Clock.SYSTEM), Clock.SYSTEM, scope, SyncConfig(intervalMs = 3_600_000, stallTimeoutMs = 10_000),
            )
            return c to node
        }
        val (ca, na) = coordinator(asha, true)
        val (cb, nb) = coordinator(ben, offerTlsOnBen)
        return Triple(ca to asha, cb to ben, { na.close(); nb.close(); scope.cancel() })
    }


    /** The sync that start() begins may still be under way when syncNow returns (a change made during one is sent by the next): wait for the state to settle. */
    private suspend fun settled(c: SyncCoordinator, group: owntally.model.GroupId, peer: owntally.model.PublicKey): owntally.data.session.PeerStatus {
        repeat(200) {
            val st = c.status(group).value.peers[peer]
            if (st != null && st.state == PeerState.OK) return st
            kotlinx.coroutines.delay(100)
        }
        return c.status(group).value.peers[peer]!!
    }

    @Test fun twoCoordinatorsSyncOverTlsAndTheStatusSaysSo() {
        val (a, b, done) = phonesOver(offerTlsOnBen = true)
        try {
            runBlocking {
                withTimeout(40_000) {
                    a.first.start(a.second.group); b.first.start(b.second.group)
                    kotlinx.coroutines.delay(600) // both have begun listening
                    assertTrue(a.second.session.addExpense("Dinner", Money(30_000), a.second.key, 20_000, Split.Equal(setOf(a.second.key, b.second.key))) is AddResult.Added)
                    a.first.syncNow(a.second.group)
                    val status = settled(a.first, a.second.group, b.second.key)
                    assertEquals(PeerState.OK, status.state)
                    assertEquals(LinkSecurity.TLS, status.security)
                    b.second.session.refresh()
                    assertTrue(b.second.session.view.value.state.expenses.values.any { it.description == "Dinner" })
                    // The answering side records it too.
                    assertEquals(LinkSecurity.TLS, settled(b.first, b.second.group, a.second.key).security)
                }
            }
        } finally { done() }
    }

    @Test fun aPhoneWithoutTlsIsSyncedOverThePlainLinkAndTheStatusSaysSo() {
        val (a, b, done) = phonesOver(offerTlsOnBen = false)
        try {
            runBlocking {
                withTimeout(40_000) {
                    a.first.start(a.second.group); b.first.start(b.second.group)
                    kotlinx.coroutines.delay(600) // both have begun listening
                    a.first.syncNow(a.second.group)
                    val status = settled(a.first, a.second.group, b.second.key)
                    assertEquals(PeerState.OK, status.state)
                    assertEquals(LinkSecurity.PLAIN, status.security)
                }
            }
        } finally { done() }
    }
}
