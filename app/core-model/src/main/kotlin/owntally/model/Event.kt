package owntally.model

enum class DisputeReason { NOTHING_RECEIVED, DIFFERENT_AMOUNT, NOT_CHECKED }

/** How an expense is shared. */
sealed class Split {
    /** Everyone listed pays the same share (see PROTOCOL.md section 5 for the rounding). */
    data class Equal(val participants: Set<PublicKey>) : Split() {
        init {
            require(participants.isNotEmpty()) { "an equal split needs at least one participant" }
        }
    }

    /** Each participant pays the listed amount. */
    data class Exact(val amounts: Map<PublicKey, Money>) : Split() {
        init {
            require(amounts.isNotEmpty()) { "an exact split needs at least one participant" }
            require(amounts.values.all { it.isPositive() }) { "exact amounts must be greater than 0" }
        }

        /** Throws ArithmeticException on overflow, so a huge split can never wrap around to a valid total. */
        fun sum(): Money = amounts.values.fold(Money.ZERO) { acc, m -> acc + m }
    }

    val participantKeys: Set<PublicKey>
        get() = when (this) {
            is Equal -> participants
            is Exact -> amounts.keys
        }
}

private fun checkName(name: String) =
    require(name.isNotBlank() && name.length in 1..40) { "name must be 1 to 40 characters" }

private fun checkPhone(phone: String) =
    require(phone.length in 5..20 && phone.all { it.isDigit() && it.code < 128 || it == '+' || it == ' ' }) {
        "phone must be 5 to 20 characters from digits, + and spaces"
    }

sealed class EventBody {
    data class GroupCreated(val groupId: GroupId, val name: String, val currency: String) : EventBody() {
        init {
            checkName(name)
            require(Regex("[A-Z]{3}").matches(currency)) { "currency must be a 3 letter upper case code" }
        }
    }

    data class MemberJoined(val member: PublicKey, val name: String, val phone: String) : EventBody() {
        init {
            checkName(name)
            checkPhone(phone)
        }
    }

    data class ProfileUpdated(val name: String, val phone: String) : EventBody() {
        init {
            checkName(name)
            checkPhone(phone)
        }
    }

    data class ExpenseAdded(
        val description: String,
        val total: Money,
        val paidBy: PublicKey,
        /** Calendar day as days since 1970-01-01. */
        val day: Long,
        val split: Split,
    ) : EventBody() {
        init {
            require(description.isNotBlank() && description.length in 1..80) { "description must be 1 to 80 characters" }
            require(total.isPositive()) { "total must be greater than 0" }
            if (split is Split.Exact) require(split.sum() == total) { "exact amounts must add up to the total" }
        }
    }

    data class ExpenseDeleted(val expense: EventId) : EventBody()

    data class SettlementRecorded(val from: PublicKey, val to: PublicKey, val amount: Money) : EventBody() {
        init {
            require(from != to) { "a payment needs two different members" }
            require(amount.isPositive()) { "amount must be greater than 0" }
        }
    }

    data class SettlementConfirmed(val settlement: EventId) : EventBody()
    data class PaymentDisputed(val settlement: EventId, val reason: DisputeReason) : EventBody()
    data class AdminAdded(val member: PublicKey) : EventBody()
    data class AdminRemoved(val member: PublicKey) : EventBody()
    data class OwnershipOffered(val to: PublicKey) : EventBody()
    data object OwnershipOfferCancelled : EventBody()
    data object OwnershipAccepted : EventBody()
    data class MemberRemoved(val member: PublicKey) : EventBody()

    /** A type this version does not know. Kept in the log, no effect (PROTOCOL.md section 3). */
    data class Unknown(val type: String) : EventBody()
}

/**
 * One signed entry in a group's log. [after] says, per author, the highest counter this author had seen when writing it,
 * including the author's own previous counter.
 */
class Event(
    val version: Int,
    val author: PublicKey,
    val counter: Long,
    after: Map<PublicKey, Long>,
    /** Wall clock milliseconds. Display only, never used by a rule. */
    val time: Long,
    val body: EventBody,
) {
    val after: Map<PublicKey, Long> = after.toMap()
    val id: EventId = EventId(author, counter)

    init {
        require(version >= 1) { "version starts at 1" }
        require(this.after.values.all { it >= 1 }) { "after counters start at 1" }
        require((this.after[author] ?: 0L) == counter - 1) { "after must include the author's own previous counter" }
    }

    /** Events that must be present before this one can be applied. */
    fun dependencies(): Set<EventId> = after.map { (k, c) -> EventId(k, c) }.toSet()

    override fun equals(other: Any?) = other is Event && version == other.version && author == other.author &&
        counter == other.counter && after == other.after && time == other.time && body == other.body

    override fun hashCode() = listOf(version, author, counter, after, time, body).hashCode()
    override fun toString() = "Event(${author}#$counter $body)"
}
