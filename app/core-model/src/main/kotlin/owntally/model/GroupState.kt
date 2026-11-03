package owntally.model

/** Why an event stayed in the log without any effect (PROTOCOL.md section 4). */
enum class IgnoreReason { NOT_MEMBER, NOT_ALLOWED, INVALID, UNKNOWN_TARGET, ALREADY_DONE, AFTER_REMOVAL, DUPLICATE_ID, UNKNOWN_TYPE }

enum class SettlementStatus { PAID_MARKED, CONFIRMED, DISPUTED }

data class Member(val key: PublicKey, val name: String, val phone: String, val role: Role, val removed: Boolean, val encryptionKey: EncryptionKey)

data class Expense(
    val id: EventId,
    val creator: PublicKey,
    val description: String,
    val total: Money,
    val paidBy: PublicKey,
    val day: Long,
    val split: Split,
    val deleted: Boolean,
    val deletedBy: PublicKey?,
)

data class Settlement(
    val id: EventId,
    val from: PublicKey,
    val to: PublicKey,
    val amount: Money,
    val status: SettlementStatus,
    val disputeReason: DisputeReason?,
)

/** [lastSeenCounter] is the highest counter of the member's that the remover had seen. Newer ones are ignored. */
data class Removal(val member: PublicKey, val by: PublicKey, val event: EventId, val lastSeenCounter: Long)

data class IgnoredEvent(val id: EventId, val reason: IgnoreReason)

/** The result of replaying a log. Everything a screen or a balance needs; nothing here depends on arrival order. */
data class GroupState(
    val groupId: GroupId?,
    val name: String?,
    val currency: String?,
    /** Everyone who ever joined, including removed members (kept for history). */
    val members: Map<PublicKey, Member>,
    val owner: PublicKey?,
    val pendingOffer: PublicKey?,
    /** The current group key epoch: 1 after the group is created, +1 for each rotation. 0 before the group exists. */
    val epoch: Long,
    /** All expenses, deleted ones included (they stay in history). */
    val expenses: Map<EventId, Expense>,
    val settlements: Map<EventId, Settlement>,
    val removals: Map<PublicKey, Removal>,
    /** Events that had no effect, in application order. */
    val ignored: List<IgnoredEvent>,
    /** Events waiting for something they depend on. */
    val heldBack: List<EventId>,
) {
    val currentMembers: Map<PublicKey, Member> get() = members.filterValues { !it.removed }
}
