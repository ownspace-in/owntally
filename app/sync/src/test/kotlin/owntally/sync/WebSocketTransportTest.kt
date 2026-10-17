package owntally.sync

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

/** A small stand-in for relay/ws-relay that follows the wire protocol in its README. */
private class FakeRelay : AutoCloseable {
    val server = MockWebServer()
    private val rooms = ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocket>>()
    val framesSeen = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val room = request.requestUrl!!.queryParameter("room")!!
                val id = request.requestUrl!!.queryParameter("id")!!
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        rooms.getOrPut(room) { ConcurrentHashMap() }[id] = webSocket
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val frame = bytes.toByteArray()
                        framesSeen += frame
                        val members = rooms[room] ?: return
                        val sender = PublicKey.fromHex(id).bytes
                        when (frame[0].toInt()) {
                            0 -> members.filterKeys { it != id }.values.forEach {
                                it.send((sender + frame.copyOfRange(1, frame.size)).toByteString())
                            }
                            1 -> members[PublicKey(frame.copyOfRange(1, 33)).toString()]
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
    private val alice = PublicKey(ByteArray(32) { 1 })
    private val bob = PublicKey(ByteArray(32) { 2 })

    private fun withTransports(block: suspend CoroutineScope.(FakeRelay, WebSocketTransport, WebSocketTransport) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            FakeRelay().use { relay ->
                val a = WebSocketTransport(alice, relay.url(), scope, backoffMillis = listOf(50), openTimeoutMillis = 3_000)
                val b = WebSocketTransport(bob, relay.url(), scope, backoffMillis = listOf(50), openTimeoutMillis = 3_000)
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
        assertContentEquals(bob.bytes, targeted.copyOfRange(1, 33))
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
        val t = WebSocketTransport(alice, "ws://127.0.0.1:1/ws", scope, backoffMillis = listOf(50), openTimeoutMillis = 500)
        try {
            assertFailsWith<IOException> { t.connect(room, bob) }
        } finally {
            t.close()
            scope.cancel()
        }
    }
}
