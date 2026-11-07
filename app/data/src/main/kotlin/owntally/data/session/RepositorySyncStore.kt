package owntally.data.session

import owntally.data.repo.AppendResult
import owntally.data.repo.GroupRepository
import owntally.data.repo.RejectedBecause
import owntally.model.Envelope
import owntally.model.GroupId
import owntally.model.PublicKey
import owntally.sync.InMemorySyncStore
import owntally.sync.Offered
import owntally.sync.Rejection
import owntally.sync.SyncStore
import owntally.sync.SyncSummary

/**
 * A phone's group as the sync engine sees it: the stored envelopes are read once when this object is made (so make one for each sync),
 * and every envelope a peer offers is checked and, if new, stored in the database at once. Only what was stored when the sync started and
 * what the sync itself stores is visible to it; entries the person writes during the sync are sent by the next one.
 *
 * Several different versions of one event may be in the database (two phones can each hold one, WIRE_FORMAT.md section 6); the engine
 * sees the two smallest, and the rules treat two or more alike, so every phone ends with the same result.
 */
class RepositorySyncStore(private val group: GroupId, private val repo: GroupRepository) : SyncStore {
    private val view = InMemorySyncStore(group)

    init {
        repo.rawEnvelopes(group).forEach { view.load(it) }
    }

    override fun summary(): SyncSummary = view.summary()
    override fun versionsAbove(author: PublicKey, above: Long): List<ByteArray> = view.versionsAbove(author, above)
    override fun fingerprintAt(author: PublicKey, counter: Long): ByteArray? = view.fingerprintAt(author, counter)

    @Synchronized
    override fun offer(bytes: ByteArray): Offered {
        val result = view.offer(bytes)
        if (result != Offered.Stored) return result
        val envelope = Envelope.decode(bytes) // already checked by the view
        return when (val stored = repo.appendEnvelope(group, envelope)) {
            AppendResult.Added, AppendResult.Duplicate -> Offered.Stored
            is AppendResult.Rejected -> Offered.Rejected(
                when (stored.because) {
                    RejectedBecause.UNKNOWN_GROUP, RejectedBecause.WRONG_GROUP -> Rejection.WRONG_GROUP
                    RejectedBecause.TOO_LARGE -> Rejection.TOO_LARGE
                    RejectedBecause.BAD_SIGNATURE -> Rejection.BAD_SIGNATURE
                },
            )
        }
    }
}
