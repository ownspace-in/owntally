package owntally.data.session

import owntally.crypto.Clock
import owntally.data.store.ProblemKind
import owntally.data.store.SyncProblem
import owntally.data.store.SyncProblemLog
import owntally.model.GroupId
import owntally.model.PublicKey
import owntally.sync.ErrorCode
import owntally.sync.Rejection
import owntally.sync.SyncOutcome
import owntally.sync.SyncResult

/** The one notice a sync run produces when envelopes were dropped (decision S4): who sent them, how many, of which kinds. No content. */
data class RunNotice(val peer: PublicKey, val total: Int, val kinds: Map<ProblemKind, Int>, val cutOff: Boolean)

/**
 * Writes what a sync run dropped into the phone's log, one line per kind, and returns the single notice for the run (or null if nothing
 * was dropped). The Sync screen shows the log's latest lines as a note and the notice once, however many envelopes were dropped.
 */
class SyncRecorder(private val log: SyncProblemLog, private val clock: Clock) {
    fun record(group: GroupId, peer: PublicKey, result: SyncResult): RunNotice? {
        val kinds = LinkedHashMap<ProblemKind, Int>()
        for ((why, count) in result.rejected) kinds.merge(kindOf(why), count, Int::plus)
        val cutOff = result.outcome == SyncOutcome.REFUSED && result.error == ErrorCode.TOO_MANY_BAD_ENVELOPES
        if (cutOff) kinds[ProblemKind.CUT_OFF] = 1
        if (kinds.isEmpty()) return null
        val now = clock.nowSeconds()
        for ((kind, count) in kinds) log.add(SyncProblem(group, peer, kind, count, now))
        return RunNotice(peer, kinds.filterKeys { it != ProblemKind.CUT_OFF }.values.sum(), kinds.filterKeys { it != ProblemKind.CUT_OFF }, cutOff)
    }

    /** The newest lines of the log for a group, for the note on the Sync screen. */
    fun recent(group: GroupId, limit: Int): List<SyncProblem> = log.recent(group, limit)

    private fun kindOf(why: Rejection) = when (why) {
        Rejection.MALFORMED -> ProblemKind.MALFORMED
        Rejection.WRONG_GROUP -> ProblemKind.WRONG_GROUP
        Rejection.TOO_LARGE -> ProblemKind.TOO_LARGE
        Rejection.BAD_SIGNATURE -> ProblemKind.BAD_SIGNATURE
    }
}
