package owntally.app

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import owntally.sync.NearbyEvent
import owntally.sync.NearbyPort

/**
 * Google's Nearby Connections behind the small interface the sync code uses ([NearbyPort]). All of the logic (who opens the connection, the
 * proof of keys, chunking, waiting) is in `NearbyTransport` and tested without a phone; this class only passes things through. It cannot be
 * tried on an emulator: two real phones are the check (docs/tasks/M6-nearby-invites.md). [serviceId] is the same on the phones of one group.
 */
class PlayNearbyPort(context: Context, private val serviceId: String) : NearbyPort {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val channel = Channel<NearbyEvent>(Channel.UNLIMITED)
    override val events: Flow<NearbyEvent> = channel.receiveAsFlow()
    override val maxPayload: Int = ConnectionsClient.MAX_BYTES_DATA_SIZE

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { channel.trySend(NearbyEvent.Payload(endpointId, it)) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) { channel.trySend(NearbyEvent.Initiated(endpointId, info.endpointName)) }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) { channel.trySend(NearbyEvent.Result(endpointId, result.status.isSuccess)) }
        override fun onDisconnected(endpointId: String) { channel.trySend(NearbyEvent.Disconnected(endpointId)) }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) { channel.trySend(NearbyEvent.Found(endpointId, info.endpointName)) }
        override fun onEndpointLost(endpointId: String) { channel.trySend(NearbyEvent.Lost(endpointId)) }
    }

    override fun startAdvertising(name: String) {
        client.startAdvertising(name, serviceId, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
    }

    override fun startDiscovery() {
        client.startDiscovery(serviceId, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
    }

    override fun stopAdvertising() = client.stopAdvertising()
    override fun stopDiscovery() = client.stopDiscovery()
    override fun requestConnection(endpoint: String, ownName: String) { client.requestConnection(ownName, endpoint, lifecycle) }
    override fun acceptConnection(endpoint: String) { client.acceptConnection(endpoint, payloads) }
    override fun send(endpoint: String, bytes: ByteArray) { client.sendPayload(endpoint, Payload.fromBytes(bytes)) }
    override fun disconnect(endpoint: String) = client.disconnectFromEndpoint(endpoint)
}
