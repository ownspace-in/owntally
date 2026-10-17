package owntally.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class ProbeTest {
    private val room = GroupRoom(ByteArray(16) { it.toByte() })
    private val alice = PublicKey(ByteArray(32) { 1 })
    private val bob = PublicKey(ByteArray(32) { 2 })

    @Test
    fun probeOverInMemoryTransportDeliversEverything() = runTest {
        val net = InMemoryNetwork()
        val a = net.transportFor(alice)
        val b = net.transportFor(bob)
        b.incoming(room)

        val toBob = a.connect(room, bob)
        val fromAlice = b.incoming(room).first()
        val server = launch { Probe.serve(fromAlice) }

        val result = Probe.run(toBob, sizes = listOf(1, 1_000, 50_000), repeats = 3, timeoutMillis = 5_000)

        assertEquals(9, result.sent)
        assertEquals(9, result.acked)
        assertEquals(0, result.corrupt)
        server.cancel()
    }

    @Test
    fun patternIsStablePerSeqAndSize() {
        assertEquals(Probe.pattern(3, 100).toList(), Probe.pattern(3, 100).toList())
        assertEquals(false, Probe.pattern(3, 100).contentEquals(Probe.pattern(4, 100)))
    }
}
