package owntally.sync

import java.security.MessageDigest
import java.util.TreeMap
import owntally.crypto.EnvelopeSigning
import owntally.model.DecodeException
import owntally.model.Envelope
import owntally.model.GroupId
import owntally.model.PublicKey

/** Why an envelope offered by a peer was not stored. */
enum class Rejection { MALFORMED, WRONG_GROUP, TOO_LARGE, BAD_SIGNATURE }

sealed class Offered {
    /** The envelope was new (or a smaller second version of an event) and is now stored. */
    data object Stored : Offered()

    /** Nothing changed: the same bytes were already stored, or it is a third version that is not among the two smallest. */
    data object Duplicate : Offered()
    data class Rejected(val why: Rejection) : Offered()
}

/**
 * What the sync engine needs from a phone's storage: a summary, the envelopes a peer lacks, and a way to offer envelopes that arrived.
 * An implementation verifies what it is offered (the group, the size and the signature) and never stores what fails.
 * The engine works on envelope bytes and never opens them.
 */
interface SyncStore {
    fun summary(): SyncSummary

    /** Every stored version of the author's events with a counter above [above], in counter order. */
    fun versionsAbove(author: PublicKey, above: Long): List<ByteArray>

    /** Every stored version of every event of the author (used when two phones disagree about them). */
    fun versionsOf(author: PublicKey): List<ByteArray> = versionsAbove(author, 0)

    /** The fingerprint at [counter], or null if the author's events are not held without a gap that far. */
    fun fingerprintAt(author: PublicKey, counter: Long): ByteArray?

    fun offer(bytes: ByteArray): Offered
}

/** The checks every store makes before storing an envelope that came from a peer. */
object EnvelopeCheck {
    const val MAX_BYTES = 64 * 1024

    sealed class Result {
        data class Valid(val envelope: Envelope) : Result()
        data class Bad(val why: Rejection) : Result()
    }

    fun check(bytes: ByteArray, group: GroupId): Result {
        if (bytes.size > MAX_BYTES) return Result.Bad(Rejection.TOO_LARGE)
        val envelope = try {
            Envelope.decode(bytes)
        } catch (e: DecodeException) {
            return Result.Bad(Rejection.MALFORMED)
        }
        if (envelope.groupId != group) return Result.Bad(Rejection.WRONG_GROUP)
        if (!EnvelopeSigning.verify(envelope)) return Result.Bad(Rejection.BAD_SIGNATURE)
        return Result.Valid(envelope)
    }
}

/** A store in memory, for the simulation and the tests. The phone's own store is built on the database (T5.8). */
class InMemorySyncStore(private val group: GroupId) : SyncStore {
    private class Author {
        val events = TreeMap<Long, MutableList<ByteArray>>()
        val fps = ArrayList<ByteArray>() // fps[c - 1] is the fingerprint at counter c, for the contiguous prefix
        fun prefix() = fps.size.toLong()
    }

    private val authors = TreeMap<PublicKey, Author>()

    private fun hash(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    @Synchronized
    override fun offer(bytes: ByteArray): Offered {
        val envelope = when (val c = EnvelopeCheck.check(bytes, group)) {
            is EnvelopeCheck.Result.Bad -> return Offered.Rejected(c.why)
            is EnvelopeCheck.Result.Valid -> c.envelope
        }
        return insert(envelope, bytes)
    }

    /**
     * Takes in an envelope that was already checked when it was first stored (for example, read back from the phone's own database),
     * without checking the signature again. Anything that does not decode or belongs to another group is ignored.
     */
    @Synchronized
    fun load(bytes: ByteArray) {
        val envelope = try { Envelope.decode(bytes) } catch (e: DecodeException) { return }
        if (envelope.groupId == group) insert(envelope, bytes)
    }

    private fun insert(envelope: Envelope, bytes: ByteArray): Offered {
        val a = authors.getOrPut(envelope.author) { Author() }
        val versions = a.events.getOrPut(envelope.counter) { ArrayList() }
        if (versions.any { it.contentEquals(bytes) }) return Offered.Duplicate
        val kept = Fingerprint.smallestTwo(versions + listOf(bytes.copyOf()))
        if (kept.none { it.contentEquals(bytes) }) return Offered.Duplicate
        versions.clear()
        versions.addAll(kept)
        // The chain from this counter on may have changed; rebuild it from here.
        while (a.fps.size >= envelope.counter) a.fps.removeAt(a.fps.size - 1)
        var c = a.fps.size.toLong() + 1
        while (true) {
            val v = a.events[c] ?: break
            a.fps += Fingerprint.next(if (a.fps.isEmpty()) Fingerprint.ZERO else a.fps.last(), c, v)
            c++
        }
        return Offered.Stored
    }

    @Synchronized
    override fun summary(): SyncSummary =
        SyncSummary(authors.filterValues { it.prefix() >= 1 }.mapValues { (_, a) -> AuthorState(a.prefix(), a.fps.last()) })

    @Synchronized
    override fun versionsAbove(author: PublicKey, above: Long): List<ByteArray> =
        authors[author]?.events?.tailMap(above, false)?.values?.flatten()?.map { it.copyOf() }.orEmpty()

    @Synchronized
    override fun fingerprintAt(author: PublicKey, counter: Long): ByteArray? {
        val a = authors[author] ?: return null
        return if (counter == 0L) Fingerprint.ZERO else a.fps.getOrNull((counter - 1).toInt())?.copyOf()
    }
}
