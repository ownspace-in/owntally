package owntally.sync

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

enum class RelayStatus { CONNECTING, OPEN, RECONNECTING, CLOSED }

/**
 * Transport over the WebSocket relay (relay/ws-relay). One socket per group room.
 * The relay does not report who is connected, so connect() succeeds as soon as the relay socket is open;
 * whether the peer is there is learned when it answers.
 */
class WebSocketTransport(
    private val self: PublicKey,
    private val relayUrl: String,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = OkHttpClient(),
    private val backoffMillis: List<Long> = listOf(1_000, 5_000, 30_000),
    private val openTimeoutMillis: Long = 10_000,
) : Transport {
    private val rooms = ConcurrentHashMap<GroupRoom, RoomConnection>()

    override suspend fun connect(room: GroupRoom, peer: PublicKey): Link {
        val conn = connection(room)
        conn.awaitOpen()
        return conn.linkTo(peer)
    }

    override fun incoming(room: GroupRoom): Flow<Link> = connection(room).incoming

    /** Sends to every other member in the room. */
    suspend fun broadcast(room: GroupRoom, bytes: ByteArray) {
        val conn = connection(room)
        conn.awaitOpen()
        conn.sendFrame(byteArrayOf(MODE_BROADCAST) + bytes)
    }

    fun status(room: GroupRoom): StateFlow<RelayStatus> = connection(room).status

    /** The last connection error for this room, for showing to the user. */
    fun lastError(room: GroupRoom): String? = connection(room).lastError

    fun close() {
        rooms.values.forEach { it.shutdown() }
        rooms.clear()
    }

    private fun connection(room: GroupRoom) = rooms.computeIfAbsent(room) { RoomConnection(room) }

    private inner class RoomConnection(private val room: GroupRoom) {
        private val links = ConcurrentHashMap<PublicKey, RelayLink>()
        private val inbound = Channel<Link>(Channel.UNLIMITED)
        val incoming: Flow<Link> = inbound.receiveAsFlow()

        private val _status = MutableStateFlow(RelayStatus.CONNECTING)
        val status = _status.asStateFlow()

        @Volatile var lastError: String? = null

        @Volatile private var socket: WebSocket? = null
        @Volatile private var closed = false
        private var attempt = 0

        private fun open() {
            val request = Request.Builder().url("$relayUrl?room=$room&id=$self").build()
            socket = client.newWebSocket(request, listener)
        }

        private val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                _status.value = RelayStatus.OPEN
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = dispatch(bytes.toByteArray())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                dropped(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lastError = listOfNotNull(response?.let { "HTTP ${it.code}" }, t.message).joinToString(": ")
                dropped(webSocket)
            }
        }

        init {
            open()
        }

        private fun dropped(ws: WebSocket) {
            if (closed || ws !== socket) return
            socket = null
            _status.value = RelayStatus.RECONNECTING
            val wait = backoffMillis[minOf(attempt, backoffMillis.lastIndex)]
            attempt++
            scope.launch {
                delay(wait)
                if (!closed) open()
            }
        }

        private fun dispatch(frame: ByteArray) {
            if (frame.size < PublicKey.SIZE) return
            val sender = PublicKey(frame.copyOfRange(0, PublicKey.SIZE))
            var created = false
            val link = links.computeIfAbsent(sender) {
                created = true
                RelayLink(sender, this)
            }
            if (created) inbound.trySend(link)
            link.deliver(frame.copyOfRange(PublicKey.SIZE, frame.size))
        }

        suspend fun awaitOpen() {
            try {
                withTimeout(openTimeoutMillis) { status.first { it == RelayStatus.OPEN } }
            } catch (e: TimeoutCancellationException) {
                throw IOException("Cannot reach the relay at $relayUrl${lastError?.let { " ($it)" } ?: ""}", e)
            }
        }

        fun linkTo(peer: PublicKey): Link = links.computeIfAbsent(peer) { RelayLink(peer, this) }

        fun sendFrame(frame: ByteArray) {
            require(frame.size <= MAX_FRAME) { "frame is ${frame.size} bytes, the relay allows $MAX_FRAME" }
            val ws = socket
            if (ws == null || _status.value != RelayStatus.OPEN || !ws.send(frame.toByteString())) {
                throw IOException("Not connected to the relay")
            }
        }

        fun forget(peer: PublicKey) {
            links.remove(peer)
        }

        fun shutdown() {
            closed = true
            socket?.close(1000, "closing")
            socket = null
            _status.value = RelayStatus.CLOSED
            inbound.close()
            links.values.forEach { it.end() }
        }
    }

    private class RelayLink(override val peer: PublicKey, private val conn: RoomConnection) : Link {
        private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        override val kind = LinkKind.RELAYED
        override val received: Flow<ByteArray> = inbox.receiveAsFlow()

        override suspend fun send(bytes: ByteArray) =
            conn.sendFrame(byteArrayOf(MODE_TARGETED) + peer.bytes + bytes)

        fun deliver(payload: ByteArray) {
            inbox.trySend(payload)
        }

        fun end() {
            inbox.close()
        }

        override suspend fun close() {
            conn.forget(peer)
            inbox.close()
        }
    }

    companion object {
        const val MAX_FRAME = 64 * 1024
        private const val MODE_BROADCAST: Byte = 0
        private const val MODE_TARGETED: Byte = 1
    }
}
