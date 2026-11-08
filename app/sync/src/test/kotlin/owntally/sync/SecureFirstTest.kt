package owntally.sync

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import owntally.crypto.EnvelopeSigning
import owntally.crypto.KeyDerivation
import owntally.crypto.MasterSeed
import owntally.model.GroupId

/** T6.10 (decision L6): TLS first, the plain link if TLS does not connect, and the link says which it is. */
class SecureFirstTest {
    private val seed = MasterSeed(ByteArray(64) { 8 })
    private val a = KeyDerivation.groupIdentity(seed, 0)
    private val b = KeyDerivation.groupIdentity(seed, 1)
    private val m = KeyDerivation.groupIdentity(seed, 2)
    private val group = GroupId(ByteArray(16) { 3 })
    private val room = GroupRoom.fromGroupId(group.bytes())
    private val loopback = InetAddress.getLoopbackAddress()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discovery = InMemoryLocalDiscovery()
    private val nodes = ArrayList<LocalNode>()
    private fun node(offerTls: Boolean = true, tlsMs: Long = 15_000): LocalNode =
        LocalNode(discovery, scope, LocalConfig(handshakeTimeoutMs = 3_000, findTimeoutMs = 300, tlsAttemptMs = tlsMs, offerTls = offerTls, bind = loopback)).also { nodes += it }

    private fun <T> run(block: suspend CoroutineScope.() -> T): T = try { runBlocking { withTimeout(40_000) { block() } } } finally { nodes.forEach { it.close() }; scope.cancel() }

    @Test fun whenBothSupportTlsTheLinkIsTlsOnBothEnds() = run {
        val nb = node(); val incoming = nb.transportFor(b).incoming(room)
        val link = node().secureFirstTransportFor(a).connect(room, b.publicKey)
        assertEquals(LinkSecurity.TLS, link.security)
        assertEquals(LinkSecurity.TLS, withTimeout(10_000) { incoming.first() }.security)
    }

    @Test fun aPhoneThatOffersNoTlsIsReachedOverThePlainLinkAndTheLinkSaysSo() = run {
        val nb = node(offerTls = false); val incoming = nb.transportFor(b).incoming(room)
        assertEquals(-1, nb.tlsPortOf(room, b.publicKey))
        val link = node().secureFirstTransportFor(a).connect(room, b.publicKey)
        assertEquals(LinkSecurity.PLAIN, link.security)
        val other = withTimeout(10_000) { incoming.first() }
        assertEquals(LinkSecurity.PLAIN, other.security)
        link.send(byteArrayOf(7))
        assertEquals(7, withTimeout(5_000) { other.received.first() }[0].toInt())
    }

    @Test fun aTlsPortThatRefusesFallsBackToPlain() = run {
        val nb = node(); val incoming = nb.transportFor(b).incoming(room)
        val dead = ServerSocket(0, 1, loopback).also { it.close() }
        discovery.point(room, b.publicKey, InetSocketAddress(loopback, dead.localPort), LinkSecurity.TLS)
        val link = node().secureFirstTransportFor(a).connect(room, b.publicKey)
        assertEquals(LinkSecurity.PLAIN, link.security)
        assertEquals(LinkSecurity.PLAIN, withTimeout(10_000) { incoming.first() }.security)
    }

    @Test fun aTlsPortThatNeverAnswersFallsBackWithinTheTlsTime() = run {
        val nb = node(); val incoming = nb.transportFor(b).incoming(room)
        val silent = ServerSocket(0, 5, loopback)
        discovery.point(room, b.publicKey, InetSocketAddress(loopback, silent.localPort), LinkSecurity.TLS)
        val started = System.currentTimeMillis()
        val link = node(tlsMs = 800).secureFirstTransportFor(a).connect(room, b.publicKey)
        assertEquals(LinkSecurity.PLAIN, link.security)
        assertTrue(System.currentTimeMillis() - started < 6_000, "did not wait the whole handshake time")
        assertEquals(LinkSecurity.PLAIN, withTimeout(10_000) { incoming.first() }.security)
        silent.close()
    }

    @Test fun aPhoneThatProvesTheWrongKeyInTlsIsRefusedAndTheCallerDoesNotFallBackToPlain() = run {
        // Mallory answers where Ben should be, in TLS, with her own key. A refusal is not a failure to connect: no plain attempt is made.
        val nb = node(); val incoming = nb.transportFor(b).incoming(room)
        val nm = node(); nm.transportFor(m).incoming(room)
        discovery.point(room, b.publicKey, InetSocketAddress(loopback, nm.tlsPortOf(room, m.publicKey)), LinkSecurity.TLS)
        assertFailsWith<LinkAuthFailed> { node().secureFirstTransportFor(a).connect(room, b.publicKey) }
        assertNull(withTimeoutOrNull(800) { incoming.first() }, "Ben's plain port heard nothing")
    }

    @Test fun whenNeitherKindConnectsThePhoneIsUnreachable() = run {
        assertFailsWith<PeerUnreachable> { node().secureFirstTransportFor(a).connect(room, b.publicKey) }
    }

    @Test fun aWholeSyncRunsOverTheChosenLinkAndTheResultSaysHowItWasCarried() = run {
        val nb = node(); val incoming = nb.transportFor(b).incoming(room)
        val ab = node().secureFirstTransportFor(a).connect(room, b.publicKey)
        val ba = withTimeout(10_000) { incoming.first() }
        fun env(n: Int, c: Long): ByteArray = EnvelopeSigning.sign(KeyDerivation.groupIdentity(seed, 100L + n), 1, group, 1, c, ByteArray(24), ByteArray(60) { (c + it).toByte() }).encode()
        val sa = InMemorySyncStore(group).also { s -> (1L..10L).forEach { s.offer(env(1, it)) } }
        val sb = InMemorySyncStore(group).also { s -> (1L..6L).forEach { s.offer(env(2, it)) } }
        coroutineScope {
            val x = async { SyncEngine(room, sa).run(ab, SyncRole.INITIATOR) }
            val y = async { SyncEngine(room, sb).run(ba, SyncRole.RESPONDER) }
            val rx = x.await(); val ry = y.await()
            assertEquals(SyncOutcome.COMPLETED, rx.outcome)
            assertEquals(LinkSecurity.TLS, rx.security); assertEquals(LinkSecurity.TLS, ry.security)
        }
        assertEquals(sa.summary(), sb.summary())
    }

    @Test fun anInMemoryLinkHasNoSecurityToReport() = run {
        val net = InMemoryNetwork()
        val inc = net.transportFor(b.publicKey).incoming(room)
        val l = net.transportFor(a.publicKey).connect(room, b.publicKey)
        assertNull(l.security)
        assertNull(inc.first().security)
    }
}
