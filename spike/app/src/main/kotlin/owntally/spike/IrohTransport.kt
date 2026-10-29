package owntally.spike

import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import computer.iroh.SecretKey
import computer.iroh.presetN0
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import owntally.sync.GroupRoom
import owntally.sync.Link
import owntally.sync.LinkKind
import owntally.sync.PublicKey
import owntally.sync.Transport

/**
 * Spike only (T0.6). Transport over Iroh. One QUIC connection per peer, one bidirectional stream per link,
 * messages framed as a 4-byte length plus payload. The room is ignored: every link shares one ALPN.
 *
 * [relayUrl] null uses the n0 preset (Iroh's public relays and discovery). A URL replaces the relay list
 * with our own relay, and also tells the dialer which relay to try first.
 */
class IrohTransport(
    private val secret: ByteArray,
    private val relayUrl: String?,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) : Transport {
    private var endpoint: Endpoint? = null
    private val ready = CompletableDeferred<Endpoint>()
    private val incomingLinks = Channel<Link>(Channel.UNLIMITED)

    val publicKey: PublicKey get() = publicKeyOf(secret)

    suspend fun start() {
        val options = EndpointOptions(
            preset = presetN0(),
            secretKey = secret,
            alpns = listOf(ALPN),
            relayMode = relayUrl?.let { RelayMode.customFromUrls(listOf(it)) },
        )
        val ep = Endpoint.bind(options)
        endpoint = ep
        log("iroh: endpoint ${ep.id()} bound; waiting to be online")
        ep.online()
        log("iroh: online, home relay ${ep.addr().relayUrl()}, ${ep.addr().directAddresses().size} direct addresses")
        ready.complete(ep)
        scope.launch {
            while (true) {
                val incoming = ep.acceptNext() ?: break
                launch {
                    try {
                        val conn = incoming.accept().connect()
                        val bi = conn.acceptBi()
                        incomingLinks.send(IrohLink(conn, bi, conn.remoteId()))
                    } catch (e: Exception) {
                        log("iroh: incoming failed: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
        }
    }

    override suspend fun connect(room: GroupRoom, peer: PublicKey): Link {
        val ep = ready.await()
        val addr = EndpointAddr(EndpointId.fromBytes(peer.bytes), relayUrl, emptyList())
        // The peer's address may not be published yet when we first dial, so retry for a while.
        var conn: Connection? = null
        var lastError = ""
        val deadline = System.nanoTime() + CONNECT_WINDOW_NANOS
        while (conn == null) {
            try {
                conn = ep.connect(addr, ALPN)
            } catch (e: computer.iroh.IrohException) {
                lastError = "${e.kind()} ${e.debugMessage()}"
                log("iroh: dial failed ($lastError), retrying")
                if (System.nanoTime() > deadline) throw java.io.IOException("iroh connect failed: $lastError")
                kotlinx.coroutines.delay(2_000)
            }
        }
        val bi = conn.openBi()
        val link = IrohLink(conn, bi, EndpointId.fromBytes(peer.bytes))
        // The acceptor only sees a stream once something is written to it.
        link.sendFrame(ByteArray(0))
        return link
    }

    override fun incoming(room: GroupRoom): Flow<Link> = incomingLinks.receiveAsFlow()

    suspend fun stop() {
        endpoint?.shutdown()
        endpoint = null
    }

    private inner class IrohLink(
        private val conn: Connection,
        private val bi: BiStream,
        remote: EndpointId,
    ) : Link {
        override val peer = PublicKey(remote.toBytes())
        override val kind: LinkKind
            get() {
                val selected = conn.paths().firstOrNull { it.isSelected }
                return if (selected?.isRelay == false) LinkKind.DIRECT else LinkKind.RELAYED
            }

        override suspend fun send(bytes: ByteArray) = sendFrame(bytes)

        private val sendHalf = bi.send()
        private val recvHalf = bi.recv()

        // Iroh's async calls finish on a Rust thread. Never let a caller's Unconfined dispatcher resume on it.
        suspend fun sendFrame(bytes: ByteArray) = withContext(Dispatchers.IO) {
            log("iroh: writing ${bytes.size} bytes")
            sendHalf.writeAll(ByteBuffer.allocate(4).putInt(bytes.size).array() + bytes)
            log("iroh: wrote ${bytes.size} bytes")
        }

        override val received: Flow<ByteArray> = flow {
            val recv = recvHalf
            while (true) {
                val len = ByteBuffer.wrap(recv.readExact(4u)).int
                if (len == 0) continue
                emit(recv.readExact(len.toUInt()))
            }
        }.flowOn(Dispatchers.IO)

        override suspend fun close() {
            conn.close(0, ByteArray(0))
        }
    }

    companion object {
        val ALPN = "owntally/spike/0".toByteArray()
        private const val CONNECT_WINDOW_NANOS = 30_000_000_000L

        fun publicKeyOf(secret: ByteArray) = PublicKey(SecretKey.fromBytes(secret).public().toBytes())
    }
}
