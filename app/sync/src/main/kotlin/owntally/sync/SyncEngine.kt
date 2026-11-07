package owntally.sync

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class SyncRole { INITIATOR, RESPONDER }

enum class SyncOutcome {
    /** Both sides said everything they had. */
    COMPLETED,

    /** The link closed or failed part way. What was stored stays stored; the next sync carries on from there. */
    INTERRUPTED,

    /** The peer said nothing for the stall time. */
    TIMED_OUT,

    /** The peer sent an `Error`. */
    PEER_ERROR,

    /** This phone ended the sync because of what the peer sent (and told it with an `Error`). */
    REFUSED,
}

/**
 * What a sync did. [stored] counts envelopes that were new, [duplicates] ones already held, [rejected] the ones dropped (by kind);
 * [peerSummary] is the peer's summary when it opened, [peerFinal] the one it sent when it finished: for the answering side that is
 * its summary after storing everything this phone sent, which is the proof that a leave arrived (T5.9).
 */
data class SyncResult(
    val outcome: SyncOutcome,
    val sent: Int,
    val stored: Int,
    val duplicates: Int,
    val rejected: Map<Rejection, Int>,
    val peerSummary: SyncSummary?,
    val peerFinal: SyncSummary?,
    val error: ErrorCode?,
    val detail: String,
)

/**
 * One sync of one group over one [Link] (docs/WIRE_FORMAT.md section 6). Both sides send a `Hello`; the initiator then sends what the
 * responder lacks and a `Done`; the responder, having stored it, sends what the initiator lacks and a `Done` with its summary after
 * storing. Everything is stored as it arrives, so a sync that stops loses nothing and the next one carries on. The engine never opens an
 * envelope; the [store] checks and keeps them.
 */
class SyncEngine(
    private val room: GroupRoom,
    private val store: SyncStore,
    /** How long to wait for the peer's next message (DESIGN.md: 30 seconds). */
    private val stallTimeoutMs: Long = 30_000,
    /** A peer that sends more bad envelopes than this in one sync is cut off. */
    private val maxBadEnvelopes: Int = 20,
) {
    suspend fun run(link: Link, role: SyncRole): SyncResult = coroutineScope {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        val pump = launch {
            try {
                link.received.collect { inbox.send(it) }
            } finally {
                inbox.close()
            }
        }
        try {
            Session(link, inbox, role).execute()
        } finally {
            pump.cancel()
        }
    }

    private class Stop(val outcome: SyncOutcome, val error: ErrorCode?, val detail: String) : Exception(detail, null, false, false)

    private inner class Session(private val link: Link, private val inbox: Channel<ByteArray>, private val role: SyncRole) {
        var sent = 0
        var stored = 0
        var duplicates = 0
        var bad = 0
        val rejected = java.util.EnumMap<Rejection, Int>(Rejection::class.java)
        var peerSummary: SyncSummary? = null
        var peerFinal: SyncSummary? = null

        suspend fun execute(): SyncResult = try {
            send(SyncMessage.Hello(room, store.summary()))
            val hello = next() as? SyncMessage.Hello ?: throw refuse(ErrorCode.INVALID, "the first message must be a Hello")
            if (hello.room != room) throw refuse(ErrorCode.WRONG_GROUP, "this is another group")
            peerSummary = hello.summary
            val owed = owedTo(hello.summary) // worked out before anything arrives, so what the peer sends is not sent back
            when (role) {
                SyncRole.INITIATOR -> {
                    sendEnvelopes(owed)
                    send(SyncMessage.Done(store.summary()))
                    receiveUntilDone()
                }
                SyncRole.RESPONDER -> {
                    receiveUntilDone()
                    sendEnvelopes(owed)
                    send(SyncMessage.Done(store.summary()))
                }
            }
            result(SyncOutcome.COMPLETED, null, "")
        } catch (s: Stop) {
            result(s.outcome, s.error, s.detail)
        }

        private fun result(outcome: SyncOutcome, error: ErrorCode?, detail: String) =
            SyncResult(outcome, sent, stored, duplicates, rejected.toMap(), peerSummary, peerFinal, error, detail)

        /** Everything the peer lacks according to its summary: per author, our versions above its counter. */
        private fun owedTo(peer: SyncSummary): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            for ((author, mine) in store.summary().entries) {
                val theirs = peer.entries[author]?.counter ?: 0L
                if (mine.counter > theirs) out += store.versionsAbove(author, theirs)
            }
            return out
        }

        private suspend fun sendEnvelopes(list: List<ByteArray>) {
            for (batch in SyncMessage.Envelopes.batches(list)) {
                send(batch)
                sent += batch.envelopes.size
            }
        }

        private suspend fun receiveUntilDone() {
            while (true) {
                when (val m = next()) {
                    is SyncMessage.Envelopes -> take(m)
                    is SyncMessage.Done -> { peerFinal = m.summary; return }
                    else -> throw refuse(ErrorCode.INVALID, "unexpected message")
                }
            }
        }

        private suspend fun take(batch: SyncMessage.Envelopes) {
            for (bytes in batch.envelopes) {
                when (val r = store.offer(bytes)) {
                    Offered.Stored -> stored++
                    Offered.Duplicate -> duplicates++
                    is Offered.Rejected -> {
                        rejected.merge(r.why, 1, Int::plus)
                        bad++
                    }
                }
            }
            if (bad > maxBadEnvelopes) throw refuse(ErrorCode.TOO_MANY_BAD_ENVELOPES, "too many bad envelopes")
        }

        private suspend fun send(message: SyncMessage) {
            try {
                link.send(SyncCodec.encode(message))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw Stop(SyncOutcome.INTERRUPTED, null, "the link failed while sending")
            } catch (e: ClosedSendChannelException) {
                throw Stop(SyncOutcome.INTERRUPTED, null, "the link was closed")
            }
        }

        /** Tells the peer (best effort) and ends this sync. */
        private suspend fun refuse(code: ErrorCode, text: String): Stop {
            try {
                link.send(SyncCodec.encode(SyncMessage.Error(code, text)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the link is already gone; nothing more to tell
            }
            return Stop(SyncOutcome.REFUSED, code, text)
        }

        private suspend fun next(): SyncMessage {
            val received = withTimeoutOrNull(stallTimeoutMs) { inbox.receiveCatching() } ?: throw Stop(SyncOutcome.TIMED_OUT, null, "the peer went silent")
            val bytes = received.getOrNull() ?: throw Stop(SyncOutcome.INTERRUPTED, null, "the link closed")
            return when (val d = SyncCodec.decode(bytes)) {
                is Decoded.Refused -> throw refuse(d.code, d.reason)
                is Decoded.Ok -> {
                    val m = d.message
                    if (m is SyncMessage.Error) throw Stop(SyncOutcome.PEER_ERROR, m.code, m.text)
                    m
                }
            }
        }
    }
}
