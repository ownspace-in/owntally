package owntally.sync

import java.io.File
import java.net.ServerSocket
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import owntally.crypto.EnvelopeSigning
import owntally.crypto.KeyDerivation
import owntally.crypto.MasterSeed
import owntally.model.GroupId
import owntally.model.PublicKey

/**
 * T7.9: the relay's limits (64 KB frames, 50 messages a second with a burst of 100, 20 clients in a room, 30 new connections a minute from one
 * address) against what the app really does, on the real Go relay. Skipped unless RELAY_BIN points at a built ws-relay. Numbers go to the file
 * named by RELAY_REPORT when it is set (docs/tasks/M7-internet-sync.md quotes them):
 *   (cd relay/ws-relay && go build -buildvcs=false -o /tmp/ws-relay .) && RELAY_BIN=/tmp/ws-relay RELAY_REPORT=/tmp/relay.txt ./gradlew :app:sync:test --tests '*RelayLimitsTest'
 */
class RelayLimitsTest {
    private val seed = MasterSeed(ByteArray(64) { 8 })

    private fun report(line: String) { System.getenv("RELAY_REPORT")?.let { File(it).appendText(line + "\n") } }

    private fun startRelay(port: Int, vararg flags: String): Process {
        val process = ProcessBuilder(listOf(System.getenv("RELAY_BIN"), "-addr", "127.0.0.1:$port") + flags)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        repeat(50) {
            try { if (URL("http://127.0.0.1:$port/healthz").readText().trim() == "ok") return process } catch (_: Exception) { Thread.sleep(100) }
        }
        process.destroy()
        error("relay did not start")
    }

    private fun key(n: Int) = PublicKey(ByteArray(32).also { it[0] = (n and 0xFF).toByte(); it[1] = (n shr 8).toByte() })

    @Test
    fun aBigHistoryCrossesTheRealRelayWithoutBeingCutOff() {
        assumeTrue(System.getenv("RELAY_BIN") != null, "RELAY_BIN is not set")
        val port = ServerSocket(0).use { it.localPort }
        val relay = startRelay(port, "-connects-per-min", "1000")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val url = "ws://127.0.0.1:$port/ws"
        val ida = KeyDerivation.groupIdentity(seed, 0); val idb = KeyDerivation.groupIdentity(seed, 1)
        val group = GroupId(ByteArray(16) { 3 }); val room = GroupRoom.fromGroupId(group.bytes())
        val ra = WebSocketTransport(ida.publicKey, url, scope, backoffMillis = listOf(100), openTimeoutMillis = 5_000)
        val rb = WebSocketTransport(idb.publicKey, url, scope, backoffMillis = listOf(100), openTimeoutMillis = 5_000)
        val ta = SecureOverCarrier(ra, ida, scope); val tb = SecureOverCarrier(rb, idb, scope)
        // A realistic envelope: an expense with a description and a split is a few hundred bytes after signing and sealing.
        val authors = (1..5).map { KeyDerivation.groupIdentity(seed, 100L + it) }
        fun env(n: Int, c: Long): ByteArray = EnvelopeSigning.sign(authors[n], 1, group, 1, c, ByteArray(24), ByteArray(300) { (c + it).toByte() }).encode()
        val total = 5_000
        val sa = InMemorySyncStore(group).also { st -> for (n in 0 until 5) for (c in 1L..(total / 5).toLong()) st.offer(env(n, c)) }
        val sb = InMemorySyncStore(group)
        try {
            runBlocking {
                withTimeout(180_000) {
                    val incoming = tb.incoming(room)
                    rb.status(room).first { it == RelayStatus.OPEN }
                    val ab = ta.connect(room, idb.publicKey)
                    val ba = incoming.first()
                    val started = System.nanoTime()
                    coroutineScope {
                        val x = async { SyncEngine(room, sa).run(ab, SyncRole.INITIATOR) }
                        val y = async { SyncEngine(room, sb).run(ba, SyncRole.RESPONDER) }
                        assertEquals(SyncOutcome.COMPLETED, x.await().outcome)
                        assertEquals(SyncOutcome.COMPLETED, y.await().outcome)
                    }
                    val seconds = (System.nanoTime() - started) / 1e9
                    assertEquals(sa.summary(), sb.summary(), "the other phone has the whole history")
                    report("history: $total envelopes (about ${total * 450 / 1000} KB signed) through the real relay in ${"%.1f".format(seconds)} s = ${"%.0f".format(total / seconds)} envelopes/s, not cut off")
                }
            }
        } finally {
            ra.close(); rb.close(); scope.cancel(); relay.destroy()
        }
    }

    @Test
    fun aPhoneListeningInManyGroupsAtOnceIsHeldBackByTheConnectionLimitAndGetsThroughAsItRefills() {
        assumeTrue(System.getenv("RELAY_BIN") != null, "RELAY_BIN is not set")
        val port = ServerSocket(0).use { it.localPort }
        val relay = startRelay(port) // the limits as they are in production: 30 new connections a minute from one address
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val url = "ws://127.0.0.1:$port/ws"
        val groups = 40
        val phone = WebSocketTransport(key(1), url, scope, backoffMillis = listOf(1_000, 5_000, 30_000), openTimeoutMillis = 2_000)
        try {
            runBlocking {
                withTimeout(150_000) {
                    val started = System.nanoTime()
                    val rooms = (1..groups).map { GroupRoom(ByteArray(16) { i -> (it + i).toByte() }) }
                    for (r in rooms) phone.incoming(r)
                    delay(3_000)
                    val openAfter3s = rooms.count { phone.status(it).value == RelayStatus.OPEN }
                    var secondsToAll = -1.0
                    while (secondsToAll < 0) {
                        if (rooms.all { phone.status(it).value == RelayStatus.OPEN }) secondsToAll = (System.nanoTime() - started) / 1e9 else delay(500)
                    }
                    report("many groups: a phone listening in $groups groups at once: $openAfter3s open after 3 s (limit 30 new connections a minute from one address), all $groups open after ${"%.0f".format(secondsToAll)} s")
                    assertTrue(openAfter3s in 25..32, "about 30 get in at once, the rest wait: $openAfter3s")
                }
            }
        } finally {
            phone.close(); scope.cancel(); relay.destroy()
        }
    }

    @Test
    fun aRoomHoldsTwentyPhonesAndThe21stIsTurnedAway() {
        assumeTrue(System.getenv("RELAY_BIN") != null, "RELAY_BIN is not set")
        val port = ServerSocket(0).use { it.localPort }
        val relay = startRelay(port, "-connects-per-min", "1000")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val url = "ws://127.0.0.1:$port/ws"
        val room = GroupRoom(ByteArray(16) { 9 })
        val phones = (1..21).map { WebSocketTransport(key(it), url, scope, backoffMillis = listOf(200), openTimeoutMillis = 2_000) }
        try {
            runBlocking {
                withTimeout(60_000) {
                    for (p in phones) p.incoming(room)
                    delay(3_000)
                    val open = phones.count { it.status(room).value == RelayStatus.OPEN }
                    report("room: $open of 21 phones in one room are connected (the relay allows 20; a group holds at most 20 members, but a removed member or a joiner waiting to be admitted is one more)")
                    assertEquals(20, open)
                }
            }
        } finally {
            phones.forEach { it.close() }; scope.cancel(); relay.destroy()
        }
    }
}
