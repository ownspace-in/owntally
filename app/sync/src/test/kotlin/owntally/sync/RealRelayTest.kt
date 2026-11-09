package owntally.sync

import java.net.ServerSocket
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import owntally.model.PublicKey

/**
 * Runs the transport against the real Go relay. Skipped unless RELAY_BIN points at a built ws-relay binary:
 *   (cd relay/ws-relay && go build -o /tmp/ws-relay .) && RELAY_BIN=/tmp/ws-relay ./gradlew :app:sync:test
 */
class RealRelayTest {
    private val room = GroupRoom(ByteArray(16) { (it + 1).toByte() })
    private val alice = PublicKey(ByteArray(32) { 0xA1.toByte() })
    private val bob = PublicKey(ByteArray(32) { 0xB2.toByte() })

    private fun startRelay(port: Int): Process {
        val process = ProcessBuilder(System.getenv("RELAY_BIN"), "-addr", "127.0.0.1:$port", "-connects-per-min", "1000")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        repeat(50) {
            try {
                if (URL("http://127.0.0.1:$port/healthz").readText().trim() == "ok") return process
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        process.destroy()
        error("relay did not start")
    }

    @Test
    fun probeThroughTheRealRelayAndAfterARestart() {
        assumeTrue(System.getenv("RELAY_BIN") != null, "RELAY_BIN is not set")
        val port = ServerSocket(0).use { it.localPort }
        var relay = startRelay(port)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val url = "ws://127.0.0.1:$port/ws"
        val a = WebSocketTransport(alice, url, scope, backoffMillis = listOf(100), openTimeoutMillis = 5_000)
        val b = WebSocketTransport(bob, url, scope, backoffMillis = listOf(100), openTimeoutMillis = 5_000)
        try {
            runBlocking {
                withTimeout(60_000) {
                    coroutineScope {
                        val server = launch { b.incoming(room).collect { link -> launch { Probe.serve(link) } } }
                        b.status(room).first { it == RelayStatus.OPEN }

                        val first = Probe.run(a.connect(room, bob), repeats = 3, timeoutMillis = 5_000)
                        assertEquals(9, first.acked, first.summary())
                        assertEquals(0, first.corrupt)

                        relay.destroy()
                        relay.waitFor()
                        a.status(room).first { it == RelayStatus.RECONNECTING }
                        relay = startRelay(port)
                        a.status(room).first { it == RelayStatus.OPEN }
                        b.status(room).first { it == RelayStatus.OPEN }
                        delay(300)

                        val second = Probe.run(a.connect(room, bob), repeats = 3, timeoutMillis = 5_000)
                        assertEquals(9, second.acked, second.summary())
                        server.cancel()
                    }
                }
            }
        } finally {
            a.close()
            b.close()
            scope.cancel()
            relay.destroy()
        }
    }

    @Test
    fun aSecureLinkThroughTheRealRelayRunsAWholeSyncWithoutBeingCutOffForSendingTooFast() {
        assumeTrue(System.getenv("RELAY_BIN") != null, "RELAY_BIN is not set")
        val port = ServerSocket(0).use { it.localPort }
        val relay = startRelay(port)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val url = "ws://127.0.0.1:$port/ws"
        val seed = owntally.crypto.MasterSeed(ByteArray(64) { 8 })
        val ida = owntally.crypto.KeyDerivation.groupIdentity(seed, 0)
        val idb = owntally.crypto.KeyDerivation.groupIdentity(seed, 1)
        val group = owntally.model.GroupId(ByteArray(16) { 3 })
        val ra = WebSocketTransport(ida.publicKey, url, scope, backoffMillis = listOf(100), openTimeoutMillis = 5_000)
        val rb = WebSocketTransport(idb.publicKey, url, scope, backoffMillis = listOf(100), openTimeoutMillis = 5_000)
        val ta = SecureOverCarrier(ra, ida, scope)
        val tb = SecureOverCarrier(rb, idb, scope)
        fun env(n: Int, c: Long): ByteArray = owntally.crypto.EnvelopeSigning.sign(owntally.crypto.KeyDerivation.groupIdentity(seed, 100L + n), 1, group, 1, c, ByteArray(24), ByteArray(300) { (c + it).toByte() }).encode()
        val sa = InMemorySyncStore(group).also { st -> (1L..1500L).forEach { st.offer(env(1, it)) } }
        val sb = InMemorySyncStore(group).also { st -> (1L..800L).forEach { st.offer(env(2, it)) } }
        try {
            runBlocking {
                withTimeout(120_000) {
                    val incoming = tb.incoming(room)
                    rb.status(room).first { it == RelayStatus.OPEN }
                    val ab = ta.connect(room, idb.publicKey)
                    val ba = incoming.first()
                    assertEquals(idb.publicKey, ab.peer); assertEquals(ida.publicKey, ba.peer)
                    assertEquals(LinkKind.RELAYED, ab.kind)
                    coroutineScope {
                        val x = this.async { SyncEngine(room, sa).run(ab, SyncRole.INITIATOR) }
                        val y = this.async { SyncEngine(room, sb).run(ba, SyncRole.RESPONDER) }
                        assertEquals(SyncOutcome.COMPLETED, x.await().outcome)
                        assertEquals(SyncOutcome.COMPLETED, y.await().outcome)
                    }
                    assertEquals(sa.summary(), sb.summary())
                    assertEquals(2, sa.summary().entries.size)
                }
            }
        } finally {
            ra.close(); rb.close(); scope.cancel(); relay.destroy()
        }
    }
}
