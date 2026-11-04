package owntally.crypto

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.CRC32
import owntally.model.GroupId
import owntally.model.PublicKey

/** Seconds since 1970 as the phone sees them. Tests supply their own. */
fun interface Clock {
    fun nowSeconds(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() / 1000 }
    }
}

/**
 * An invitation (docs/CRYPTO.md section 7). It carries the group key, so it is a secret: anyone who sees the text can join
 * until it is used or expires. [hostId] is the transport key of the phone that approves the join. [expiresAtSeconds] is the
 * first second at which the ticket no longer works.
 */
class Ticket(
    ticketId: ByteArray,
    val hostId: PublicKey,
    val groupId: GroupId,
    val epoch: Long,
    val groupKey: GroupKey,
    val expiresAtSeconds: Long,
    val hint: String,
) {
    private val id = ticketId.copyOf()

    init {
        require(id.size == ID_SIZE) { "a ticket id is $ID_SIZE bytes" }
        require(epoch in 1..0xFFFF_FFFFL) { "an epoch starts at 1 and fits 32 bits" }
        require(expiresAtSeconds >= 0) { "expiry is a time in seconds since 1970" }
        require(hint.toByteArray(Charsets.UTF_8).size <= MAX_HINT_BYTES) { "the hint is at most $MAX_HINT_BYTES bytes" }
    }

    fun ticketId(): ByteArray = id.copyOf()
    fun isExpired(nowSeconds: Long) = nowSeconds >= expiresAtSeconds

    /** The text people share: URL-safe base64 without padding. */
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes())

    private fun bytes(): ByteArray {
        val hintBytes = hint.toByteArray(Charsets.UTF_8)
        val body = ByteBuffer.allocate(BASE_SIZE + hintBytes.size)
            .put(VERSION.toByte()).put(id).put(hostId.bytes()).put(groupId.bytes()).putInt(epoch.toInt())
            .put(groupKey.bytes()).putLong(expiresAtSeconds).put(hintBytes.size.toByte()).put(hintBytes).array()
        return body + ByteBuffer.allocate(4).putInt(crc(body, body.size).toInt()).array()
    }

    override fun equals(other: Any?) = other is Ticket && MessageDigest.isEqual(bytes(), other.bytes())
    override fun hashCode() = 0
    override fun toString() = "Ticket([redacted])"

    companion object {
        const val LIFETIME_SECONDS = 8 * 3600L
        const val ID_SIZE = 16
        const val MAX_HINT_BYTES = 100
        private const val VERSION = 1
        private const val HINT_LENGTH_AT = 1 + ID_SIZE + 32 + 16 + 4 + 32 + 8
        private const val BASE_SIZE = HINT_LENGTH_AT + 1 // everything before the hint

        private fun crc(bytes: ByteArray, length: Int): Long = CRC32().also { it.update(bytes, 0, length) }.value

        /** The ticket in [text], or null if it is malformed, altered, or in any way not exactly what [encode] writes. */
        fun decode(text: String): Ticket? {
            val bytes = try {
                Base64.getUrlDecoder().decode(text)
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != text) return null // padding, spare bits
            if (bytes.size < BASE_SIZE + 4 || bytes[0].toInt() != VERSION) return null
            val hintLength = bytes[HINT_LENGTH_AT].toInt() and 0xFF
            if (hintLength > MAX_HINT_BYTES || bytes.size != BASE_SIZE + hintLength + 4) return null
            val buffer = ByteBuffer.wrap(bytes)
            if ((buffer.getInt(bytes.size - 4).toLong() and 0xFFFF_FFFFL) != crc(bytes, bytes.size - 4)) return null
            buffer.position(1)
            val ticketId = ByteArray(ID_SIZE).also { buffer.get(it) }
            val host = ByteArray(32).also { buffer.get(it) }
            val group = ByteArray(16).also { buffer.get(it) }
            val epoch = buffer.getInt().toLong() and 0xFFFF_FFFFL
            val key = ByteArray(32).also { buffer.get(it) }
            val expires = buffer.getLong()
            buffer.get() // hint length, read above
            val hintBytes = ByteArray(hintLength).also { buffer.get(it) }
            val hint = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(hintBytes)).toString()
            } catch (e: CharacterCodingException) {
                return null
            }
            return try {
                Ticket(ticketId, PublicKey(host), GroupId(group), epoch, GroupKey(key), expires, hint)
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        /** A ticket from shared text: the bare ticket, or a link whose part after `#` is the ticket. Spaces around it are ignored. */
        /**
         * A ticket from what a person pasted: a link or the bare text. Spaces, line breaks and invisible characters anywhere are ignored
         * first (a chat app may wrap or space a long text; owner decision, 4 Nov 2026), then the part after the last "#" is decoded by
         * [decode], which still accepts exactly one text per ticket.
         */
        fun fromShared(text: String): Ticket? =
            decode(text.filterNot { it.isWhitespace() || Character.isSpaceChar(it) || Character.getType(it) == Character.FORMAT.toInt() }.substringAfterLast('#'))
    }
}

/**
 * The host phone's record of the tickets it has handed out. A ticket is accepted once, before it expires, and only if this
 * host issued exactly this ticket. The answer is a plain yes or no, so a joiner cannot tell why a ticket failed. The records
 * live in memory; the storage milestone keeps them across restarts.
 */
class TicketRegistry(private val hostId: PublicKey, private val clock: Clock = Clock.SYSTEM, private val random: SecureRandom = SecureRandom()) {
    private val outstanding = HashMap<String, Ticket>()

    @Synchronized
    fun issue(groupId: GroupId, epoch: Long, groupKey: GroupKey, hint: String = ""): Ticket {
        val now = clock.nowSeconds()
        outstanding.values.removeAll { it.isExpired(now) }
        val ticket = Ticket(ByteArray(Ticket.ID_SIZE).also { random.nextBytes(it) }, hostId, groupId, epoch, groupKey, now + Ticket.LIFETIME_SECONDS, hint)
        outstanding[idKey(ticket)] = ticket
        return ticket
    }

    /** True exactly once for a ticket this host issued, if it has not expired. */
    @Synchronized
    fun accept(ticket: Ticket): Boolean {
        val issued = outstanding[idKey(ticket)] ?: return false
        if (issued != ticket || ticket.hostId != hostId || ticket.isExpired(clock.nowSeconds())) return false
        outstanding.remove(idKey(ticket))
        return true
    }

    private fun idKey(ticket: Ticket) = ticket.ticketId().joinToString("") { "%02x".format(it) }
    override fun toString() = "TicketRegistry([redacted])"
}
