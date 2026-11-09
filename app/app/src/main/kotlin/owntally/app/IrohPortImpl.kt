package owntally.app

import android.content.Context
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.IrohAndroid
import computer.iroh.RelayMode
import computer.iroh.presetN0
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import owntally.crypto.GroupIdentity
import owntally.model.PublicKey
import owntally.sync.IrohConnection
import owntally.sync.IrohPort

/**
 * n0's Iroh library behind the small interface the sync code uses ([IrohPort]). All of the logic (retries, framing, the check of who answered,
 * the chain with the relay) is in `IrohTransport` and tested without the native library; this class only passes things through. It is not
 * tried on the JVM: two real phones on mobile networks are the check (docs/tasks/M7-internet-sync.md).
 *
 * The member's own key is Iroh's identity (decision I2): the endpoint id is the member key and Iroh's handshake proves it. [relayUrl] is
 * our relay and the only relay in the endpoint's list; with the default preset the phone also publishes its address to, and looks peers up
 * in, n0's discovery (decision I1: n0 learns the key, the IP address and when the phone is online).
 */
class IrohPortImpl(private val context: Context, private val identity: GroupIdentity, private val relayUrl: String) : IrohPort {
    private var endpoint: Endpoint? = null

    @Synchronized
    private fun bound(): Endpoint = endpoint ?: throw IOException("Iroh is not started")

    override suspend fun start(): PublicKey {
        IrohAndroid.installAndroidContext(context.applicationContext)
        val secret = identity.irohSecretKey()
        try {
            val ep = Endpoint.bind(
                EndpointOptions(preset = presetN0(), secretKey = secret, alpns = listOf(ALPN), relayMode = RelayMode.customFromUrls(listOf(relayUrl))),
            )
            synchronized(this) { endpoint = ep }
            return PublicKey(ep.id().toBytes())
        } finally {
            secret.fill(0)
        }
    }

    override suspend fun connect(peer: PublicKey, relayHint: String?): IrohConnection {
        val ep = bound()
        try {
            val conn = ep.connect(EndpointAddr(EndpointId.fromBytes(peer.bytes()), relayHint ?: relayUrl, emptyList()), ALPN)
            return Wrapped(conn, conn.openBi())
        } catch (e: computer.iroh.IrohException) {
            throw IOException("iroh connect failed: ${e.kind()} ${e.debugMessage()}", e)
        }
    }

    override fun incoming(): Flow<IrohConnection> = flow {
        val ep = bound()
        while (true) {
            val incoming = ep.acceptNext() ?: break
            // One that fails to complete is dropped: it must not stop the others being accepted.
            val conn = try { incoming.accept().connect() } catch (e: Exception) { continue }
            val bi = try { conn.acceptBi() } catch (e: Exception) { continue }
            emit(Wrapped(conn, bi))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun stop() {
        val ep = synchronized(this) { endpoint.also { endpoint = null } } ?: return
        runCatching { ep.shutdown() }
    }

    private class Wrapped(private val conn: Connection, private val bi: computer.iroh.BiStream) : IrohConnection {
        override val remote: PublicKey = PublicKey(conn.remoteId().toBytes())
        override val isDirect: Boolean get() = conn.paths().firstOrNull { it.isSelected }?.isRelay == false
        private val send = bi.send()
        private val recv = bi.recv()

        override suspend fun write(bytes: ByteArray) {
            try { send.writeAll(bytes) } catch (e: computer.iroh.IrohException) { throw IOException("iroh write failed: ${e.kind()}", e) }
        }

        override suspend fun readExact(n: Int): ByteArray =
            try { recv.readExact(n.toUInt()) } catch (e: computer.iroh.IrohException) { throw IOException("iroh read failed: ${e.kind()}", e) }

        override suspend fun close() { conn.close(0, ByteArray(0)) }
    }

    private companion object {
        val ALPN = "owntally/1".toByteArray()
    }
}
