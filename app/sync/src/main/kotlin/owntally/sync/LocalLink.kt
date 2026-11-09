package owntally.sync

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import owntally.crypto.GroupIdentity
import owntally.crypto.LinkAuth
import owntally.model.PublicKey

/** Messages on a local link travel as frames: a four-byte length, then the bytes. A frame over [MAX] is an error, never read. */
object Frames {
    const val MAX = 1 shl 20

    class Bad(message: String) : IOException(message)

    fun write(out: OutputStream, bytes: ByteArray) {
        if (bytes.size > MAX) throw Bad("frame too large")
        out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
        out.write(bytes)
        out.flush()
    }

    /** The next frame, or null if the stream ended cleanly between frames. Throws [Bad] for an over-long or cut-off frame. */
    fun read(input: InputStream): ByteArray? {
        val head = ByteArray(4)
        var got = 0
        while (got < 4) {
            val n = input.read(head, got, 4 - got)
            if (n < 0) { if (got == 0) return null else throw Bad("cut off inside a frame header") }
            got += n
        }
        val length = ByteBuffer.wrap(head).int
        if (length < 0 || length > MAX) throw Bad("frame too large")
        val body = ByteArray(length)
        try {
            DataInputStream(input).readFully(body)
        } catch (e: java.io.EOFException) {
            throw Bad("cut off inside a frame")
        }
        return body
    }
}

/** How a phone on the same network finds another (decision L1). Names are opaque: see [LocalNames]. */
interface LocalDiscovery {
    /** Makes this phone findable as (room, key) for links of kind [security] at [port] until the handle is closed. */
    fun announce(room: GroupRoom, self: PublicKey, port: Int, security: LinkSecurity = LinkSecurity.PLAIN): AutoCloseable

    /** Where the phone (room, peer) takes links of kind [security] on this network, or null if it was not found within [timeoutMs]. */
    suspend fun find(room: GroupRoom, peer: PublicKey, timeoutMs: Long, security: LinkSecurity = LinkSecurity.PLAIN): InetSocketAddress?
}

object LocalNames {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    /**
     * The name a phone is announced under: 20 letters and digits made from the room and the key. Someone who knows both can work it out and
     * look for it; to anyone else on the network it says nothing about the group or the person.
     */
    fun serviceName(room: GroupRoom, key: PublicKey, security: LinkSecurity = LinkSecurity.PLAIN): String {
        val label = if (security == LinkSecurity.PLAIN) "owntally/v1/local" else "owntally/v1/local-tls"
        val digest = MessageDigest.getInstance("SHA-256").digest(label.toByteArray() + room.id + key.bytes())
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in digest.copyOf(13)) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5 && sb.length < 20) { sb.append(ALPHABET[(buffer shr (bits - 5)) and 31]); bits -= 5 }
        }
        return sb.toString()
    }
}

/** For tests: phones that find each other through a shared table. */
class InMemoryLocalDiscovery : LocalDiscovery {
    private val table = ConcurrentHashMap<String, InetSocketAddress>()

    override fun announce(room: GroupRoom, self: PublicKey, port: Int, security: LinkSecurity): AutoCloseable {
        val name = LocalNames.serviceName(room, self, security)
        table[name] = InetSocketAddress(InetAddress.getLoopbackAddress(), port)
        return AutoCloseable { table.remove(name) }
    }

    override suspend fun find(room: GroupRoom, peer: PublicKey, timeoutMs: Long, security: LinkSecurity): InetSocketAddress? = table[LocalNames.serviceName(room, peer, security)]

    /** Points a name at another address (a phone that answers where another should be). */
    fun point(room: GroupRoom, key: PublicKey, to: InetSocketAddress, security: LinkSecurity = LinkSecurity.PLAIN) { table[LocalNames.serviceName(room, key, security)] = to }
}

/** The other phone could not prove it holds the key it connected as (or the key we expected). */
class LinkAuthFailed(message: String) : IOException(message)

/** A [Link] over a socket, after the handshake: frames both ways. The first of reading ends, or any write failure, ends the link. */
internal class SocketLink(
    private val socket: Socket,
    override val peer: PublicKey,
    override val kind: LinkKind,
    scope: CoroutineScope,
    firstInput: InputStream? = null,
    override val security: LinkSecurity? = LinkSecurity.PLAIN,
) : Link {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val writeLock = Mutex()
    private val reader: Job

    init {
        socket.soTimeout = 0
        val input = (firstInput ?: socket.getInputStream()).buffered()
        reader = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) inbox.send(Frames.read(input) ?: break)
            } catch (e: IOException) {
                // the link is gone or sent something that is not a frame: it ends either way
            } finally {
                inbox.close()
                runCatching { socket.close() }
            }
        }
    }

    override suspend fun send(bytes: ByteArray) {
        writeLock.withLock {
            withContext(Dispatchers.IO) { Frames.write(socket.getOutputStream(), bytes) }
        }
    }

    override val received: Flow<ByteArray> = inbox.receiveAsFlow()

    override suspend fun close() {
        withContext(Dispatchers.IO) { runCatching { socket.close() } }
        reader.cancel()
    }
}

/** What the two sides say to each other before a local link carries anything (plain link; T6.7). */
internal object LocalHandshake {
    const val VERSION = 1
    const val KIND_PLAIN = 1
    private val MAGIC = byteArrayOf(0x4F, 0x4C)
    const val HELLO_SIZE = 2 + 1 + 1 + 16 + 32 + 32 + 32
    const val REPLY_SIZE = 2 + 1 + 1 + 32 + 64
    const val PROOF_SIZE = 2 + 1 + 64

    class Hello(val kinds: Int, val room: GroupRoom, val from: PublicKey, val to: PublicKey, val nonce: ByteArray) {
        fun encode(): ByteArray = ByteBuffer.allocate(HELLO_SIZE).put(MAGIC).put(VERSION.toByte()).put(kinds.toByte()).put(room.id).put(from.bytes()).put(to.bytes()).put(nonce).array()

        companion object {
            fun decode(b: ByteArray): Hello? {
                if (b.size != HELLO_SIZE || b[0] != MAGIC[0] || b[1] != MAGIC[1] || b[2].toInt() != VERSION) return null
                val buf = ByteBuffer.wrap(b, 4, b.size - 4)
                fun take(n: Int) = ByteArray(n).also { buf.get(it) }
                return Hello(b[3].toInt() and 0xff, GroupRoom(take(16)), PublicKey(take(32)), PublicKey(take(32)), take(32))
            }
        }
    }

    fun transcript(hello: Hello, chosen: Int, serverNonce: ByteArray): ByteArray = hello.encode() + byteArrayOf(VERSION.toByte(), chosen.toByte()) + serverNonce

    fun encodeReply(chosen: Int, nonce: ByteArray, signature: ByteArray): ByteArray = ByteBuffer.allocate(REPLY_SIZE).put(MAGIC).put(VERSION.toByte()).put(chosen.toByte()).put(nonce).put(signature).array()

    class Reply(val chosen: Int, val nonce: ByteArray, val signature: ByteArray)

    fun decodeReply(b: ByteArray): Reply? {
        if (b.size != REPLY_SIZE || b[0] != MAGIC[0] || b[1] != MAGIC[1] || b[2].toInt() != VERSION) return null
        val buf = ByteBuffer.wrap(b, 4, b.size - 4)
        return Reply(b[3].toInt() and 0xff, ByteArray(32).also { buf.get(it) }, ByteArray(64).also { buf.get(it) })
    }

    fun encodeProof(signature: ByteArray): ByteArray = ByteBuffer.allocate(PROOF_SIZE).put(MAGIC).put(VERSION.toByte()).put(signature).array()

    fun decodeProof(b: ByteArray): ByteArray? {
        if (b.size != PROOF_SIZE || b[0] != MAGIC[0] || b[1] != MAGIC[1] || b[2].toInt() != VERSION) return null
        return b.copyOfRange(3, PROOF_SIZE)
    }
}

/** How a local link is protected on the network (decision L2): the plain link, or TLS 1.3 (T6.8). Noise follows in T6.9. */
enum class LinkSecurity { PLAIN, TLS }

class LocalConfig(
    /** How long each step of the handshake may take (and the connecting itself). */
    val handshakeTimeoutMs: Int = 10_000,
    val findTimeoutMs: Long = 5_000,
    /** How long the TLS attempt may take (finding the phone, connecting, the handshake) before the plain link is tried (decision L6). */
    val tlsAttemptMs: Long = 5_000,
    /** Whether this phone takes TLS links at all. */
    val offerTls: Boolean = true,
    val bind: InetAddress? = null,
)

/**
 * One phone's way of being reached and of reaching others on the same network (decision L1; T6.7). It listens on one port for every group
 * and every key this phone uses, announces each (room, key) through [discovery], and routes an incoming connection by the room and key it
 * names. A connection is handed on only after both sides have shown they hold their keys ([LinkAuth]); what the group allows that key to
 * have is the sync engine's rule, not this layer's. This is the plain link of decision L2: nothing on it is encrypted beyond the envelopes.
 */
class LocalNode(
    private val discovery: LocalDiscovery,
    private val scope: CoroutineScope,
    private val config: LocalConfig = LocalConfig(),
    private val random: java.security.SecureRandom = java.security.SecureRandom(),
) {
    private class Listener(val identity: GroupIdentity, val inbox: Channel<Link>, val announced: List<AutoCloseable>, val tls: TlsCertificates.Issued, val tlsServer: ServerSocket?, val tlsAccepting: Job?)

    private val listeners = ConcurrentHashMap<Pair<GroupRoom, PublicKey>, Listener>()
    private var server: ServerSocket? = null
    private var accepting: Job? = null

    val port: Int get() = server?.localPort ?: -1

    /** The port where this phone takes TLS links for (room, key): one for each, so the certificate to show is known from the port alone. */
    fun tlsPortOf(room: GroupRoom, key: PublicKey): Int = listeners[room to key]?.tlsServer?.localPort ?: -1

    /** Opens the port. Call once, before any [transportFor] is used to listen. */
    @Synchronized
    fun start(): Int {
        server?.let { return it.localPort }
        val s = ServerSocket(0, 50, config.bind)
        server = s
        accepting = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val socket = try { s.accept() } catch (e: IOException) { break }
                launch(Dispatchers.IO) { answer(socket) }
            }
        }
        return s.localPort
    }

    @Synchronized
    fun close() {
        accepting?.cancel()
        runCatching { server?.close() }
        server = null
        listeners.values.forEach { l -> l.announced.forEach { runCatching { it.close() } }; l.tlsAccepting?.cancel(); runCatching { l.tlsServer?.close() }; l.inbox.close() }
        listeners.clear()
    }

    fun transportFor(identity: GroupIdentity, kind: LinkKind = LinkKind.DIRECT, security: LinkSecurity = LinkSecurity.PLAIN): Transport = object : Transport {
        override suspend fun connect(room: GroupRoom, peer: PublicKey): Link = dial(identity, room, peer, kind, security)

        override fun incoming(room: GroupRoom): Flow<Link> {
            val key = room to identity.publicKey
            val l = listeners.computeIfAbsent(key) {
                val plainPort = start()
                // TLS has a port of its own for this identity, so the certificate to show (the one that carries this key) is known without asking
                // the caller anything: choosing it by the name the caller asks for is not something every platform's TLS lets us do.
                val tls = TlsCertificates.issue(identity, random)
                val inbox = Channel<Link>(Channel.UNLIMITED)
                val tlsServer = if (config.offerTls) ServerSocket(0, 50, config.bind) else null
                val accepting = if (tlsServer != null) scope.launch(Dispatchers.IO) {
                    while (isActive) {
                        val socket = try { tlsServer.accept() } catch (e: IOException) { break }
                        launch(Dispatchers.IO) { answerTls(socket, tls, inbox) }
                    }
                } else null
                val handles = listOfNotNull(
                    discovery.announce(room, identity.publicKey, plainPort, LinkSecurity.PLAIN),
                    tlsServer?.let { discovery.announce(room, identity.publicKey, it.localPort, LinkSecurity.TLS) },
                )
                Listener(identity, inbox, handles, tls, tlsServer, accepting)
            }
            return l.inbox.receiveAsFlow()
        }
    }

    /**
     * The way a phone normally reaches another (decision L6): TLS first; if TLS does not connect within [LocalConfig.tlsAttemptMs] (the other
     * phone offers none, is not found that way, refuses, or the handshake fails), the plain link. A phone that answers **with the wrong key**
     * is a refusal, not a failure to connect: no plain attempt follows. The link says which kind it is ([Link.security]).
     */
    fun secureFirstTransportFor(identity: GroupIdentity, kind: LinkKind = LinkKind.DIRECT): Transport = object : Transport {
        private val tls = transportFor(identity, kind, LinkSecurity.TLS)
        private val plain = transportFor(identity, kind, LinkSecurity.PLAIN)

        override suspend fun connect(room: GroupRoom, peer: PublicKey): Link {
            val viaTls = try {
                kotlinx.coroutines.withTimeoutOrNull(config.tlsAttemptMs) { tls.connect(room, peer) }
            } catch (e: PeerUnreachable) {
                null
            }
            return viaTls ?: plain.connect(room, peer)
        }

        override fun incoming(room: GroupRoom): Flow<Link> = plain.incoming(room) // both kinds arrive in the same inbox
    }

    private suspend fun dial(identity: GroupIdentity, room: GroupRoom, peer: PublicKey, kind: LinkKind, security: LinkSecurity): Link {
        val address = discovery.find(room, peer, config.findTimeoutMs, security) ?: throw PeerUnreachable(peer)
        return suspendCancellableCoroutine { cont ->
            val socket = Socket()
            // A blocking connect or handshake cannot be interrupted: closing the socket is what makes it give up when the caller's time is up.
            cont.invokeOnCancellation { runCatching { socket.close() } }
            scope.launch(Dispatchers.IO) {
                val result = runCatching { dialBlocking(socket, address, identity, room, peer, kind, security) }
                if (cont.isActive) cont.resumeWith(result) else result.getOrNull()?.close()
            }
        }
    }

    private fun dialBlocking(socket: Socket, address: InetSocketAddress, identity: GroupIdentity, room: GroupRoom, peer: PublicKey, kind: LinkKind, security: LinkSecurity): Link {
        try {
            socket.connect(address, config.handshakeTimeoutMs)
            socket.soTimeout = config.handshakeTimeoutMs
            socket.tcpNoDelay = true
            if (security == LinkSecurity.TLS) return dialTls(socket, identity, room, peer, kind)
            val out = socket.getOutputStream()
            val input = socket.getInputStream().buffered()
            val hello = LocalHandshake.Hello(LocalHandshake.KIND_PLAIN, room, identity.publicKey, peer, LinkAuth.newNonce(random))
            Frames.write(out, hello.encode())
            val reply = Frames.read(input)?.let { LocalHandshake.decodeReply(it) } ?: throw LinkAuthFailed("no proper answer")
            if (reply.chosen != LocalHandshake.KIND_PLAIN) throw LinkAuthFailed("no kind of link in common")
            val transcript = LocalHandshake.transcript(hello, reply.chosen, reply.nonce)
            if (!LinkAuth.verifyServer(peer, transcript, reply.signature)) throw LinkAuthFailed("the other phone does not hold the key it should")
            Frames.write(out, LocalHandshake.encodeProof(LinkAuth.signAsClient(identity, transcript)))
            return SocketLink(socket, peer, kind, scope, input)
        } catch (e: LinkAuthFailed) {
            runCatching { socket.close() }
            throw e
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw PeerUnreachable(peer, e)
        }
    }

    /** The other side of [dial]. Anything wrong closes the connection without a word. */
    private fun answer(socket: Socket) {
        try {
            socket.soTimeout = config.handshakeTimeoutMs
            socket.tcpNoDelay = true
            val input = socket.getInputStream().buffered()
            val out = socket.getOutputStream()
            val hello = Frames.read(input)?.let { LocalHandshake.Hello.decode(it) } ?: return socket.close()
            val listener = listeners[hello.room to hello.to]
            if (listener == null || hello.kinds and LocalHandshake.KIND_PLAIN == 0) return socket.close()
            val nonce = LinkAuth.newNonce(random)
            val transcript = LocalHandshake.transcript(hello, LocalHandshake.KIND_PLAIN, nonce)
            Frames.write(out, LocalHandshake.encodeReply(LocalHandshake.KIND_PLAIN, nonce, LinkAuth.signAsServer(listener.identity, transcript)))
            val proof = Frames.read(input)?.let { LocalHandshake.decodeProof(it) } ?: return socket.close()
            if (!LinkAuth.verifyClient(hello.from, transcript, proof)) return socket.close()
            val link = SocketLink(socket, hello.from, LinkKind.DIRECT, scope, input) // the stream the handshake read through: it may hold bytes already
            if (listener.inbox.trySend(link).isFailure) socket.close()
        } catch (e: IOException) {
            runCatching { socket.close() }
        }
    }

    // ---- TLS (T6.8) ------------------------------------------------------------------------------------------------------------------

    private fun dialTls(socket: Socket, identity: GroupIdentity, room: GroupRoom, peer: PublicKey, kind: LinkKind): Link {
        val own = TlsCertificates.issue(identity, random)
        val ssl = TlsLinks.client(socket, socket.inetAddress.hostAddress, socket.port, own, peer, config.handshakeTimeoutMs, random)
        return SocketLink(ssl, peer, kind, scope, security = LinkSecurity.TLS)
    }

    private fun answerTls(socket: Socket, own: TlsCertificates.Issued, inbox: Channel<Link>) {
        socket.tcpNoDelay = true
        val (ssl, member) = TlsLinks.server(socket, own, config.handshakeTimeoutMs, random) ?: return
        val link = SocketLink(ssl, member, LinkKind.DIRECT, scope, security = LinkSecurity.TLS)
        if (inbox.trySend(link).isFailure) ssl.close()
    }
}
