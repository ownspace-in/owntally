package owntally.data.store

import owntally.model.GroupId
import owntally.model.PublicKey

/** What was wrong with envelopes a peer sent, or what was done about the peer. Never the envelope itself. */
enum class ProblemKind { MALFORMED, WRONG_GROUP, TOO_LARGE, BAD_SIGNATURE, CUT_OFF }

/**
 * One line of the log of dropped envelopes (decision S4): which group, which peer, what kind, how many in that sync, and when.
 * There is deliberately no field that could hold any part of an envelope.
 */
data class SyncProblem(val group: GroupId, val peer: PublicKey, val kind: ProblemKind, val count: Int, val atSeconds: Long) {
    init {
        require(count >= 1) { "a problem line counts at least one envelope" }
    }
}

/** The phone's log of dropped envelopes. Only the latest lines of each group are kept. */
interface SyncProblemLog {
    fun add(problem: SyncProblem)

    /** The newest first. */
    fun recent(group: GroupId, limit: Int): List<SyncProblem>

    fun clear(group: GroupId)
}

/** For tests and previews. */
class InMemorySyncProblemLog(private val maxPerGroup: Int = DEFAULT_MAX) : SyncProblemLog {
    private val rows = ArrayList<SyncProblem>()

    @Synchronized
    override fun add(problem: SyncProblem) {
        rows += problem
        val mine = rows.filter { it.group == problem.group }
        if (mine.size > maxPerGroup) rows.remove(mine.first())
    }

    @Synchronized
    override fun recent(group: GroupId, limit: Int): List<SyncProblem> = rows.filter { it.group == group }.asReversed().take(limit)

    @Synchronized
    override fun clear(group: GroupId) {
        rows.removeAll { it.group == group }
    }

    companion object {
        const val DEFAULT_MAX = 200
    }
}
