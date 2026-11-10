package owntally.sync

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import owntally.crypto.GroupIdentity
import owntally.crypto.LinkAuth
import owntally.model.PublicKey

/** What Google's Nearby Connections tells us, boiled down (the real one is in the app: `PlayNearbyPort`). */
sealed class NearbyEvent {
    data class Found(val endpoint: String, val name: String) : NearbyEvent()
    data class Lost(val endpoint: String) : NearbyEvent()

    /** A connection is being made (by us or by them): both sides must accept before anything is sent. */
    data class Initiated(val endpoint: String, val name: String) : NearbyEvent()
    data class Result(val endpoint: String, val ok: Boolean) : NearbyEvent()
    class Payload(val endpoint: String, val bytes: ByteArray) : NearbyEvent()
    data class Disconnected(val endpoint: String) : NearbyEvent()
}

/** What the transport needs from Nearby Connections. Payloads arrive in order and whole, each at most [maxPayload] bytes. */
interface NearbyPort {
    val maxPayload: Int
    val events: Flow<NearbyEvent>
    fun startAdvertising(name: String)
    fun startDiscovery()
    fun stopAdvertising()
    fun stopDiscovery()
    fun requestConnection(endpoint: String, ownName: String)
    fun acceptConnection(endpoint: String)
    fun send(endpoint: String, bytes: ByteArray)
    fun disconnect(endpoint: String)
}

class NearbyConfig(
    /** DESIGN.md: Nearby waits 15 seconds. It covers finding the other phone and making the connection. */
    val connectTimeoutMs: Long = 15_000,
    val handshakeTimeoutMs: Long = 10_000,
)

/** Where Nearby names are made: opaque, like the local ones (an endpoint name is seen by everyone nearby that is looking). */
object NearbyNames {
    fun endpointName(room: GroupRoom, key: PublicKey): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("owntally/v1/nearby".toByteArray() + room.id + key.bytes())
        return digest.copyOf(10).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Same-room sync over Nearby Connections (decision L1's neighbour; DESIGN.md: only when the person asks, 15 seconds). One instance for one
 * group and the key this phone uses in it. Nearby encrypts what it carries but does not tell either side who the other is, so the same
 * proofs of the plain local link run over it first ([LinkAuth]): a link is handed on only after both have shown they hold their keys.
 *
 * Who calls: of two phones, the one with the smaller key opens the connection; the other only answers (otherwise both would try at the same
 * moment). [connect] on the other phone says [TransportUnavailable], which the coordinator takes as "not now", not as a failure.
 */
class NearbyTransport(
    private val port: NearbyPort,
    private val identity: GroupIdentity,
    private val scope: CoroutineScope,
    private val config: NearbyConfig = NearbyConfig(),
    private val random: java.security.SecureRandom = java.security.SecureRandom(),
) : Transport {
    private class Endpoint(val id: String) {
        val inbox = Channel<ByteArray>(LinkQueue.CALLBACK_CAPACITY)
        val reassembler = Reassembler()
        var initiated = CompletableDeferred<String>() // the name it gave
        var result = CompletableDeferred<Boolean>()
        @Volatile var disconnected = false
    }

    private val endpoints = ConcurrentHashMap<String, Endpoint>()
    private val found = ConcurrentHashMap<String, String>() // name -> endpoint
    private val foundWaiters = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val incoming = Channel<Link>(Channel.UNLIMITED)
    private var collector: Job? = null
    private var advertisingRoom: GroupRoom? = null
    private var discovering = false
    private val chunker = Chunker(port.maxPayload - Chunker.HEADER)

    private fun endpoint(id: String) = endpoints.getOrPut(id) { Endpoint(id) }

    @Synchronized
    private fun ensureCollecting() {
        if (collector != null) return
        collector = scope.launch {
            port.events.collect { e ->
                when (e) {
                    is NearbyEvent.Found -> { found[e.name] = e.endpoint; foundWaiters[e.name]?.complete(e.endpoint) }
                    is NearbyEvent.Lost -> found.entries.removeAll { it.value == e.endpoint }
                    is NearbyEvent.Initiated -> {
                        val ep = endpoint(e.endpoint)
                        ep.initiated.complete(e.name)
                        port.acceptConnection(e.endpoint) // both sides accept; who the other is gets proven by key, not by this name
                        // A connection we did not open: answer its handshake (for the group this phone is advertising for).
                        val room = advertisingRoom
                        if (e.endpoint !in dialled && room != null) scope.launch { answer(room, ep) }
                    }
                    is NearbyEvent.Result -> endpoint(e.endpoint).result.complete(e.ok)
                    is NearbyEvent.Payload -> endpoint(e.endpoint).reassembler.add(e.bytes)?.let { endpoint(e.endpoint).let { ep -> if (ep.inbox.trySend(it).isFailure) ep.inbox.close() } }
                    is NearbyEvent.Disconnected -> endpoints.remove(e.endpoint)?.let { it.disconnected = true; it.result.complete(false); it.inbox.close() }
                }
            }
        }
    }

    /** Stops advertising and discovering and drops every connection: what "Stop" on the Nearby screen does. */
    @Synchronized
    fun stop() {
        port.stopAdvertising(); port.stopDiscovery()
        advertisingRoom = null; discovering = false
        // The collector is about to stop, so the links are ended here and not by the Disconnected events that follow.
        for (ep in endpoints.values.toList()) { ep.disconnected = true; ep.result.complete(false); ep.inbox.close(); port.disconnect(ep.id) }
        endpoints.clear()
        collector?.cancel(); collector = null
        found.clear()
    }

    private inner class NearbyLink(override val peer: PublicKey, private val endpoint: Endpoint) : Link {
        override val kind = LinkKind.NEARBY
        override suspend fun send(bytes: ByteArray) {
            if (endpoint.disconnected) throw java.io.IOException("the Nearby connection is gone")
            if (bytes.size > Frames.MAX) throw Frames.Bad("frame too large")
            chunker.split(bytes).forEach { port.send(endpoint.id, it) }
        }

        override val received: Flow<ByteArray> = endpoint.inbox.receiveAsFlow()
        override suspend fun close() { port.disconnect(endpoint.id) }
    }

    private suspend fun receive(endpoint: Endpoint, ms: Long): ByteArray =
        withTimeoutOrNull(ms) { endpoint.inbox.receiveCatching().getOrNull() } ?: throw LinkAuthFailed("no answer from the other phone")

    private fun sendMessage(endpoint: Endpoint, bytes: ByteArray) = chunker.split(bytes).forEach { port.send(endpoint.id, it) }

    override fun incoming(room: GroupRoom): Flow<Link> {
        ensureCollecting()
        synchronized(this) {
            if (advertisingRoom != room) {
                advertisingRoom = room
                port.startAdvertising(NearbyNames.endpointName(room, identity.publicKey))
            }
        }
        return incoming.receiveAsFlow()
    }

    private val dialled = ConcurrentHashMap.newKeySet<String>()

    private suspend fun answer(room: GroupRoom, ep: Endpoint) {
        try {
            if (withTimeoutOrNull(config.connectTimeoutMs) { ep.result.await() } != true) return port.disconnect(ep.id)
            val hello = LocalHandshake.Hello.decode(receive(ep, config.handshakeTimeoutMs))
            if (hello == null || hello.room != room || hello.to != identity.publicKey) return port.disconnect(ep.id)
            val nonce = LinkAuth.newNonce(random)
            val transcript = LocalHandshake.transcript(hello, LocalHandshake.KIND_PLAIN, nonce)
            sendMessage(ep, LocalHandshake.encodeReply(LocalHandshake.KIND_PLAIN, nonce, LinkAuth.signAsServer(identity, transcript)))
            val proof = LocalHandshake.decodeProof(receive(ep, config.handshakeTimeoutMs)) ?: return port.disconnect(ep.id)
            if (!LinkAuth.verifyClient(hello.from, transcript, proof)) return port.disconnect(ep.id)
            incoming.trySend(NearbyLink(hello.from, ep))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            port.disconnect(ep.id)
        }
    }

    override suspend fun connect(room: GroupRoom, peer: PublicKey): Link {
        if (identity.publicKey > peer) throw TransportUnavailable() // the other phone opens this connection
        ensureCollecting()
        synchronized(this) { if (!discovering) { discovering = true; port.startDiscovery() } }
        val name = NearbyNames.endpointName(room, peer)
        val endpointId: String
        val ep: Endpoint
        try {
            endpointId = withTimeout(config.connectTimeoutMs) {
                found[name] ?: foundWaiters.getOrPut(name) { CompletableDeferred() }.await()
            }
            ep = endpoint(endpointId)
            dialled += endpointId
            port.requestConnection(endpointId, NearbyNames.endpointName(room, identity.publicKey))
            if (!withTimeout(config.connectTimeoutMs) { ep.result.await() }) throw PeerUnreachable(peer)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw PeerUnreachable(peer)
        } finally {
            foundWaiters.remove(name)
        }
        try {
            val hello = LocalHandshake.Hello(LocalHandshake.KIND_PLAIN, room, identity.publicKey, peer, LinkAuth.newNonce(random))
            sendMessage(ep, hello.encode())
            val reply = LocalHandshake.decodeReply(receive(ep, config.handshakeTimeoutMs)) ?: throw LinkAuthFailed("no proper answer")
            val transcript = LocalHandshake.transcript(hello, reply.chosen, reply.nonce)
            if (!LinkAuth.verifyServer(peer, transcript, reply.signature)) throw LinkAuthFailed("the other phone does not hold the key it should")
            sendMessage(ep, LocalHandshake.encodeProof(LinkAuth.signAsClient(identity, transcript)))
            return NearbyLink(peer, ep)
        } catch (e: LinkAuthFailed) {
            port.disconnect(ep.id)
            throw e
        }
    }
}
