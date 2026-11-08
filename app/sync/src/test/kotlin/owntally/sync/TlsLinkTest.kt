package owntally.sync

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import owntally.crypto.EnvelopeSigning
import owntally.crypto.KeyDerivation
import owntally.crypto.LinkAuth
import owntally.crypto.MasterSeed
import owntally.model.GroupId
import owntally.model.PublicKey

/** T6.8: the same local link, inside TLS 1.3 (platform TLS, certificates that carry the members' keys), over real sockets on loopback. */
class TlsLinkTest {
    private val seed = MasterSeed(ByteArray(64) { 8 })
    private val a = KeyDerivation.groupIdentity(seed, 0)
    private val b = KeyDerivation.groupIdentity(seed, 1)
    private val m = KeyDerivation.groupIdentity(seed, 2)
    private val group = GroupId(ByteArray(16) { 3 })
    private val room = GroupRoom.fromGroupId(group.bytes())
    private val loopback = InetAddress.getLoopbackAddress()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discovery = InMemoryLocalDiscovery()
    private val config = LocalConfig(handshakeTimeoutMs = 3_000, findTimeoutMs = 500, bind = loopback)
    private val nodes = ArrayList<LocalNode>()
    private fun node(): LocalNode = LocalNode(discovery, scope, config).also { nodes += it }
    private fun run(block: suspend CoroutineScope.() -> Unit): Unit = try { runBlocking { withTimeout(40_000) { block() } } } finally { nodes.forEach { it.close() }; scope.cancel() }

    private suspend fun connected(security: LinkSecurity = LinkSecurity.TLS): Triple<Link, Link, LocalNode> {
        val nb = node()
        val incoming = nb.transportFor(b).incoming(room)
        val link = node().transportFor(a, security = security).connect(room, b.publicKey)
        return Triple(link, withTimeout(10_000) { incoming.first() }, nb)
    }

    @Test fun twoPhonesTalkInsideTlsAndEachKnowsWhoTheOtherIs() = run {
        val (ab, ba, _) = connected()
        assertEquals(b.publicKey, ab.peer); assertEquals(a.publicKey, ba.peer)
        ab.send(byteArrayOf(1)); ba.send(byteArrayOf(9))
        assertContentEquals(byteArrayOf(1), ba.received.first())
        assertContentEquals(byteArrayOf(9), ab.received.first())
        val big = ByteArray(Frames.MAX) { (it * 5).toByte() }
        ab.send(big)
        assertContentEquals(big, withTimeout(15_000) { ba.received.first { it.size > 100 } })
    }

    @Test fun aWholeSyncRunsInsideTls() = run {
        val (ab, ba, _) = connected()
        fun env(n: Int, c: Long): ByteArray = EnvelopeSigning.sign(KeyDerivation.groupIdentity(seed, 100L + n), 1, group, 1, c, ByteArray(24), ByteArray(60) { (c + it).toByte() }).encode()
        val sa = InMemorySyncStore(group).also { s -> (1L..40L).forEach { s.offer(env(1, it)) } }
        val sb = InMemorySyncStore(group).also { s -> (1L..25L).forEach { s.offer(env(2, it)) } }
        coroutineScope {
            val x = async { SyncEngine(room, sa).run(ab, SyncRole.INITIATOR) }
            val y = async { SyncEngine(room, sb).run(ba, SyncRole.RESPONDER) }
            assertEquals(SyncOutcome.COMPLETED, x.await().outcome)
            assertEquals(SyncOutcome.COMPLETED, y.await().outcome)
        }
        assertEquals(sa.summary(), sb.summary())
    }

    @Test fun aPlainCallerAndATlsCallerAreBothAnsweredByOneNode() = run {
        val nb = node(); val incoming = nb.transportFor(b).incoming(room)
        node().transportFor(a, security = LinkSecurity.PLAIN).connect(room, b.publicKey)
        assertEquals(a.publicKey, withTimeout(10_000) { incoming.first() }.peer)
        val c = KeyDerivation.groupIdentity(seed, 9)
        node().transportFor(c, security = LinkSecurity.TLS).connect(room, b.publicKey)
        assertEquals(c.publicKey, withTimeout(10_000) { incoming.first() }.peer)
    }

    // ---- what can be seen on the wire -----------------------------------------------------------------------------------------------

    /** A relay that copies everything both ways and keeps what went through, standing in for someone on the network. */
    private class Tap(private val target: InetSocketAddress, scope: CoroutineScope) {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val seen = java.io.ByteArrayOutputStream()
        init {
            scope.launch(Dispatchers.IO) {
                val inbound = server.accept()
                val outbound = Socket(target.address, target.port)
                fun pump(from: Socket, to: Socket) = scope.launch(Dispatchers.IO) {
                    runCatching {
                        val buf = ByteArray(8192)
                        while (true) { val n = from.getInputStream().read(buf); if (n < 0) break; synchronized(seen) { seen.write(buf, 0, n) }; to.getOutputStream().write(buf, 0, n); to.getOutputStream().flush() }
                    }
                    runCatching { to.close() }
                }
                pump(inbound, outbound); pump(outbound, inbound)
            }
        }
        fun bytes(): ByteArray = synchronized(seen) { seen.toByteArray() }
        fun contains(marker: ByteArray) = bytes().toList().windowed(marker.size).any { it == marker.toList() }
    }

    private suspend fun throughATap(security: LinkSecurity, marker: ByteArray): Tap {
        val nb = node(); val incoming = nb.transportFor(b).incoming(room)
        val tap = Tap(InetSocketAddress(loopback, if (security == LinkSecurity.TLS) nb.tlsPortOf(room, b.publicKey) else nb.port), scope)
        discovery.point(room, b.publicKey, InetSocketAddress(loopback, tap.server.localPort), security)
        val link = node().transportFor(a, security = security).connect(room, b.publicKey)
        val other = withTimeout(10_000) { incoming.first() }
        link.send(marker)
        assertContentEquals(marker, withTimeout(10_000) { other.received.first() })
        return tap
    }

    @Test fun onTheWireAMessageIsReadableOnAPlainLinkAndNotInsideTls() = run {
        val marker = "SECRET-AMOUNT-4242-RUPEES".toByteArray()
        assertTrue(throughATap(LinkSecurity.PLAIN, marker).contains(marker), "the plain link shows the message (the envelope inside it is what is encrypted)")
        val tls = throughATap(LinkSecurity.TLS, marker)
        assertFalse(tls.contains(marker), "TLS hides it")
        assertFalse(tls.contains(a.publicKey.bytes()), "and who is calling")
        assertFalse(tls.contains(b.publicKey.bytes()), "and who is called")
    }

    // ---- who it will talk to -------------------------------------------------------------------------------------------------------

    private fun serverContextFor(identity: owntally.crypto.GroupIdentity): Pair<SSLContext, TlsCertificates.Issued> {
        val issued = TlsCertificates.issue(identity)
        val ks = KeyStore.getInstance("PKCS12").apply { load(null); setKeyEntry("k", issued.keyPair.private, CharArray(0), arrayOf(issued.certificate)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), null) } to issued
    }

    @Test fun aServerWhoseCertificateCarriesAnotherMembersKeyIsRefused() = run {
        // Found where Ben should be, Mallory shows a perfectly good certificate: bound to Mallory's key.
        val (ctx, _) = serverContextFor(m)
        val server = ctx.serverSocketFactory.createServerSocket(0, 5, loopback) as javax.net.ssl.SSLServerSocket
        scope.launch(Dispatchers.IO) { runCatching { (server.accept() as SSLSocket).startHandshake() } }
        discovery.point(room, b.publicKey, InetSocketAddress(loopback, server.localPort), LinkSecurity.TLS)
        assertFailsWith<LinkAuthFailed> { node().transportFor(a, security = LinkSecurity.TLS).connect(room, b.publicKey) }
        server.close()
    }

    @Test fun aServerWithAnOrdinarySelfSignedCertificateIsRefused() = run {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(java.io.ByteArrayInputStream(TlsCertificates.buildWithoutBinding(pair))) as X509Certificate
        val ks = KeyStore.getInstance("PKCS12").apply { load(null); setKeyEntry("k", pair.private, CharArray(0), arrayOf(cert)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val server = ctx.serverSocketFactory.createServerSocket(0, 5, loopback)
        scope.launch(Dispatchers.IO) { runCatching { (server.accept() as SSLSocket).startHandshake() } }
        discovery.point(room, b.publicKey, InetSocketAddress(loopback, server.localPort), LinkSecurity.TLS)
        assertFailsWith<LinkAuthFailed> { node().transportFor(a, security = LinkSecurity.TLS).connect(room, b.publicKey) }
        server.close()
    }

    /** A TLS caller by hand: [identity]'s certificate (or an ordinary one), asking for [name]. */
    private fun rawTlsCaller(nb: LocalNode, name: String, certificate: TlsCertificates.Issued?, protocols: Array<String> = arrayOf("TLSv1.3")): SSLSocket {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null); if (certificate != null) setKeyEntry("k", certificate.keyPair.private, CharArray(0), arrayOf(certificate.certificate)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), null) }
        val s = ctx.socketFactory.createSocket(Socket(loopback, nb.tlsPortOf(room, b.publicKey)), "localhost", nb.tlsPortOf(room, b.publicKey), true) as SSLSocket
        s.enabledProtocols = protocols
        s.soTimeout = 3_000
        return s
    }

    private suspend fun assertNoLink(incoming: kotlinx.coroutines.flow.Flow<Link>, message: String) = assertNull(withTimeoutOrNull(1_000) { incoming.first() }, message)

    @Test fun aCallerWithoutAMemberKeyOrWithAForgedOneGetsNoLink() = run {
        val nb = node(); val incoming = nb.transportFor(b).incoming(room)
        val name = "unused"
        // no certificate at all
        runCatching { rawTlsCaller(nb, name, null).use { it.startHandshake(); it.outputStream.write(1); it.inputStream.read() } }
        assertNoLink(incoming, "no certificate")
        // an ordinary self-signed certificate
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val ordinary = TlsCertificates.Issued(CertificateFactory.getInstance("X509").generateCertificate(java.io.ByteArrayInputStream(TlsCertificates.buildWithoutBinding(pair))) as X509Certificate, pair)
        runCatching { rawTlsCaller(nb, name, ordinary).use { it.startHandshake(); it.outputStream.write(1); it.inputStream.read() } }
        assertNoLink(incoming, "an ordinary certificate")
        // a certificate that claims Asha but whose binding was made by Mallory
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val forged = TlsCertificates.build(key, a.publicKey.bytes(), LinkAuth.signTlsBinding(m, key.public.encoded))
        val forgedIssued = TlsCertificates.Issued(CertificateFactory.getInstance("X509").generateCertificate(java.io.ByteArrayInputStream(forged)) as X509Certificate, key)
        runCatching { rawTlsCaller(nb, name, forgedIssued).use { it.startHandshake(); it.outputStream.write(1); it.inputStream.read() } }
        assertNoLink(incoming, "a forged claim")
        // and a real one still works afterwards
        rawTlsCaller(nb, name, TlsCertificates.issue(a)).use { it.startHandshake(); assertEquals(a.publicKey, withTimeout(10_000) { incoming.first() }.peer) }
    }

    @Test fun aCallThatOffersOnlyOlderTlsOrSendsGarbageGetsNothing() = run {
        val nb = node(); val incoming = nb.transportFor(b).incoming(room)
        val mine = TlsCertificates.issue(a)
        runCatching { rawTlsCaller(nb, "unused", mine, arrayOf("TLSv1.2")).use { it.startHandshake() } }
        assertNoLink(incoming, "TLS 1.2")
        Socket(loopback, nb.tlsPortOf(room, b.publicKey)).use { s -> s.soTimeout = 3_000; s.getOutputStream().write(byteArrayOf(0x16, 3, 3, 0, 5, 1, 2, 3, 4, 5)); s.getOutputStream().flush(); runCatching { s.getInputStream().read() } }
        assertNoLink(incoming, "a TLS-looking start that is not TLS")
    }

    @Test fun aMessageRightAfterTheTlsHandshakeIsNotLost() = run {
        val (ab, ba, _) = connected()
        repeat(20) { ab.send(byteArrayOf(it.toByte())) }
        val got = withTimeout(10_000) { ba.received.let { f -> val l = ArrayList<Int>(); f.collect2(20) { l += it[0].toInt() }; l } }
        assertEquals((0 until 20).toList(), got)
    }

    private suspend fun kotlinx.coroutines.flow.Flow<ByteArray>.collect2(n: Int, each: (ByteArray) -> Unit) {
        var count = 0
        this.take(n).collect { each(it); count++ }
    }

    @Test fun oneNodeKeepsTheTlsLinksOfSeveralGroupsAndKeysApart() = run {
        val nb = node()
        val room2 = GroupRoom.fromGroupId(ByteArray(16) { 5 })
        val b2 = KeyDerivation.groupIdentity(seed, 50)
        val in1 = nb.transportFor(b).incoming(room)
        val in2 = nb.transportFor(b2).incoming(room2)
        assertTrue(nb.tlsPortOf(room, b.publicKey) != nb.tlsPortOf(room2, b2.publicKey), "a port for each")
        val caller = node().transportFor(a, security = LinkSecurity.TLS)
        caller.connect(room2, b2.publicKey)
        assertEquals(a.publicKey, withTimeout(10_000) { in2.first() }.peer)
        assertNoLink(in1, "the other group heard nothing")
        caller.connect(room, b.publicKey)
        assertEquals(a.publicKey, withTimeout(10_000) { in1.first() }.peer)
    }

    @Test fun aCallerMeantForOneKeyIsRefusedByTheOthersCertificate() = run {
        // Both of Ben's keys are on this node. A caller who wants the first key but is sent to the second one's port must not be let in.
        val nb = node()
        val b2 = KeyDerivation.groupIdentity(seed, 50)
        nb.transportFor(b).incoming(room); nb.transportFor(b2).incoming(room)
        discovery.point(room, b.publicKey, InetSocketAddress(loopback, nb.tlsPortOf(room, b2.publicKey)), LinkSecurity.TLS)
        assertFailsWith<LinkAuthFailed> { node().transportFor(a, security = LinkSecurity.TLS).connect(room, b.publicKey) }
    }
}
