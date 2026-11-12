package owntally.model

/** An amount in paise (1 rupee = 100 paise). Negative values are balances: negative means the member owes. */
@JvmInline
value class Money(val paise: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(Math.addExact(paise, other.paise))
    operator fun minus(other: Money) = Money(Math.subtractExact(paise, other.paise))
    operator fun unaryMinus() = Money(Math.negateExact(paise))
    override fun compareTo(other: Money) = paise.compareTo(other.paise)
    fun isPositive() = paise > 0
    fun isNegative() = paise < 0
    override fun toString() = "Money($paise)"

    companion object {
        val ZERO = Money(0)

        /**
         * The largest amount of one expense or payment: 100,000,000 in whole currency units, whatever the currency (owner decision,
         * 4 Nov 2026), which is 10,000,000,000 paise. Without a limit a member could write amounts so large that adding them up
         * overflows and fails on every phone.
         */
        val MAX_AMOUNT = Money(10_000_000_000)
    }
}

/** Raw bytes that compare, hash and sort as unsigned bytes. */
sealed class ByteId(bytes: ByteArray, size: Int) {
    private val data: ByteArray

    init {
        require(bytes.size == size) { "expected $size bytes, got ${bytes.size}" }
        data = bytes.copyOf()
    }

    fun bytes(): ByteArray = data.copyOf()
    fun toHex(): String = data.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    protected fun compareBytes(other: ByteId): Int {
        for (i in data.indices) {
            val c = (data[i].toInt() and 0xff).compareTo(other.data[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return 0
    }

    final override fun equals(other: Any?) = other != null && other::class == this::class && data.contentEquals((other as ByteId).data)
    final override fun hashCode() = data.contentHashCode()
    override fun toString() = "${this::class.simpleName}(${toHex().take(8)})"

    companion object {
        internal fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "not hex" }
            return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}

/** A member's 32-byte public key. */
class PublicKey(bytes: ByteArray) : ByteId(bytes, 32), Comparable<PublicKey> {
    override fun compareTo(other: PublicKey) = compareBytes(other)

    companion object {
        fun fromHex(hex: String) = PublicKey(hexToBytes(hex))
    }
}

/** A member's 32-byte X25519 public key, used to seal the group key to them (docs/CRYPTO.md section 2). */
class EncryptionKey(bytes: ByteArray) : ByteId(bytes, 32) {
    companion object {
        fun fromHex(hex: String) = EncryptionKey(hexToBytes(hex))
    }
}

/** A group key sealed to one member (an HPKE output). Opaque to the core: only the crypto module opens it. */
class SealedKey(bytes: ByteArray) {
    private val data: ByteArray

    init {
        require(bytes.size in 1..65_535) { "a sealed key is 1 to 65535 bytes" }
        data = bytes.copyOf()
    }

    fun bytes(): ByteArray = data.copyOf()
    override fun equals(other: Any?) = other is SealedKey && data.contentEquals(other.data)
    override fun hashCode() = data.contentHashCode()
    override fun toString() = "SealedKey(${data.size} bytes)"
}

/** A group's random 16-byte id. */
class GroupId(bytes: ByteArray) : ByteId(bytes, 16) {
    companion object {
        /** Reads 32 hex digits, or returns null (for example for an id that came from a navigation argument). */
        fun fromHexOrNull(hex: String): GroupId? = if (hex.length == 32) try { GroupId(hexToBytes(hex)) } catch (e: IllegalArgumentException) { null } else null
    }
}

/** An event is identified by its author and the author's counter, which starts at 1. */
data class EventId(val author: PublicKey, val counter: Long) : Comparable<EventId> {
    init {
        require(counter >= 1) { "counter starts at 1" }
    }

    override fun compareTo(other: EventId): Int {
        val c = author.compareTo(other.author)
        return if (c != 0) c else counter.compareTo(other.counter)
    }
}

enum class Role {
    OWNER, ADMIN, MEMBER;

    /** The owner is also an admin. */
    val isAdmin: Boolean get() = this != MEMBER
}
