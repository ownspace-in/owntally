package owntally.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import owntally.crypto.GroupIdentity
import owntally.sync.ChainConfig
import owntally.sync.ChainTransport
import owntally.sync.IrohConfig
import owntally.sync.IrohTransport
import owntally.sync.SecureOverCarrier
import owntally.sync.Transport
import owntally.sync.WebSocketTransport

/**
 * Every way one member key reaches the other phones (M7, T7.10), as one transport: the same network first, then Iroh and our relay
 * (docs/tasks/M7-internet-sync.md, T7.5). [stop] closes what holds a connection (Iroh's endpoint and the relay's sockets).
 *
 * Iroh binds one endpoint for each member key (decision I2: the member key is Iroh's identity) and the relay holds one socket for each group,
 * and neither starts until the transport is first used. A stopped one is not started again: make a new one.
 */
class InternetWays(
    context: Context,
    identity: GroupIdentity,
    scope: CoroutineScope,
    local: Transport?,
    relayHttpUrl: String = RELAY_HTTP_URL,
    relaySocketUrl: String = RELAY_SOCKET_URL,
    chain: ChainConfig = ChainConfig(),
    /** Leave one of the two internet ways out (the checks on a device use it to try each alone). */
    useIroh: Boolean = true,
    useRelay: Boolean = true,
) {
    private val iroh = if (useIroh) IrohTransport(IrohPortImpl(context, identity, relayHttpUrl), scope, IrohConfig()) else null
    private val socket = if (useRelay) WebSocketTransport(identity.publicKey, relaySocketUrl, scope) else null
    private val relay = socket?.let { SecureOverCarrier(it, identity, scope) }

    val transport: Transport = ChainTransport(scope, local, iroh, relay, chain)

    suspend fun stop() {
        runCatching { iroh?.stop() }
        runCatching { socket?.close() }
    }

    companion object {
        /** Our relay (decision: the only relay, 4 Nov 2026). The Iroh relay and the WebSocket relay are on the same host. */
        const val RELAY_HTTP_URL = "https://relay.ownspace.in"
        const val RELAY_SOCKET_URL = "wss://relay.ownspace.in/ws"
    }
}
