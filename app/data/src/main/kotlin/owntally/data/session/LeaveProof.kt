package owntally.data.session

import owntally.model.GroupState
import owntally.model.PublicKey
import owntally.sync.SyncOutcome
import owntally.sync.SyncResult

/**
 * Whether a sync proves that a member has this person's leave (D4). Only a sync that finished counts: the member's closing summary, sent
 * after it stored what we sent, shows our own events up to the leave. A summary a member opened with, or sent and then went silent, is a
 * claim and proves nothing; neither does a sync with anyone who is not a member as this phone knows the group.
 */
object LeaveProof {
    fun delivered(state: GroupState, me: PublicKey, leaveCounter: Long, peer: PublicKey, result: SyncResult): Boolean {
        if (peer == me || peer !in state.currentMembers) return false
        if (result.outcome != SyncOutcome.COMPLETED) return false
        val shown = result.peerFinal?.entries?.get(me)?.counter ?: return false
        return shown >= leaveCounter
    }
}
