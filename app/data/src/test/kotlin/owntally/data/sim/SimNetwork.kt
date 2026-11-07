package owntally.data.sim

import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import owntally.model.PublicKey
import owntally.sync.Link
import owntally.sync.LinkKind

/** How badly the simulated network behaves. Every number is drawn from the scenario's seeded random source. */
data class SimFaults(val drop: Double, val duplicate: Double, val maxDelayMs: Long, val breakPerMessage: Double) {
    companion object {
        /** Reliable, ordered, quick: how the quiet period at the end of a scenario runs. */
        val NONE = SimFaults(0.0, 0.0, 0, 0.0)
    }
}

/**
 * Links between simulated phones, on virtual time (the test scheduler). A message is delayed by a random time (so messages can arrive in
 * another order), may be lost or delivered twice, and a link may break at any message. A real transport is ordered and reliable; these
 * faults are worse on purpose, to prove that no fault can corrupt a phone's data and that repeated syncs always converge.
 */
class SimNetwork(private val scope: CoroutineScope, private val random: Random) {
    var faults: SimFaults = SimFaults.NONE
    var linksMade = 0
        private set
    var messagesLost = 0
        private set
    var linksBroken = 0
        private set

    fun link(a: PublicKey, b: PublicKey): Pair<Link, Link> {
        linksMade++
        val x = Half(b)
        val y = Half(a)
        x.other = y
        y.other = x
        return x to y
    }

    private inner class Half(override val peer: PublicKey) : Link {
        lateinit var other: Half
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        @Volatile var closed = false
        override val kind = LinkKind.DIRECT
        override val received: Flow<ByteArray> = inbox.receiveAsFlow()

        override suspend fun send(bytes: ByteArray) {
            if (closed || other.closed) throw IOException("link closed")
            if (faults.breakPerMessage > 0 && random.nextDouble() < faults.breakPerMessage) {
                linksBroken++
                close()
                throw IOException("link broken")
            }
            if (faults.drop > 0 && random.nextDouble() < faults.drop) {
                messagesLost++
                return
            }
            val copies = if (faults.duplicate > 0 && random.nextDouble() < faults.duplicate) 2 else 1
            repeat(copies) {
                val wait = if (faults.maxDelayMs > 0) random.nextLong(0, faults.maxDelayMs + 1) else 0L
                val copy = bytes.copyOf()
                val target = other
                scope.launch {
                    if (wait > 0) delay(wait)
                    if (!target.closed) target.inbox.trySend(copy)
                }
            }
        }

        override suspend fun close() {
            if (closed) return
            closed = true
            inbox.close()
            val target = other
            target.closed = true
            target.inbox.close()
        }
    }
}
