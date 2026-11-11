package owntally.sync

import kotlinx.coroutines.delay
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import owntally.crypto.RelayAuth
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import owntally.model.PublicKey

/** A small stand-in for relay/ws-relay that follows the wire protocol in its README. */
private class FakeRelay(
    /** "answer": the real exchange; "refuse": close with 4401 whatever is answered; "silent": send the challenge and then nothing, ever. */
    private val behaviour: String = "answer",
) : AutoCloseable {
    val server = MockWebServer()
    /** Each answer the relay received, with the nonce it had sent and whether it verified (M12). */
    val answers = java.util.concurrent.CopyOnWriteArrayList<Triple<ByteArray, ByteArray, Boolean>>()
    val challenges = java.util.concurrent.atomic.AtomicInteger()
    private val rooms = ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocket>>()
    val framesSeen = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val room = request.requestUrl!!.queryParameter("room")!!
                val id = request.requestUrl!!.queryParameter("id")!!
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    private val nonce = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                    @Volatile private var signedIn = false

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // M12: nothing is forwarded to or from the client until it has proved it holds the key (docs/CRYPTO.md section 7a).
                        challenges.incrementAndGet()
                        webSocket.send((byteArrayOf(0x01) + nonce).toByteString())
                        // "early": a relay (or someone in between) that slips in a frame shaped like a forwarded message before the client is ready.
                        if (behaviour == "early") webSocket.send((ByteArray(32) { 5 } + "hello".toByteArray()).toByteString())
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val frame = bytes.toByteArray()
                        if (!signedIn) {
                            val ok = frame.size == 65 && frame[0].toInt() == 0x02 && RelayAuth.verify(PublicKey.fromHex(id), room.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), nonce, frame.copyOfRange(1, 65))
                            answers += Triple(frame, nonce, ok)
                            if (behaviour == "refuse" || !ok) { webSocket.close(4401, "not the holder of this key"); return }
                            if (behaviour == "silent") return
                            signedIn = true
                            rooms.getOrPut(room) { ConcurrentHashMap() }[id] = webSocket
                            webSocket.send(byteArrayOf(0x03).toByteString())
                            return
                        }
                        framesSeen += frame
                        val members = rooms[room] ?: return
                        val sender = PublicKey.fromHex(id).bytes()
                        when (frame[0].toInt()) {
                            0 -> members.filterKeys { it != id }.values.forEach {
                                it.send((sender + frame.copyOfRange(1, frame.size)).toByteString())
                            }
                            1 -> members[PublicKey(frame.copyOfRange(1, 33)).toHex()]
                                ?.send((sender + frame.copyOfRange(33, frame.size)).toByteString())
                        }
                    }
                })
            }
        }
        server.start()
    }

    fun url() = server.url("/ws").toString().replace("http://", "ws://")

    /** Closes every socket from the relay side, like a restart would. */
    fun dropAll() {
        rooms.values.forEach { room -> room.values.forEach { it.close(1001, "restart") } }
        rooms.clear()
    }

    override fun close() {
        dropAll()
        runCatching { server.shutdown() }
    }
}

class WebSocketTransportTest {
    private val room = GroupRoom(ByteArray(16) { it.toByte() })
    private val seed = owntally.crypto.MasterSeed(ByteArray(64) { 8 })
    private val aliceId = owntally.crypto.KeyDerivation.groupIdentity(seed, 1)
    private val bobId = owntally.crypto.KeyDerivation.groupIdentity(seed, 2)
    private val alice = aliceId.publicKey
    private val bob = bobId.publicKey

    private fun withTransports(block: suspend CoroutineScope.(FakeRelay, WebSocketTransport, WebSocketTransport) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            FakeRelay().use { relay ->
                val a = WebSocketTransport(aliceId, relay.url(), scope, backoffMillis = listOf(50), openTimeoutMillis = 3_000)
                val b = WebSocketTransport(bobId, relay.url(), scope, backoffMillis = listOf(50), openTimeoutMillis = 3_000)
                try {
                    withTimeout(15_000) { coroutineScope { block(relay, a, b) } }
                } finally {
                    a.close()
                    b.close()
                    scope.cancel()
                }
            }
        }

    @Test
    fun targetedMessagesTravelBothWays() = withTransports { relay, a, b ->
        b.incoming(room)
        b.broadcast(room, byteArrayOf(9)) // makes bob join the room
        val toBob = a.connect(room, bob)
        toBob.send(byteArrayOf(1, 2, 3))

        val fromAlice = b.incoming(room).first()
        assertEquals(alice, fromAlice.peer)
        assertEquals(LinkKind.RELAYED, fromAlice.kind)
        assertContentEquals(byteArrayOf(1, 2, 3), fromAlice.received.first())

        fromAlice.send(byteArrayOf(4, 5))
        assertContentEquals(byteArrayOf(4, 5), toBob.received.first())

        val targeted = relay.framesSeen.first { it[0].toInt() == 1 }
        assertContentEquals(bob.bytes(), targeted.copyOfRange(1, 33))
    }

    @Test
    fun broadcastReachesTheOtherMemberAsAnIncomingLink() = withTransports { _, a, b ->
        a.status(room).first { it == RelayStatus.OPEN }
        b.status(room).first { it == RelayStatus.OPEN }

        b.broadcast(room, "hello".toByteArray())

        val link = a.incoming(room).first()
        assertEquals(bob, link.peer)
        assertContentEquals("hello".toByteArray(), link.received.first())
    }

    @Test
    fun connectAndIncomingShareOneLinkPerPeer() = withTransports { _, a, _ ->
        val one = a.connect(room, bob)
        val two = a.connect(room, bob)
        assertSame(one, two)
    }

    @Test
    fun tooLargeMessageIsRejected() = withTransports { _, a, _ ->
        val link = a.connect(room, bob)
        assertFailsWith<IllegalArgumentException> { link.send(ByteArray(WebSocketTransport.MAX_FRAME)) }
    }

    @Test
    fun reconnectsAfterTheRelayDropsTheSocket() = withTransports { relay, a, b ->
        val received = Channel<ByteArray>(Channel.UNLIMITED)
        val collector = launch {
            b.incoming(room).collect { link -> launch { link.received.collect { received.send(it) } } }
        }
        a.status(room).first { it == RelayStatus.OPEN }
        b.status(room).first { it == RelayStatus.OPEN }

        relay.dropAll()

        // both come back by themselves; the relay keeps no queue, so keep sending until one gets through
        var got: ByteArray? = null
        withTimeout(8_000) {
            while (got == null) {
                runCatching { a.connect(room, bob).send(byteArrayOf(7)) }
                got = withTimeoutOrNull(150) { received.receive() }
            }
        }
        assertContentEquals(byteArrayOf(7), got)
        collector.cancel()
    }

    @Test
    fun unreachableRelayFailsWithinTheTimeout() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val t = WebSocketTransport(aliceId, "ws://127.0.0.1:1/ws", scope, backoffMillis = listOf(50), openTimeoutMillis = 500)
        try {
            assertFailsWith<IOException> { t.connect(room, bob) }
        } finally {
            t.close()
            scope.cancel()
        }
    }

    // ---- M12: signing in to the relay (docs/CRYPTO.md section 7a) --------------------------------------------------------------------

    @Test
    fun theTransportSignsTheChallengeWithTheMembersKeyForThisRoomAndIsOpenOnlyAfterReady() = withTransports { relay, a, _ ->
        a.status(room).first { it == RelayStatus.OPEN }
        val (answer, nonce, ok) = relay.answers.first()
        assertTrue(ok, "the relay's own check of the answer")
        assertEquals(65, answer.size)
        assertEquals(2, answer[0].toInt())
        assertTrue(RelayAuth.verify(alice, room.id, nonce, answer.copyOfRange(1, 65)))
        assertFalse(RelayAuth.verify(alice, ByteArray(16) { 9 }, nonce, answer.copyOfRange(1, 65)), "good for this room only")
        assertFalse(RelayAuth.verify(bob, room.id, nonce, answer.copyOfRange(1, 65)), "and for this key only")
    }

    @Test
    fun everyReconnectionSignsInAgainWithAFreshChallenge() = withTransports { relay, a, _ ->
        a.status(room).first { it == RelayStatus.OPEN }
        relay.dropAll()
        withTimeout(8_000) { while (relay.answers.size < 2) delay(50) }
        val nonces = relay.answers.map { it.second.toList() }
        assertEquals(nonces.size, nonces.toSet().size, "a new nonce each time")
        assertTrue(relay.answers.all { it.third })
    }

    @Test
    fun aRelayThatRefusesTheKeyLeavesTheTransportNotOpenAndSaysWhy() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeRelay("refuse").use { relay ->
            val t = WebSocketTransport(aliceId, relay.url(), scope, backoffMillis = listOf(50), openTimeoutMillis = 800)
            try {
                assertFailsWith<IOException> { t.connect(room, bob) }
                assertNotEquals(RelayStatus.OPEN, t.status(room).value)
                assertTrue(t.lastError(room).orEmpty().contains("4401"), "the last error says the relay refused the key: ${t.lastError(room)}")
            } finally {
                t.close(); scope.cancel()
            }
        }
    }

    @Test
    fun aRelayThatNeverSaysReadyIsNotTrustedAsOpen() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeRelay("silent").use { relay ->
            val t = WebSocketTransport(aliceId, relay.url(), scope, backoffMillis = listOf(50), openTimeoutMillis = 600)
            try {
                assertFailsWith<IOException> { t.connect(room, bob) }
                assertNotEquals(RelayStatus.OPEN, t.status(room).value)
                assertFailsWith<IOException> { t.broadcast(room, byteArrayOf(1)) }
            } finally {
                t.close(); scope.cancel()
            }
        }
    }

    @Test
    fun aFrameFromTheRelayBeforeReadyIsNotTakenForAMessage() = withTransports { relay, a, b ->
        // The challenge has the shape of a forwarded frame ([32 bytes sender][payload]) but arrives before ready: it must not become a link.
        a.status(room); b.status(room) // each transport connects when its room is first used
        withTimeout(5_000) { while (relay.challenges.get() < 2) delay(20) }
        b.status(room).first { it == RelayStatus.OPEN }
        val link = withTimeoutOrNull(300) { b.incoming(room).first() }
        assertEquals(null, link, "no link appeared out of the sign-in frames")
    }

    @Test
    fun anUnexpectedFrameBeforeReadyIsNeverTakenForAMessageAndEndsThatConnection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeRelay("early").use { relay ->
            val t = WebSocketTransport(aliceId, relay.url(), scope, backoffMillis = listOf(50), openTimeoutMillis = 600)
            try {
                val links = t.incoming(room)
                assertEquals(null, withTimeoutOrNull(700) { links.first() }, "no link out of a frame that came before ready")
                assertTrue(t.lastError(room).orEmpty().contains("unexpected"), "and the transport says what it saw: ${t.lastError(room)}")
            } finally {
                t.close(); scope.cancel()
            }
        }
    }
}
