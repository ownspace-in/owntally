package owntally.data.session

import owntally.crypto.Clock
import owntally.data.store.JoinRequest
import owntally.data.store.JoinRequestStore
import owntally.data.store.JoinState
import owntally.model.EncryptionKey
import owntally.model.GroupId
import owntally.model.MAX_MEMBERS
import owntally.model.PublicKey

enum class ReceiveResult { New, Updated, AlreadyMember }

sealed class ApproveResult {
    data class Approved(val id: owntally.model.EventId) : ApproveResult()

    /** There is no waiting request from that person. */
    data object NotWaiting : ApproveResult()

    /** The group already has the most people it can have. */
    data object GroupFull : ApproveResult()

    /** The group's rules or the name/phone check said no; the request keeps waiting. */
    data class Failed(val result: AddResult) : ApproveResult()
}

/**
 * People who asked to join, kept on this phone. A request is not an event; only the approval is, and it is written by the owner or
 * an admin through [GroupSession.approveJoin]. Declining writes nothing and tells nobody.
 */
class JoinRequests(private val store: JoinRequestStore, private val sessions: Sessions, private val clock: Clock) {
    /** A request arrived. The same person asking again replaces their earlier request; someone already in the group is ignored. */
    @Synchronized
    fun receive(group: GroupId, member: PublicKey, encryptionKey: EncryptionKey, name: String, phone: String): ReceiveResult {
        val earlier = store.get(group, member)
        if (earlier?.state == JoinState.APPROVED && member in sessions.session(group).view.value.state.currentMembers) return ReceiveResult.AlreadyMember
        store.put(JoinRequest(group, member, encryptionKey, name, phone, clock.nowSeconds(), JoinState.WAITING))
        return if (earlier?.state == JoinState.WAITING) ReceiveResult.Updated else ReceiveResult.New
    }

    fun waiting(group: GroupId): List<JoinRequest> = store.list(group).filter { it.state == JoinState.WAITING }

    /** Admits the person under [name] (the admin may change what they typed). */
    @Synchronized
    fun approve(group: GroupId, member: PublicKey, name: String): ApproveResult {
        val request = store.get(group, member)?.takeIf { it.state == JoinState.WAITING } ?: return ApproveResult.NotWaiting
        val session = sessions.session(group)
        if (session.view.value.state.currentMembers.size >= MAX_MEMBERS) return ApproveResult.GroupFull
        return when (val r = session.approveJoin(member, request.encryptionKey, name, request.phone)) {
            is AddResult.Added -> {
                store.setState(group, member, JoinState.APPROVED)
                ApproveResult.Approved(r.id)
            }
            else -> ApproveResult.Failed(r)
        }
    }

    /** Marks a waiting request declined on this phone. Nothing is sent. */
    @Synchronized
    fun decline(group: GroupId, member: PublicKey) {
        if (store.get(group, member)?.state == JoinState.WAITING) store.setState(group, member, JoinState.DECLINED)
    }
}
