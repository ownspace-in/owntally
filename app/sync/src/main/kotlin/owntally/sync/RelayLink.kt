package owntally.sync

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import owntally.crypto.GroupIdentity
import owntally.model.PublicKey

/**
 * Keeps a sender under a rate: the relay closes a connection that sends more than 50 messages a second after a burst of 100
 * (relay/ws-relay/README.md). [nanos] and [sleep] are the clock and the wait, so tests can run it on a pretend clock.
 */
internal class FramePacer(
    private val maxPerSecond: Int = 40,
    private val burst: Int = 20,
    private val nanos: () -> Long = System::nanoTime,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private var tokens = burst.toDouble()
    private var last = nanos()

    suspend fun acquire() {
        while (true) {
            val wait = synchronized(this) {
                val now = nanos()
                tokens = minOf(burst.toDouble(), tokens + (now - last) / 1e9 * maxPerSecond)
                last = now
                if (tokens >= 1.0) { tokens -= 1.0; 0L } else ((1.0 - tokens) / maxPerSecond * 1000).toLong().coerceAtLeast(1)
            }
            if (wait == 0L) return
            sleep(wait)
        }
    }
}

/** One logical byte stream between two phones, carried in the frames of a [StreamMux]. */
internal class LogicalStream(val id: Int, private val mux: StreamMux) {
    val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile var closed = false

    suspend fun write(bytes: ByteArray) {
        if (closed) throw IOException("the stream is closed")
        mux.sendData(id, bytes)
    }

    suspend fun close() {
        if (closed) return
        closed = true
        runCatching { mux.sendClose(id) }
        inbox.close()
        mux.forget(id)
    }

    fun remoteEnded() { closed = true; inbox.close(); mux.forget(id) }
}

/**
 * Many streams over one path to a peer (the relay forwards frames to a key and says nothing of who else is on it): a frame is `[stream id u32]
 * [flags u8: 0 data, 1 end][bytes]`. A frame for a stream id not seen before opens a stream for the answering side ([onNew]). The mux reads
 * the carrier's messages itself; nothing else may.
 */
internal class StreamMux(
    private val carrier: Link,
    scope: CoroutineScope,
    private val maxFrame: Int,
    private val pacer: FramePacer,
    private val random: java.security.SecureRandom,
    private val onNew: (LogicalStream) -> Unit,
) {
    private val streams = ConcurrentHashMap<Int, LogicalStream>()

    init {
        scope.launch {
            try {
                carrier.received.collect { frame -> receive(frame) }
            } finally {
                streams.values.toList().forEach { it.remoteEnded() }
            }
        }
    }

    private fun receive(frame: ByteArray) {
        if (frame.size < HEADER) return
        val b = ByteBuffer.wrap(frame)
        val id = b.int
        val flags = b.get().toInt()
        val data = frame.copyOfRange(HEADER, frame.size)
        val existing = streams[id]
        if (flags == END) { existing?.remoteEnded(); return }
        if (flags != DATA) return
        val stream = existing ?: LogicalStream(id, this).also { s ->
            if (streams.putIfAbsent(id, s) != null) return receive(frame)
            onNew(s)
        }
        stream.inbox.trySend(data)
    }

    fun open(): LogicalStream {
        while (true) {
            val id = random.nextInt()
            val s = LogicalStream(id, this)
            if (streams.putIfAbsent(id, s) == null) return s
        }
    }

    suspend fun sendData(id: Int, bytes: ByteArray) {
        var from = 0
        do {
            val to = minOf(bytes.size, from + maxFrame - HEADER)
            pacer.acquire()
            carrier.send(ByteBuffer.allocate(HEADER + to - from).putInt(id).put(DATA.toByte()).put(bytes, from, to - from).array())
            from = to
        } while (from < bytes.size)
    }

    suspend fun sendClose(id: Int) {
        pacer.acquire()
        carrier.send(ByteBuffer.allocate(HEADER).putInt(id).put(END.toByte()).array())
    }

    fun forget(id: Int) { streams.remove(id) }

    companion object {
        const val HEADER = 5
        const val DATA = 0
        const val END = 1
    }
}

/** A socket that is really a [LogicalStream], so the same TLS code that runs over a network socket runs through the relay. */
internal class StreamSocket(private val stream: LogicalStream) : Socket() {
    @Volatile private var timeoutMs = 0
    @Volatile private var shut = false
    private var pending: ByteArray? = null
    private var pos = 0

    private val input = object : InputStream() {
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pending == null || pos >= pending!!.size) {
                val next = runBlocking {
                    if (timeoutMs > 0) withTimeoutOrNull(timeoutMs.toLong()) { stream.inbox.receiveCatching() } ?: throw SocketTimeoutException("read timed out")
                    else stream.inbox.receiveCatching()
                }.getOrNull() ?: return -1
                pending = next; pos = 0
            }
            val n = minOf(len, pending!!.size - pos)
            System.arraycopy(pending!!, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = pending?.let { it.size - pos } ?: 0
    }

    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (shut || stream.closed) throw SocketException("the stream is closed")
            try {
                runBlocking { stream.write(b.copyOfRange(off, off + len)) }
            } catch (e: IOException) {
                throw SocketException(e.message)
            }
        }
    }

    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun isConnected(): Boolean = true
    override fun isClosed(): Boolean = stream.closed
    override fun isBound(): Boolean = true
    override fun isInputShutdown(): Boolean = stream.closed
    override fun isOutputShutdown(): Boolean = shut || stream.closed
    override fun setSoTimeout(timeout: Int) { timeoutMs = timeout }
    override fun getSoTimeout(): Int = timeoutMs
    override fun setTcpNoDelay(on: Boolean) = Unit
    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getPort(): Int = 0
    override fun shutdownInput() = Unit
    override fun shutdownOutput() { shut = true }
    override fun close() { runBlocking { stream.close() } }
}

/**
 * Authenticated links through a relay, or any carrier that forwards frames to a key and proves nothing about who sends (T7.3). The relay
 * operator terminates the TLS to the relay, so it could read the sync messages' summaries and change or insert any of them (a changed closing
 * summary could even pass for the proof that a leave arrived, D4). So every link runs **TLS 1.3 end to end between the two members**, with the
 * same member-bound certificates as the local link ([TlsLinks]): the carrier sees only TLS records and cannot alter them undetected. There is
 * no plain link over a carrier. Of the carrier we use only `connect` and `incoming`; the sender it names is checked against the key in the
 * certificate, and a link is handed on only if they agree.
 */
class SecureOverCarrier(
    private val carrier: Transport,
    private val identity: GroupIdentity,
    private val scope: CoroutineScope,
    /** The biggest message the carrier takes (the relay: 64 KB with its own header). */
    private val maxFrame: Int = 60_000,
    private val handshakeTimeoutMs: Int = 10_000,
    private val random: java.security.SecureRandom = java.security.SecureRandom(),
    /** The most frames a second sent to the carrier (the relay closes a connection above 50 after a burst of 100). */
    maxFramesPerSecond: Int = 40,
) : Transport {
    private val own = TlsCertificates.issue(identity, random)
    private val pacer = FramePacer(maxFramesPerSecond)
    private val muxes = ConcurrentHashMap<Pair<GroupRoom, Link>, StreamMux>()
    private val inboxes = ConcurrentHashMap<GroupRoom, Channel<Link>>()
    private val listening = ConcurrentHashMap.newKeySet<GroupRoom>()

    private fun muxOf(room: GroupRoom, raw: Link): StreamMux = muxes.computeIfAbsent(room to raw) {
        StreamMux(raw, scope, maxFrame, pacer, random) { stream -> accept(room, raw, stream) }
    }

    /** Someone opened a stream to us: answer its TLS handshake and, if the member it proves is the sender the carrier named, hand it on. */
    private fun accept(room: GroupRoom, raw: Link, stream: LogicalStream) {
        val inbox = inboxes[room] ?: return stream.remoteEnded()
        scope.launch(Dispatchers.IO) {
            val socket = StreamSocket(stream)
            val answered = TlsLinks.server(socket, own, handshakeTimeoutMs, random)
            if (answered == null || answered.second != raw.peer) { answered?.first?.close(); return@launch }
            val link = SocketLink(answered.first, answered.second, LinkKind.RELAYED, scope, security = LinkSecurity.TLS)
            if (inbox.trySend(link).isFailure) answered.first.close()
        }
    }

    override fun incoming(room: GroupRoom): Flow<Link> {
        val inbox = inboxes.computeIfAbsent(room) { Channel(Channel.UNLIMITED) }
        // The carrier's flow is asked for here, not in the launched job: some carriers (the pretend one in tests) start answering only once asked.
        if (listening.add(room)) { val raws = carrier.incoming(room); scope.launch { raws.collect { raw -> muxOf(room, raw) } } }
        return inbox.receiveAsFlow()
    }

    override suspend fun connect(room: GroupRoom, peer: PublicKey): Link {
        val raw = carrier.connect(room, peer)
        val stream = muxOf(room, raw).open()
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val ssl = try {
                TlsLinks.client(StreamSocket(stream), peer.toHex(), 0, own, peer, handshakeTimeoutMs, random)
            } catch (e: Exception) {
                runCatching { stream.close() }
                throw e
            }
            SocketLink(ssl, peer, LinkKind.RELAYED, scope, security = LinkSecurity.TLS) as Link
        }
    }
}
