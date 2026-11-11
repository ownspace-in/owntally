package owntally.sync

import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import owntally.model.PublicKey

/** One QUIC connection to a member, with one bidirectional stream for the link (what the native Iroh library gives, boiled down). */
interface IrohConnection {
    /** The key the other end proved in the handshake (Iroh's endpoint id): authenticated, not claimed. */
    val remote: PublicKey

    /** True while the selected path is a direct one; false while it goes through a relay. It can change during the link. */
    val isDirect: Boolean

    suspend fun write(bytes: ByteArray)

    /** Exactly [n] bytes from the stream, or throws [IOException] if it ends first. */
    suspend fun readExact(n: Int): ByteArray
    suspend fun close()
}

/**
 * A connection that has been accepted but whose other end has not yet opened its stream (finding F7, docs/SECURITY_REVIEW.md). Anyone who knows
 * a member's key can get this far and then say nothing, so waiting for the stream is the transport's job, one connection at a time on its own and
 * with a time limit, never in the loop that accepts the next.
 */
interface IncomingIroh {
    /** Completes the handshake and waits for the stream the other end opens. Throws if it fails. */
    suspend fun open(): IrohConnection

    /** Drops the connection: it was too slow, or there were too many waiting. */
    fun abandon()
}

/** What the transport needs from Iroh (the real one is `IrohPortImpl` in the app; the native library cannot run on the JVM tests). */
interface IrohPort {
    /** Binds the endpoint with the member's key as its identity (decision I2) and our relay as its home relay. Returns the endpoint id. */
    suspend fun start(): PublicKey

    /** Dials a member by its key. [relayHint] is the relay to try first. Throws if it cannot connect within the library's own attempt. */
    suspend fun connect(peer: PublicKey, relayHint: String?): IrohConnection
    fun incoming(): Flow<IncomingIroh>
    suspend fun stop()
}

class IrohConfig(
    /** How long to keep trying to dial: the other phone's address may not be published yet when we first ask (found in M0). */
    val connectWindowMs: Long = 15_000,
    val retryMs: Long = 2_000,
    val relayHint: String? = null,
    /** How long an incoming connection may take to open its stream before it is dropped (F7). */
    val acceptWindowMs: Long = 10_000,
    /** How many incoming connections may be waiting for their stream at once; more are dropped at once (F7). */
    val maxPendingAccepts: Int = 16,
)

/**
 * The internet link over Iroh (M7, T7.4): direct between the two phones when hole punching works, through the relay when not, and Iroh
 * carries it in QUIC with the other end's key proved by its handshake. With the member's own key as Iroh's identity (decision I2) the
 * authenticated remote id **is** the member key, so nothing more is proved here: a link says [IrohConnection.remote] and nothing else. One
 * QUIC connection per peer and one stream per link, messages as a 4-byte length and the bytes; an empty message opens the stream for the
 * other side (it only sees a stream once something is written).
 */
class IrohTransport(
    private val port: IrohPort,
    private val scope: CoroutineScope,
    private val config: IrohConfig = IrohConfig(),
) : Transport {
    private val started = CompletableDeferred<PublicKey>()
    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)
    private val inbox = Channel<Link>(LinkQueue.INCOMING)
    private val pendingAccepts = java.util.concurrent.Semaphore(config.maxPendingAccepts)

    private fun ensureStarted() {
        if (!starting.compareAndSet(false, true)) return
        scope.launch {
            try {
                started.complete(port.start())
            } catch (e: Exception) {
                started.completeExceptionally(e)
                return@launch
            }
            port.incoming().collect { incoming ->
                if (!pendingAccepts.tryAcquire()) { incoming.abandon(); return@collect }
                // Each one waits for its stream on its own, so a silent stranger holds up nobody (F7).
                scope.launch {
                    try {
                        val conn = try {
                            withTimeout(config.acceptWindowMs) { incoming.open() }
                        } catch (e: TimeoutCancellationException) {
                            null
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null // one that fails to complete must not stop the others being accepted
                        }
                        if (conn == null) incoming.abandon() else if (inbox.trySend(IrohLink(conn)).isFailure) runCatching { conn.close() } // nobody is taking them: close, do not queue (F8)
                    } finally {
                        pendingAccepts.release()
                    }
                }
            }
        }
    }

    override fun incoming(room: GroupRoom): Flow<Link> {
        ensureStarted()
        return inbox.receiveAsFlow()
    }

    override suspend fun connect(room: GroupRoom, peer: PublicKey): Link {
        ensureStarted()
        val own = try { started.await() } catch (e: Exception) { throw PeerUnreachable(peer, e) }
        if (peer == own) throw PeerUnreachable(peer)
        var last: Exception? = null
        val deadline = System.nanoTime() + config.connectWindowMs * 1_000_000
        while (true) {
            try {
                val conn = withTimeout(maxOf(1L, (deadline - System.nanoTime()) / 1_000_000)) { port.connect(peer, config.relayHint) }
                if (conn.remote != peer) { runCatching { conn.close() }; throw LinkAuthFailed("the other phone does not hold the key it should") }
                val link = IrohLink(conn)
                link.send(ByteArray(0)) // opens the stream for the other side
                return link
            } catch (e: LinkAuthFailed) {
                throw e
            } catch (e: TimeoutCancellationException) {
                throw PeerUnreachable(peer, last ?: e)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (System.nanoTime() + config.retryMs * 1_000_000 > deadline) throw PeerUnreachable(peer, e)
                delay(config.retryMs)
            }
        }
    }

    suspend fun stop() = port.stop()

    private class IrohLink(private val conn: IrohConnection) : Link {
        override val peer: PublicKey = conn.remote
        override val kind: LinkKind get() = if (conn.isDirect) LinkKind.DIRECT else LinkKind.RELAYED
        override val security: LinkSecurity = LinkSecurity.TLS // QUIC, with TLS 1.3 inside
        private val writeLock = Mutex()

        override suspend fun send(bytes: ByteArray) {
            if (bytes.size > Frames.MAX) throw Frames.Bad("frame too large")
            // Iroh's calls finish on a thread of its own: never let a caller's dispatcher resume there.
            writeLock.withLock { withContext(Dispatchers.IO) { conn.write(ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).array()) } }
        }

        override val received: Flow<ByteArray> = flow {
            while (true) {
                val length = try { ByteBuffer.wrap(conn.readExact(4)).int } catch (e: IOException) { break }
                if (length < 0 || length > Frames.MAX) break // not a frame we would ever send: the link ends rather than allocate it
                if (length == 0) continue
                emit(try { conn.readExact(length) } catch (e: IOException) { break })
            }
        }.flowOn(Dispatchers.IO)

        override suspend fun close() { runCatching { conn.close() } }
    }
}
