package owntally.data.store

import owntally.model.EncryptionKey
import owntally.model.GroupId
import owntally.model.PublicKey

enum class JoinState { WAITING, APPROVED, DECLINED }

/**
 * Someone who asked to join a group, as seen by the phone that received the request. Local to this phone: the request is not an
 * event, only the approval is. [name] and [phone] are what the person typed.
 */
data class JoinRequest(
    val groupId: GroupId,
    val member: PublicKey,
    val encryptionKey: EncryptionKey,
    val name: String,
    val phone: String,
    val receivedAtSeconds: Long,
    val state: JoinState,
)

/** Where join requests are kept. One request per group and member key. */
interface JoinRequestStore {
    /** Adds the request, or replaces the one with the same group and member key. */
    fun put(request: JoinRequest)
    fun get(groupId: GroupId, member: PublicKey): JoinRequest?

    /** Oldest first. */
    fun list(groupId: GroupId): List<JoinRequest>
    fun setState(groupId: GroupId, member: PublicKey, state: JoinState)
}

/** For tests and previews. */
class InMemoryJoinRequestStore : JoinRequestStore {
    private val rows = LinkedHashMap<Pair<GroupId, PublicKey>, JoinRequest>()

    @Synchronized
    override fun put(request: JoinRequest) {
        rows[request.groupId to request.member] = request
    }

    @Synchronized
    override fun get(groupId: GroupId, member: PublicKey): JoinRequest? = rows[groupId to member]

    @Synchronized
    override fun list(groupId: GroupId): List<JoinRequest> = rows.values.filter { it.groupId == groupId }.sortedBy { it.receivedAtSeconds }

    @Synchronized
    override fun setState(groupId: GroupId, member: PublicKey, state: JoinState) {
        rows[groupId to member]?.let { rows[groupId to member] = it.copy(state = state) }
    }
}
