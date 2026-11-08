package owntally.data.store

import owntally.model.GroupId
import owntally.model.PublicKey

/**
 * Where an invitation this phone opened stands. OPENED: nothing sent yet (the person may be typing their name); WAITING: the host has
 * the request and its person has not answered; APPROVED: admitted, the history is on its way; DECLINED: the host said no.
 */
enum class PendingState { OPENED, WAITING, APPROVED, DECLINED }

/** An invitation this phone opened. The group key is kept with the other keys (so there is no second copy of it here). */
data class PendingJoin(
    val group: GroupId,
    val host: PublicKey,
    val ticketId: ByteArray,
    val keySlot: Long,
    val epoch: Long,
    val expiresAtSeconds: Long,
    val name: String,
    val phone: String,
    val state: PendingState,
    /** What the host told us when the invitation was opened (decision J1); empty if it could not be reached then. */
    val groupName: String,
    val ownerName: String,
    val memberCount: Int,
    val openedAtSeconds: Long,
) {
    override fun equals(other: Any?) = other is PendingJoin && group == other.group && ticketId.contentEquals(other.ticketId) && state == other.state && name == other.name && phone == other.phone && keySlot == other.keySlot
    override fun hashCode() = group.hashCode()
    override fun toString() = "PendingJoin($group, $state)"
}

interface PendingJoinStore {
    fun put(p: PendingJoin)
    fun get(group: GroupId): PendingJoin?

    /** Oldest first. */
    fun all(): List<PendingJoin>
    fun delete(group: GroupId)
}

class InMemoryPendingJoinStore : PendingJoinStore {
    private val rows = LinkedHashMap<GroupId, PendingJoin>()
    @Synchronized override fun put(p: PendingJoin) { rows[p.group] = p }
    @Synchronized override fun get(group: GroupId) = rows[group]
    @Synchronized override fun all() = rows.values.sortedBy { it.openedAtSeconds }
    @Synchronized override fun delete(group: GroupId) { rows.remove(group) }
}
