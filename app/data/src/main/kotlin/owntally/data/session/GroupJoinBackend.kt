package owntally.data.session

import owntally.crypto.GroupIdentity
import owntally.crypto.Ticket
import owntally.data.store.JoinState
import owntally.model.EventBody
import owntally.model.GroupId
import owntally.model.PublicKey
import owntally.sync.GroupPreview
import owntally.crypto.KeyRotation
import owntally.model.Member
import owntally.sync.EpochKey
import owntally.sync.JoinHostBackend
import owntally.sync.JoinLimits
import owntally.sync.JoinRequestBody
import owntally.sync.JoinStatus
import owntally.sync.JoinStatusAnswer

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
    override fun issued(ticketId: ByteArray): Ticket? = invites.registry(group)?.find(ticketId)?.takeIf { invites.isForCurrentKey(group, it) } // a key change cancels earlier invitations (N2)

    override fun consume(ticket: Ticket): Boolean = invites.isForCurrentKey(group, ticket) && invites.registry(group)?.accept(ticket) == true

    /** False for a name or number the group would not accept, and for someone who is already a member. */
    override fun submit(request: JoinRequestBody): Boolean {
        if (runCatching { EventBody.ProfileUpdated(request.name.trim(), request.phone.trim()) }.isFailure) return false
        return joinRequests.receive(group, request.joinerKey, request.encryptionKey, request.name.trim(), request.phone.trim()) != ReceiveResult.AlreadyMember
    }

    /**
     * An approved member is sent every epoch key this phone holds, each sealed to that member's encryption key (R7: the joiner trusts the host for
     * these exactly as for the current key in the ticket). Anyone else gets only a status.
     */
    override fun statusOf(joinerKey: PublicKey): JoinStatusAnswer {
        val state = sessions.session(group).view.value.state
        state.members[joinerKey]?.let { member ->
            return if (member.removed) JoinStatusAnswer(JoinStatus.DECLINED) else JoinStatusAnswer(JoinStatus.APPROVED, chainKeysFor(member))
        }
        val status = when (joinRequests.stateOf(group, joinerKey)) {
            JoinState.WAITING -> JoinStatus.PENDING
            JoinState.APPROVED -> JoinStatus.APPROVED
            JoinState.DECLINED -> JoinStatus.DECLINED
            null -> JoinStatus.UNKNOWN
        }
        return JoinStatusAnswer(status)
    }

    private fun chainKeysFor(member: Member): List<EpochKey> {
        val keyring = sessions.epochKeys(group)
        return keyring.epochs().take(JoinLimits.MAX_CHAIN_KEYS).map { epoch ->
            EpochKey(epoch, KeyRotation.sealChainKey(host.publicKey, group, epoch, keyring[epoch]!!, member.encryptionKey))
        }
    }

    override fun preview(): GroupPreview {
        val state = sessions.session(group).view.value.state
        return GroupPreview(state.name ?: "Group", state.owner?.let { state.members[it]?.name }.orEmpty(), state.currentMembers.size)
    }
}
