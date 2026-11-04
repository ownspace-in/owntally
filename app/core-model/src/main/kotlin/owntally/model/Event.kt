package owntally.model

enum class DisputeReason(val code: Int) {
    NOTHING_RECEIVED(0), DIFFERENT_AMOUNT(1), NOT_CHECKED(2);

    companion object {
        fun fromCode(code: Int) = values().firstOrNull { it.code == code }
    }
}

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

private fun checkEpoch(epoch: Long) = require(epoch in 1..0xFFFF_FFFFL) { "an epoch starts at 1 and fits 32 bits" }

private fun checkSealedKeys(keys: Map<PublicKey, SealedKey>) = require(keys.size <= 65_535) { "too many sealed keys" }

private fun checkName(name: String) =
    require(name.isNotBlank() && name.length in 1..40) { "name must be 1 to 40 characters" }

private fun checkPhone(phone: String) =
    require(phone.length in 5..20 && phone.all { it.isDigit() && it.code < 128 || it == '+' || it == ' ' }) {
        "phone must be 5 to 20 characters from digits, + and spaces"
    }

sealed class EventBody {
    data class GroupCreated(val groupId: GroupId, val name: String, val currency: String, val encryptionKey: EncryptionKey) : EventBody() {
        init {
            checkName(name)
            require(Regex("[A-Z]{3}").matches(currency)) { "currency must be a 3 letter upper case code" }
        }
    }

    data class MemberJoined(val member: PublicKey, val encryptionKey: EncryptionKey, val name: String, val phone: String) : EventBody() {
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
            require(total <= Money.MAX_AMOUNT) { "total is above the limit" }
            if (split is Split.Exact) require(split.sum() == total) { "exact amounts must add up to the total" }
        }
    }

    data class ExpenseDeleted(val expense: EventId) : EventBody()

    data class SettlementRecorded(val from: PublicKey, val to: PublicKey, val amount: Money) : EventBody() {
        init {
            require(from != to) { "a payment needs two different members" }
            require(amount.isPositive()) { "amount must be greater than 0" }
            require(amount <= Money.MAX_AMOUNT) { "amount is above the limit" }
        }
    }

    data class SettlementConfirmed(val settlement: EventId) : EventBody()
    data class PaymentDisputed(val settlement: EventId, val reason: DisputeReason) : EventBody()
    data class AdminAdded(val member: PublicKey) : EventBody()
    data class AdminRemoved(val member: PublicKey) : EventBody()
    data class OwnershipOffered(val to: PublicKey) : EventBody()
    data object OwnershipOfferCancelled : EventBody()
    data object OwnershipAccepted : EventBody()
    /**
     * Removes a member, or lets a member leave (when [member] is the author). Removal by someone else starts a new key
     * epoch and carries the new group key sealed to each remaining member; a leave carries the current epoch and no keys
     * (docs/CRYPTO.md section 6).
     */
    data class MemberRemoved(val member: PublicKey, val newEpoch: Long, val sealedKeys: Map<PublicKey, SealedKey>) : EventBody() {
        init {
            checkEpoch(newEpoch)
            checkSealedKeys(sealedKeys)
        }
    }

    /** A new group key epoch without removing anyone, written by an admin (docs/CRYPTO.md section 6). */
    data class KeyRotated(val newEpoch: Long, val sealedKeys: Map<PublicKey, SealedKey>) : EventBody() {
        init {
            checkEpoch(newEpoch)
            checkSealedKeys(sealedKeys)
        }
    }

    /** A type this version does not know. Its tag and content are kept so it can be passed on byte for byte. */
    class Unknown(val tag: Int, content: ByteArray) : EventBody() {
        private val data = content.copyOf()

        init {
            require(tag in 0..255) { "a tag is one byte" }
        }

        fun content(): ByteArray = data.copyOf()
        override fun equals(other: Any?) = other is Unknown && tag == other.tag && data.contentEquals(other.data)
        override fun hashCode() = 31 * tag + data.contentHashCode()
        override fun toString() = "Unknown(tag=$tag, ${data.size} bytes)"
    }
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
