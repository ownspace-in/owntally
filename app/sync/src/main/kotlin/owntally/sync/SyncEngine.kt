package owntally.sync

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import owntally.model.PublicKey

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
    /** Authors whose events the two phones disagreed about (same counters, different versions); every version of them was exchanged. */
    val reconciled: Set<PublicKey>,
    val error: ErrorCode?,
    val detail: String,
    /** The kind of link the sync ran over. */
    val via: LinkKind? = null,
    /** How the link was protected (a local link: TLS or plain), if it says. */
    val security: LinkSecurity? = null,
)

/** What this phone does for a peer that connects or is connected to (decision S2, T5.7). */
sealed class Admission {
    /** A member: everything is exchanged. */
    data object Full : Admission()

    /** Someone who was never a member: nothing is sent and nothing they send is read. They get an `Error` and nothing else. */
    data object Refuse : Admission()

    /**
     * Someone who is no longer a member: they are shown only [summary] (computed again when it is sent, so it can show what was just
     * stored), are sent only [send], and no fingerprints are compared with them. What they hand over is stored as usual.
     */
    class Limited(val summary: () -> SyncSummary, val send: List<ByteArray>) : Admission()
}

/** Decides, by the key the link was opened with, what a peer may have. The engine asks before it sends anything. */
fun interface PeerAccess {
    fun admit(peer: PublicKey): Admission

    companion object {
        val EVERYONE = PeerAccess { Admission.Full }
    }
}

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
    /** Who may have what (S2). The default is everyone, for a store whose caller has already decided. */
    private val access: PeerAccess = PeerAccess.EVERYONE,
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

        val reconciled = LinkedHashSet<PublicKey>()

        suspend fun execute(): SyncResult = try {
            val admission = access.admit(link.peer)
            if (admission is Admission.Refuse) {
                // Before a single summary or envelope is sent. A phone that was connected to tells the peer; one that connected says nothing.
                throw if (role == SyncRole.RESPONDER) refuse(ErrorCode.NOT_ALLOWED, "not allowed") else Stop(SyncOutcome.REFUSED, ErrorCode.NOT_ALLOWED, "not allowed")
            }
            var limited = admission as? Admission.Limited
            fun mySummary() = limited?.summary?.invoke() ?: store.summary()
            send(SyncMessage.Hello(room, mySummary()))
            val hello = next() as? SyncMessage.Hello ?: throw refuse(ErrorCode.INVALID, "the first message must be a Hello")
            if (hello.room != room) throw refuse(ErrorCode.WRONG_GROUP, "this is another group")
            peerSummary = hello.summary
            val owed = limited?.send ?: owedTo(hello.summary) // worked out before anything arrives, so what the peer sends is not sent back
            val mismatched = if (limited != null) emptyList() else mismatches(hello.summary) // authors whose versions differ from the peer's (S1)
            when (role) {
                SyncRole.INITIATOR -> {
                    for (author in mismatched) { send(SyncMessage.Reconcile(author)); reconciled += author }
                    sendEnvelopes(distinct(owed + mismatched.flatMap { store.versionsOf(it) }))
                    send(SyncMessage.Done(mySummary()))
                    val asked = receiveRound()
                    // The responder found a difference we could not see (its history is longer): send it every version we hold of those.
                    // It waits for our last Done whenever it asked, so a limited phone answers too, with no envelopes: it is not a member.
                    if (asked.isNotEmpty()) {
                        if (limited == null) {
                            reconciled += asked
                            sendEnvelopes(distinct((asked - mismatched.toSet()).flatMap { store.versionsOf(it) }))
                        }
                        send(SyncMessage.Done(mySummary()))
                    }
                }
                SyncRole.RESPONDER -> {
                    var asked = receiveRound().let { if (limited != null) emptySet() else it }
                    // What the peer handed over may have changed who they are (their leave, for one): decide again before sending our part.
                    if (limited == null) {
                        when (val again = access.admit(link.peer)) {
                            is Admission.Limited -> { limited = again; asked = emptySet() }
                            Admission.Refuse -> { limited = Admission.Limited({ SyncSummary(emptyMap()) }, emptyList()); asked = emptySet() }
                            Admission.Full -> Unit
                        }
                    }
                    reconciled += asked
                    val send = limited?.let { it.send } ?: (owed + (asked + mismatched).flatMap { store.versionsOf(it) })
                    for (author in if (limited != null) emptyList() else mismatched) { send(SyncMessage.Reconcile(author)); reconciled += author }
                    sendEnvelopes(distinct(send))
                    send(SyncMessage.Done(mySummary()))
                    if (mismatched.isNotEmpty() && limited == null) receiveRound() // the initiator's answer to our Reconcile
                }
            }
            result(SyncOutcome.COMPLETED, null, "")
        } catch (s: Stop) {
            result(s.outcome, s.error, s.detail)
        }

        private fun result(outcome: SyncOutcome, error: ErrorCode?, detail: String) =
            SyncResult(outcome, sent, stored, duplicates, rejected.toMap(), peerSummary, peerFinal, reconciled.toSet(), error, detail, link.kind, link.security)

        /** Everything the peer lacks according to its summary: per author, our versions above its counter. */
        private fun owedTo(peer: SyncSummary): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            for ((author, mine) in store.summary().entries) {
                val theirs = peer.entries[author]?.counter ?: 0L
                if (mine.counter > theirs) out += store.versionsAbove(author, theirs)
            }
            return out
        }

        /**
         * The authors for whom both phones hold the same counters (as far as the shorter one goes) but a different fingerprint: some
         * event exists in different versions. Only the phone with the longer (or equal) history can see it; the other is told with `Reconcile`.
         */
        private fun mismatches(peer: SyncSummary): List<PublicKey> = peer.entries.filter { (author, theirs) ->
            val mine = store.summary().entries[author]
            mine != null && theirs.counter <= mine.counter && store.fingerprintAt(author, theirs.counter)?.contentEquals(theirs.fingerprint) != true
        }.keys.toList()

        private fun distinct(list: List<ByteArray>) = list.distinctBy { it.toList() }

        private suspend fun sendEnvelopes(list: List<ByteArray>) {
            for (batch in SyncMessage.Envelopes.batches(list)) {
                send(batch)
                sent += batch.envelopes.size
            }
        }

        /** Takes in what the peer sends until its `Done`; returns the authors it asked about with `Reconcile`. */
        private suspend fun receiveRound(): Set<PublicKey> {
            val asked = LinkedHashSet<PublicKey>()
            while (true) {
                when (val m = next()) {
                    is SyncMessage.Envelopes -> take(m)
                    is SyncMessage.Reconcile -> asked += m.author
                    is SyncMessage.Done -> { peerFinal = m.summary; return asked }
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
