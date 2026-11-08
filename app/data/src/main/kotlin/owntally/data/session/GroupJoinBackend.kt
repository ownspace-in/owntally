package owntally.data.session

import owntally.crypto.GroupIdentity
import owntally.crypto.Ticket
import owntally.data.store.JoinState
import owntally.model.EventBody
import owntally.model.GroupId
import owntally.model.PublicKey
import owntally.sync.GroupPreview
import owntally.sync.JoinHostBackend
import owntally.sync.JoinRequestBody
import owntally.sync.JoinStatus

/**
 * What a host phone gives the join protocol for one group (M6, T6.5 and T6.6): its tickets, the join requests list, and what the group
 * looks like. A request that arrives becomes a waiting entry on the Join requests screen; approving it is the owner's or an admin's act.
 */
class GroupJoinBackend(
    override val group: GroupId,
    override val host: GroupIdentity,
    private val invites: Invites,
    private val joinRequests: JoinRequests,
    private val sessions: Sessions,
) : JoinHostBackend {
    override fun issued(ticketId: ByteArray): Ticket? = invites.registry(group)?.find(ticketId)

    override fun consume(ticket: Ticket): Boolean = invites.registry(group)?.accept(ticket) == true

    /** False for a name or number the group would not accept, and for someone who is already a member. */
    override fun submit(request: JoinRequestBody): Boolean {
        if (runCatching { EventBody.ProfileUpdated(request.name.trim(), request.phone.trim()) }.isFailure) return false
        return joinRequests.receive(group, request.joinerKey, request.encryptionKey, request.name.trim(), request.phone.trim()) != ReceiveResult.AlreadyMember
    }

    override fun statusOf(joinerKey: PublicKey): JoinStatus {
        val state = sessions.session(group).view.value.state
        state.members[joinerKey]?.let { return if (it.removed) JoinStatus.DECLINED else JoinStatus.APPROVED }
        return when (joinRequests.stateOf(group, joinerKey)) {
            JoinState.WAITING -> JoinStatus.PENDING
            JoinState.APPROVED -> JoinStatus.APPROVED
            JoinState.DECLINED -> JoinStatus.DECLINED
            null -> JoinStatus.UNKNOWN
        }
    }

    override fun preview(): GroupPreview {
        val state = sessions.session(group).view.value.state
        return GroupPreview(state.name ?: "Group", state.owner?.let { state.members[it]?.name }.orEmpty(), state.currentMembers.size)
    }
}
