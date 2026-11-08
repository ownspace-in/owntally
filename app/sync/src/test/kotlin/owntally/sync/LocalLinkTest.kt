package owntally.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
import kotlinx.coroutines.flow.toList
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

/** T6.7: the plain local link, over real sockets on this machine's loopback address. */
class LocalLinkTest {
    private val seed = MasterSeed(ByteArray(64) { 8 })
    private val a = KeyDerivation.groupIdentity(seed, 0)
    private val b = KeyDerivation.groupIdentity(seed, 1)
    private val m = KeyDerivation.groupIdentity(seed, 2)
    private val group = GroupId(ByteArray(16) { 3 })
    private val room = GroupRoom.fromGroupId(group.bytes())
    private val loopback = InetAddress.getLoopbackAddress()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discovery = InMemoryLocalDiscovery()
    private val config = LocalConfig(handshakeTimeoutMs = 1_500, findTimeoutMs = 500, bind = loopback)
    private val nodes = ArrayList<LocalNode>()
    private fun node(): LocalNode = LocalNode(discovery, scope, config).also { nodes += it }

    private fun finish() { nodes.forEach { it.close() }; scope.cancel() }
    private fun <T> run(block: suspend CoroutineScope.() -> T): T = try { runBlocking { withTimeout(30_000) { block() } } } finally { finish() }

    // ---- frames -------------------------------------------------------------------------------------------------------------------

    private fun frames(vararg f: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> f.forEach { Frames.write(o, it) } }.toByteArray()

    /** A stream that gives one byte at a time, like a slow network. */
    private class Trickle(private val bytes: ByteArray) : InputStream() {
        private var i = 0
        override fun read(): Int = if (i < bytes.size) bytes[i++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else if (i >= bytes.size) -1 else { b[off] = bytes[i++]; 1 }
    }

    @Test fun framesRoundTripInOrderIncludingEmptyAndTheLargestAndFromAnyTrickle() {
        val all = listOf(byteArrayOf(), byteArrayOf(1), ByteArray(300) { it.toByte() }, ByteArray(Frames.MAX) { 7 })
        val stream = frames(*all.toTypedArray())
        for (input in listOf(ByteArrayInputStream(stream), Trickle(stream))) {
            for (f in all) assertContentEquals(f, Frames.read(input))
            assertNull(Frames.read(input), "a clean end between frames")
        }
    }

    @Test fun anOverLongFrameIsNeverWrittenOrRead() {
        assertFailsWith<Frames.Bad> { Frames.write(ByteArrayOutputStream(), ByteArray(Frames.MAX + 1)) }
        val huge = java.nio.ByteBuffer.allocate(4).putInt(Frames.MAX + 1).array()
        assertFailsWith<Frames.Bad> { Frames.read(ByteArrayInputStream(huge + ByteArray(10))) }
        val negative = java.nio.ByteBuffer.allocate(4).putInt(-1).array()
        assertFailsWith<Frames.Bad> { Frames.read(ByteArrayInputStream(negative)) }
    }

    @Test fun aFrameCutOffAnywhereIsAnErrorNotAShortMessage() {
        val whole = frames(ByteArray(50) { 1 })
        for (cut in 1 until whole.size) assertFailsWith<Frames.Bad>("cut at $cut") { Frames.read(ByteArrayInputStream(whole.copyOf(cut))) }
    }

    // ---- names --------------------------------------------------------------------------------------------------------------------

    @Test fun aServiceNameIsTwentyPlainCharactersThatSayNothingAboutTheGroupOrThePerson() {
        val name = LocalNames.serviceName(room, a.publicKey)
        assertEquals(20, name.length)
        assertTrue(name.all { it in 'a'..'z' || it in '2'..'7' })
        assertEquals(name, LocalNames.serviceName(room, a.publicKey))
        assertNotEquals(name, LocalNames.serviceName(room, b.publicKey))
        assertNotEquals(name, LocalNames.serviceName(GroupRoom.fromGroupId(ByteArray(16) { 9 }), a.publicKey))
        val hexKey = a.publicKey.bytes().joinToString("") { "%02x".format(it) }
        assertFalse(name in hexKey || hexKey.startsWith(name.take(6)))
    }

    @Test fun theTlsLinkIsFoundUnderItsOwnNameAndStillSaysNothing() {
        val plain = LocalNames.serviceName(room, a.publicKey)
        val tls = LocalNames.serviceName(room, a.publicKey, LinkSecurity.TLS)
        assertEquals(20, tls.length)
        assertNotEquals(plain, tls)
        assertEquals(tls, LocalNames.serviceName(room, a.publicKey, LinkSecurity.TLS))
        assertNotEquals(tls, LocalNames.serviceName(room, b.publicKey, LinkSecurity.TLS))
    }

    // ---- the link -----------------------------------------------------------------------------------------------------------------

    private suspend fun connected(): Triple<Link, Link, Pair<LocalNode, LocalNode>> {
        val na = node(); val nb = node()
        val tb = nb.transportFor(b)
        val incoming = tb.incoming(room)
        val link = na.transportFor(a).connect(room, b.publicKey)
        val other = withTimeout(5_000) { incoming.first() }
        return Triple(link, other, na to nb)
    }

    @Test fun twoPhonesFindEachOtherProveTheirKeysAndExchangeMessagesBothWays() = run {
        val (ab, ba, _) = connected()
        assertEquals(b.publicKey, ab.peer); assertEquals(a.publicKey, ba.peer)
        assertEquals(LinkKind.DIRECT, ab.kind)
        ab.send(byteArrayOf(1)); ab.send(byteArrayOf(2, 2)); ba.send(byteArrayOf(9))
        val got = ba.received
        assertContentEquals(byteArrayOf(1), got.first())
        assertContentEquals(byteArrayOf(9), ab.received.first())
        val big = ByteArray(Frames.MAX) { (it * 7).toByte() }
        ba.send(big)
        assertContentEquals(big, withTimeout(10_000) { ab.received.first { it.size > 100 } })
    }

    @Test fun whenOneSideClosesTheOtherSidesMessagesEnd() = run {
        val (ab, ba, _) = connected()
        ab.send(byteArrayOf(5))
        ab.close()
        assertEquals(listOf(5), withTimeout(5_000) { ba.received.toList().map { it[0].toInt() } })
    }

    @Test fun aWholeSyncRunsOverTheLink() = run {
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
        assertEquals(2, sa.summary().entries.size)
    }

    @Test fun aPhoneThatIsNotFoundIsUnreachable() = run {
        val na = node()
        assertFailsWith<PeerUnreachable> { na.transportFor(a).connect(room, b.publicKey) }
    }

    @Test fun anAddressWhereNobodyListensIsUnreachable() = run {
        val dead = ServerSocket(0, 1, loopback).also { it.close() }
        discovery.point(room, b.publicKey, InetSocketAddress(loopback, dead.localPort))
        assertFailsWith<PeerUnreachable> { node().transportFor(a).connect(room, b.publicKey) }
    }

    // ---- who it will talk to --------------------------------------------------------------------------------------------------------

    @Test fun aPhoneThatAnswersForAnotherKeyIsNotBelieved() = run {
        // Mallory's phone is found where Ben should be and answers with its own key.
        val nm = node(); val tm = nm.transportFor(m); tm.incoming(room)
        discovery.point(room, b.publicKey, InetSocketAddress(loopback, nm.port))
        assertFailsWith<owntally.sync.LinkAuthFailed> { node().transportFor(a).connect(room, b.publicKey) }
    }

    /** A server that does the handshake as [who] whatever it was asked for: a phone that claims to be someone it is not. */
    private fun impostorServer(who: owntally.crypto.GroupIdentity): ServerSocket {
        val server = ServerSocket(0, 5, loopback)
        scope.launch(Dispatchers.IO) {
            val s = runCatching { server.accept() }.getOrNull() ?: return@launch
            runCatching {
                val input = s.getInputStream().buffered()
                val hello = LocalHandshake.Hello.decode(Frames.read(input)!!)!!
                val nonce = LinkAuth.newNonce()
                val transcript = LocalHandshake.transcript(hello, LocalHandshake.KIND_PLAIN, nonce)
                Frames.write(s.getOutputStream(), LocalHandshake.encodeReply(LocalHandshake.KIND_PLAIN, nonce, LinkAuth.signAsServer(who, transcript)))
                Thread.sleep(500)
            }
            runCatching { s.close() }
        }
        return server
    }

    @Test fun aServerThatSignsWithTheWrongKeyIsRefusedByTheCaller() = run {
        val fake = impostorServer(m)
        discovery.point(room, b.publicKey, InetSocketAddress(loopback, fake.localPort))
        val e = assertFailsWith<LinkAuthFailed> { node().transportFor(a).connect(room, b.publicKey) }
        assertTrue("does not hold the key" in e.message!!)
        fake.close()
    }

    /** Opens a raw connection to [nb] as a caller claiming [from], giving back what the server answered; [signWith] makes the caller's proof. */
    private fun rawCaller(nb: LocalNode, from: PublicKey, to: PublicKey, signWith: owntally.crypto.GroupIdentity?, roomName: GroupRoom = room): Socket {
        val s = Socket(loopback, nb.port)
        s.soTimeout = 2_000
        val hello = LocalHandshake.Hello(LocalHandshake.KIND_PLAIN, roomName, from, to, LinkAuth.newNonce())
        Frames.write(s.getOutputStream(), hello.encode())
        val reply = Frames.read(s.getInputStream().buffered())?.let { LocalHandshake.decodeReply(it) }
        if (reply != null) {
            val transcript = LocalHandshake.transcript(hello, reply.chosen, reply.nonce)
            val sig = if (signWith != null) LinkAuth.signAsClient(signWith, transcript) else ByteArray(64)
            Frames.write(s.getOutputStream(), LocalHandshake.encodeProof(sig))
        }
        return s
    }

    private suspend fun assertNoLink(incoming: kotlinx.coroutines.flow.Flow<Link>, message: String) =
        assertNull(withTimeoutOrNull(800) { incoming.first() }, message)

    @Test fun aCallerThatCannotSignForTheKeyItClaimsGetsNoLink() = run {
        val nb = node(); val incoming = nb.transportFor(b).incoming(room)
        rawCaller(nb, from = a.publicKey, to = b.publicKey, signWith = m).close() // claims to be Asha, signs as Mallory
        assertNoLink(incoming, "a proof by another key")
        rawCaller(nb, from = a.publicKey, to = b.publicKey, signWith = null).close()
        assertNoLink(incoming, "a proof of zeros")
        rawCaller(nb, from = a.publicKey, to = b.publicKey, signWith = a).use { assertTrue(withTimeout(5_000) { incoming.first() }.peer == a.publicKey, "the real thing still works afterwards") }
    }

    @Test fun aRecordedHandshakeCannotBeReplayedOnAnotherConnection() = run {
        val nb = node(); val incoming = nb.transportFor(b).incoming(room)
        // Record a real caller's hello and proof by playing the caller by hand once.
        val hello = LocalHandshake.Hello(LocalHandshake.KIND_PLAIN, room, a.publicKey, b.publicKey, LinkAuth.newNonce())
        var recordedProof: ByteArray
        Socket(loopback, nb.port).use { s ->
            Frames.write(s.getOutputStream(), hello.encode())
            val reply = LocalHandshake.decodeReply(Frames.read(s.getInputStream().buffered())!!)!!
            recordedProof = LocalHandshake.encodeProof(LinkAuth.signAsClient(a, LocalHandshake.transcript(hello, reply.chosen, reply.nonce)))
            Frames.write(s.getOutputStream(), recordedProof)
            assertEquals(a.publicKey, withTimeout(5_000) { incoming.first() }.peer, "the first time it works")
        }
        // A new connection with the same hello and the same proof: the server's nonce is new, so the proof no longer fits.
        Socket(loopback, nb.port).use { s ->
            s.soTimeout = 2_000
            Frames.write(s.getOutputStream(), hello.encode())
            Frames.read(s.getInputStream().buffered())
            Frames.write(s.getOutputStream(), recordedProof)
        }
        assertNoLink(incoming, "a replay")
    }

    @Test fun aCallThatNamesAnotherRoomOrAnotherKeyOrIsGarbageGetsNothing() = run {
        val nb = node(); val incoming = nb.transportFor(b).incoming(room)
        for (call in listOf<() -> Socket>(
            { rawCaller(nb, a.publicKey, b.publicKey, a, GroupRoom(ByteArray(16) { 1 })) },
            { rawCaller(nb, a.publicKey, m.publicKey, a) },
        )) {
            call().use { s -> assertNull(runCatching { Frames.read(s.getInputStream().buffered()) }.getOrNull(), "closed without a word") }
        }
        Socket(loopback, nb.port).use { s -> s.soTimeout = 3_000; s.getOutputStream().write(ByteArray(40) { 0x41 }); s.getOutputStream().flush(); assertEquals(-1, runCatching { s.getInputStream().read() }.getOrElse { -2 }) }
        Socket(loopback, nb.port).use { s -> s.soTimeout = 3_000; s.getOutputStream().write(byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f)); s.getOutputStream().flush(); assertEquals(-1, runCatching { s.getInputStream().read() }.getOrElse { -2 }, "a frame length over the limit: closed, not waiting") }
        assertNoLink(incoming, "none of it made a link")
    }

    @Test fun aConnectionThatSaysNothingDoesNotBlockTheNextCaller() = run {
        val nb = node(); val incoming = nb.transportFor(b).incoming(room)
        Socket(loopback, nb.port).use {
            val link = node().transportFor(a).connect(room, b.publicKey)
            assertEquals(a.publicKey, withTimeout(5_000) { incoming.first() }.peer)
            link.close()
        }
    }

    @Test fun aServerThatNeverAnswersTimesTheCallerOutQuickly() = run {
        val silent = ServerSocket(0, 5, loopback)
        discovery.point(room, b.publicKey, InetSocketAddress(loopback, silent.localPort))
        val started = System.currentTimeMillis()
        assertFailsWith<PeerUnreachable> { node().transportFor(a).connect(room, b.publicKey) }
        assertTrue(System.currentTimeMillis() - started < 5_000)
        silent.close()
    }

    @Test fun oneNodeAnswersForSeveralGroupsAndKeysAndKeepsThemApart() = run {
        val nb = node()
        val room2 = GroupRoom.fromGroupId(ByteArray(16) { 5 })
        val b2 = KeyDerivation.groupIdentity(seed, 50)
        val in1 = nb.transportFor(b).incoming(room)
        val in2 = nb.transportFor(b2).incoming(room2)
        val caller = node().transportFor(a)
        caller.connect(room2, b2.publicKey)
        assertEquals(a.publicKey, withTimeout(5_000) { in2.first() }.peer)
        assertNoLink(in1, "the other group heard nothing")
        caller.connect(room, b.publicKey)
        assertEquals(a.publicKey, withTimeout(5_000) { in1.first() }.peer)
        assertEquals(1, setOf(nb.port).size)
    }

    @Test fun aPhoneInTheMiddleCannotPassOffOneConversationsAnswerAsAnothersToGetInAsSomeoneElse() = run {
        // Mallory sits between Asha and Ben. Asha calls "Ben" (really Mallory). Mallory calls Ben as Asha, takes Ben's answer and hands it to
        // Asha as if it were the answer to Asha's call, hoping Asha's proof (made for that answer) lets Mallory into Ben's phone as Asha.
        val nb = node(); val incoming = nb.transportFor(b).incoming(room)
        val mitm = ServerSocket(0, 5, loopback)
        discovery.point(room, b.publicKey, InetSocketAddress(loopback, mitm.localPort))
        val forwarded = scope.async(Dispatchers.IO) {
            val fromAsha = mitm.accept()
            runCatching {
                fromAsha.soTimeout = 3_000
                Frames.read(fromAsha.getInputStream().buffered()) // Asha's hello, which Mallory does not use
                val toBen = Socket(loopback, nb.port)
                toBen.soTimeout = 3_000
                val myHello = LocalHandshake.Hello(LocalHandshake.KIND_PLAIN, room, a.publicKey, b.publicKey, LinkAuth.newNonce())
                Frames.write(toBen.getOutputStream(), myHello.encode())
                val reply = Frames.read(toBen.getInputStream().buffered())!!
                Frames.write(fromAsha.getOutputStream(), reply) // Ben's answer to Mallory's hello, given to Asha
                val proof = Frames.read(fromAsha.getInputStream().buffered()) // Asha's proof, if she sends one
                if (proof != null) Frames.write(toBen.getOutputStream(), proof)
                proof != null
            }.getOrDefault(false)
        }
        val e = assertFailsWith<LinkAuthFailed> { node().transportFor(a).connect(room, b.publicKey) }
        assertTrue(e.message!!.isNotEmpty())
        assertFalse(forwarded.await(), "Asha sent no proof")
        assertNoLink(incoming, "Mallory did not get into Ben's phone as Asha")
        mitm.close()
    }

    @Test fun aMessageSentInTheSameBreathAsTheProofIsNotLostToTheHandshakesBuffer() = run {
        val nb = node(); val incoming = nb.transportFor(b).incoming(room)
        Socket(loopback, nb.port).use { s ->
            val hello = LocalHandshake.Hello(LocalHandshake.KIND_PLAIN, room, a.publicKey, b.publicKey, LinkAuth.newNonce())
            Frames.write(s.getOutputStream(), hello.encode())
            val reply = LocalHandshake.decodeReply(Frames.read(s.getInputStream().buffered())!!)!!
            val proof = LocalHandshake.encodeProof(LinkAuth.signAsClient(a, LocalHandshake.transcript(hello, reply.chosen, reply.nonce)))
            // The proof and the first three messages in one write, so they reach the answerer together.
            s.getOutputStream().write(frames(proof, byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)))
            s.getOutputStream().flush()
            val link = withTimeout(5_000) { incoming.first() }
            assertEquals(listOf<Byte>(1, 2, 3), withTimeout(5_000) { link.received.toList2(3) }.map { it[0] })
        }
    }

    private suspend fun kotlinx.coroutines.flow.Flow<ByteArray>.toList2(n: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        this.take(n).collect { out += it }
        return out
    }
}
