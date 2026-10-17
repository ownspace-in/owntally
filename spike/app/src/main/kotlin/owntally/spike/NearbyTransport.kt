package owntally.spike

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import owntally.sync.Chunker
import owntally.sync.GroupRoom
import owntally.sync.Link
import owntally.sync.LinkKind
import owntally.sync.PublicKey
import owntally.sync.Reassembler
import owntally.sync.Transport
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Same-room transport over Google Nearby Connections. Spike version: connections are accepted
 * automatically. The product must show the pairing digits and ask the user to confirm.
 *
 * The advertising side calls incoming(); the other side calls connect() or connectAny().
 */
class NearbyTransport(context: Context, private val self: PublicKey) : Transport {
    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val chunker = Chunker()
    private val links = ConcurrentHashMap<String, NearbyLink>()
    private val strategy = Strategy.P2P_CLUSTER

    private fun serviceId(room: GroupRoom) = "owntally.spike.$room"

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            links[endpointId]?.deliver(bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    override fun incoming(room: GroupRoom): Flow<Link> = callbackFlow {
        val lifecycle = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                links[endpointId] = NearbyLink(peerOf(info.endpointName), endpointId)
                client.acceptConnection(endpointId, payloads)
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (result.status.isSuccess) links[endpointId]?.let { trySend(it) } else links.remove(endpointId)
            }

            override fun onDisconnected(endpointId: String) {
                links.remove(endpointId)?.end()
            }
        }
        client.startAdvertising(
            self.toString(),
            serviceId(room),
            lifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build(),
        ).addOnFailureListener { close(it) }
        awaitClose { client.stopAdvertising() }
    }

    override suspend fun connect(room: GroupRoom, peer: PublicKey): Link =
        connectMatching(room) { it == peer.toString() }

    /** Connects to the first nearby member found in this room. Used by the spike, which has no peer list. */
    suspend fun connectAny(room: GroupRoom): Link = connectMatching(room) { true }

    private suspend fun connectMatching(room: GroupRoom, accept: (String) -> Boolean): Link =
        withTimeout(CONNECT_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { cont ->
                val lifecycle = object : ConnectionLifecycleCallback() {
                    override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                        links[endpointId] = NearbyLink(peerOf(info.endpointName), endpointId)
                        client.acceptConnection(endpointId, payloads)
                    }

                    override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                        val link = links[endpointId]
                        if (result.status.isSuccess && link != null) {
                            if (cont.isActive) cont.resume(link)
                        } else {
                            links.remove(endpointId)
                            if (cont.isActive) {
                                cont.resumeWithException(IOException("Nearby refused: ${result.status.statusMessage}"))
                            }
                        }
                    }

                    override fun onDisconnected(endpointId: String) {
                        links.remove(endpointId)?.end()
                    }
                }
                val discovery = object : EndpointDiscoveryCallback() {
                    override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                        if (!accept(info.endpointName)) return
                        client.stopDiscovery()
                        client.requestConnection(self.toString(), endpointId, lifecycle)
                            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
                    }

                    override fun onEndpointLost(endpointId: String) = Unit
                }
                client.startDiscovery(
                    serviceId(room),
                    discovery,
                    DiscoveryOptions.Builder().setStrategy(strategy).build(),
                ).addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
                cont.invokeOnCancellation { client.stopDiscovery() }
            }
        }

    fun stop() {
        client.stopAllEndpoints()
        links.values.forEach { it.end() }
        links.clear()
    }

    private fun peerOf(name: String): PublicKey =
        runCatching { PublicKey.fromHex(name) }.getOrElse { PublicKey(ByteArray(PublicKey.SIZE)) }

    private inner class NearbyLink(override val peer: PublicKey, private val endpointId: String) : Link {
        private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        private val reassembler = Reassembler()
        override val kind = LinkKind.NEARBY
        override val received: Flow<ByteArray> = inbox.receiveAsFlow()

        override suspend fun send(bytes: ByteArray) {
            for (chunk in chunker.split(bytes)) client.sendPayload(endpointId, Payload.fromBytes(chunk)).await()
        }

        fun deliver(chunk: ByteArray) {
            reassembler.add(chunk)?.let { inbox.trySend(it) }
        }

        fun end() {
            inbox.close()
        }

        override suspend fun close() {
            client.disconnectFromEndpoint(endpointId)
            links.remove(endpointId)
            inbox.close()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 30_000L
    }
}
