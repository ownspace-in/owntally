package owntally.sync

import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ProbeResult(
    val sent: Int,
    val acked: Int,
    val corrupt: Int,
    val failedToSend: Int,
    val rttMillis: List<Long>,
) {
    fun summary(): String {
        val sorted = rttMillis.sorted()
        val avg = if (sorted.isEmpty()) 0 else sorted.sum() / sorted.size
        val p95 = if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * 0.95).toInt()]
        val max = sorted.lastOrNull() ?: 0
        return "sent=$sent acked=$acked corrupt=$corrupt sendFailed=$failedToSend " +
            "rtt avg=${avg}ms p95=${p95}ms max=${max}ms"
    }
}

/**
 * Measures any Transport: the initiator sends messages of several sizes, the responder checks the
 * content and answers. Used by the M0 spike to compare transports.
 */
object Probe {
    private const val DATA: Byte = 0
    private const val ACK: Byte = 1
    private const val HEADER = 9

    fun pattern(seq: Int, size: Int) = ByteArray(size) { i -> (seq * 31 + i * 7).toByte() }

    fun dataMessage(seq: Int, size: Int): ByteArray =
        ByteBuffer.allocate(HEADER + size).put(DATA).putInt(seq).putInt(size).put(pattern(seq, size)).array()

    private fun ackMessage(seq: Int, ok: Boolean): ByteArray =
        ByteBuffer.allocate(6).put(ACK).putInt(seq).put(if (ok) 1 else 0).array()

    /** Answers every probe message on this link until the link ends. */
    suspend fun serve(link: Link, log: (String) -> Unit = {}) {
        link.received.collect { msg ->
            if (msg.isEmpty() || msg[0] != DATA || msg.size < HEADER) {
                log("other message, ${msg.size} bytes")
                return@collect
            }
            val buf = ByteBuffer.wrap(msg)
            buf.get()
            val seq = buf.int
            val size = buf.int
            val body = msg.copyOfRange(HEADER, msg.size)
            val ok = size == body.size && body.contentEquals(pattern(seq, size))
            log("got #$seq, $size bytes, ${if (ok) "ok" else "CORRUPT"}")
            try {
                link.send(ackMessage(seq, ok))
            } catch (e: Exception) {
                log("could not answer #$seq: ${e.message}")
            }
        }
    }

    suspend fun run(
        link: Link,
        sizes: List<Int> = listOf(1, 1_000, 50_000),
        repeats: Int = 10,
        timeoutMillis: Long = 10_000,
        log: (String) -> Unit = {},
    ): ProbeResult = coroutineScope {
        val acks = Channel<Pair<Int, Boolean>>(Channel.UNLIMITED)
        val reader = launch(Dispatchers.Unconfined) {
            link.received.collect { msg ->
                if (msg.size == 6 && msg[0] == ACK) {
                    val buf = ByteBuffer.wrap(msg)
                    buf.get()
                    acks.trySend(buf.int to (buf.get() == 1.toByte()))
                }
            }
        }
        var seq = 0
        var sent = 0
        var acked = 0
        var corrupt = 0
        var failed = 0
        val rtts = mutableListOf<Long>()
        for (size in sizes) {
            repeat(repeats) {
                seq++
                val started = System.nanoTime()
                try {
                    link.send(dataMessage(seq, size))
                    sent++
                } catch (e: Exception) {
                    failed++
                    log("send #$seq failed: ${e.message}")
                    return@repeat
                }
                val mine = seq
                val reply = withTimeoutOrNull(timeoutMillis) { acks.receiveAsFlow().first { it.first == mine } }
                if (reply == null) {
                    log("#$seq ($size bytes): no answer in ${timeoutMillis}ms")
                } else {
                    acked++
                    if (!reply.second) corrupt++
                    rtts += (System.nanoTime() - started) / 1_000_000
                }
            }
        }
        reader.cancel()
        ProbeResult(sent, acked, corrupt, failed, rtts)
    }
}
