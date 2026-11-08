package owntally.sync

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import owntally.model.EncryptionKey
import owntally.model.PublicKey

/** Limits of the join messages (docs/WIRE_FORMAT.md, join handshake). */
object JoinLimits {
    const val TICKET_ID_SIZE = 16
    const val NONCE_SIZE = 32
    const val SIGNATURE_SIZE = 64
    const val MAX_NAME_BYTES = 160 // 40 letters of up to 4 bytes
    const val MAX_PHONE_BYTES = 64
    const val MAX_SEALED_BYTES = 1024
}

/** What the host knows of a request, told to the person who made it. */
enum class JoinStatus(val code: Int) {
    UNKNOWN(0), PENDING(1), APPROVED(2), DECLINED(3);

    companion object {
        fun fromCode(code: Int): JoinStatus? = entries.firstOrNull { it.code == code }
    }
}

/** The request the joiner makes: who they are, the keys they will use in this group, and the name and number they typed. */
class JoinRequestBody(val joinerKey: PublicKey, val encryptionKey: EncryptionKey, val name: String, val phone: String) {
    fun encode(): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val p = phone.toByteArray(Charsets.UTF_8)
        require(n.size <= JoinLimits.MAX_NAME_BYTES && p.size <= JoinLimits.MAX_PHONE_BYTES) { "name or phone too long" }
        val b = ByteBuffer.allocate(64 + 2 + n.size + 2 + p.size)
        b.put(joinerKey.bytes()).put(encryptionKey.bytes()).putShort(n.size.toShort()).put(n).putShort(p.size.toShort()).put(p)
        return b.array()
    }

    companion object {
        /** Null for anything that is not exactly what [encode] makes. */
        fun decode(bytes: ByteArray): JoinRequestBody? {
            try {
                val b = ByteBuffer.wrap(bytes)
                if (b.remaining() < 64 + 2) return null
                val key = ByteArray(32).also { b.get(it) }
                val enc = ByteArray(32).also { b.get(it) }
                fun text(max: Int): String? {
                    if (b.remaining() < 2) return null
                    val len = b.short.toInt() and 0xffff
                    if (len > max || b.remaining() < len) return null
                    val raw = ByteArray(len).also { b.get(it) }
                    return try {
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
                    } catch (e: CharacterCodingException) {
                        null
                    }
                }
                val name = text(JoinLimits.MAX_NAME_BYTES) ?: return null
                val phone = text(JoinLimits.MAX_PHONE_BYTES) ?: return null
                if (b.hasRemaining()) return null
                return JoinRequestBody(PublicKey(key), EncryptionKey(enc), name, phone)
            } catch (e: IllegalArgumentException) {
                return null
            }
        }
    }
}

sealed class JoinMessage {
    /** The joiner opens: the group's room and the ticket's id (not the ticket: the group key never leaves the joiner's phone). */
    class Hello(val room: GroupRoom, ticketId: ByteArray) : JoinMessage() {
        val ticketId: ByteArray = ticketId.copyOf()
        init { require(this.ticketId.size == JoinLimits.TICKET_ID_SIZE) { "a ticket id is 16 bytes" } }
    }

    /** The host's nonce for this connection and its signature, which proves it holds the key the ticket names. Sent for any ticket id. */
    class Challenge(nonce: ByteArray, signature: ByteArray) : JoinMessage() {
        val nonce: ByteArray = nonce.copyOf()
        val signature: ByteArray = signature.copyOf()
        init { require(this.nonce.size == JoinLimits.NONCE_SIZE && this.signature.size == JoinLimits.SIGNATURE_SIZE) { "wrong sizes" } }
    }

    /** The request, sealed with the ticket's group key: the [JoinRequestBody] and the joiner's signature of it. */
    class Request(sealed: ByteArray) : JoinMessage() {
        val sealed: ByteArray = sealed.copyOf()
        init { require(this.sealed.size in 1..JoinLimits.MAX_SEALED_BYTES) { "a sealed request is 1 to ${JoinLimits.MAX_SEALED_BYTES} bytes" } }
    }

    /** The host's answer, the same whatever was wrong: the request is kept for the host to approve, or it is not. */
    data class Reply(val accepted: Boolean) : JoinMessage()

    /** A later connection: "what became of my request?" The room and the key the request was made under. */
    data class StatusHello(val room: GroupRoom, val joinerKey: PublicKey) : JoinMessage()
    class StatusChallenge(nonce: ByteArray) : JoinMessage() {
        val nonce: ByteArray = nonce.copyOf()
        init { require(this.nonce.size == JoinLimits.NONCE_SIZE) { "wrong size" } }
    }
    class StatusProof(signature: ByteArray) : JoinMessage() {
        val signature: ByteArray = signature.copyOf()
        init { require(this.signature.size == JoinLimits.SIGNATURE_SIZE) { "wrong size" } }
    }
    data class StatusReply(val status: JoinStatus) : JoinMessage()
}

/** Bytes of the join messages. Same two-byte start and version as the sync messages, types from 16, so one first byte tells them apart. */
object JoinCodec {
    private const val T_HELLO = 16
    private const val T_CHALLENGE = 17
    private const val T_REQUEST = 18
    private const val T_REPLY = 19
    private const val T_STATUS_HELLO = 20
    private const val T_STATUS_CHALLENGE = 21
    private const val T_STATUS_PROOF = 22
    private const val T_STATUS_REPLY = 23

    /** True if the bytes start like a join message (so the receiver can hand them to the join handler rather than the sync engine). */
    fun isJoin(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x4F.toByte() && bytes[1] == 0x53.toByte() && (bytes[3].toInt() and 0xff) >= T_HELLO

    fun encode(m: JoinMessage): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x4F, 0x53, SyncLimits.VERSION.toByte()))
        when (m) {
            is JoinMessage.Hello -> { out.write(T_HELLO); out.write(m.room.id); out.write(m.ticketId) }
            is JoinMessage.Challenge -> { out.write(T_CHALLENGE); out.write(m.nonce); out.write(m.signature) }
            is JoinMessage.Request -> { out.write(T_REQUEST); out.write(ByteBuffer.allocate(2).putShort(m.sealed.size.toShort()).array()); out.write(m.sealed) }
            is JoinMessage.Reply -> { out.write(T_REPLY); out.write(if (m.accepted) 1 else 0) }
            is JoinMessage.StatusHello -> { out.write(T_STATUS_HELLO); out.write(m.room.id); out.write(m.joinerKey.bytes()) }
            is JoinMessage.StatusChallenge -> { out.write(T_STATUS_CHALLENGE); out.write(m.nonce) }
            is JoinMessage.StatusProof -> { out.write(T_STATUS_PROOF); out.write(m.signature) }
            is JoinMessage.StatusReply -> { out.write(T_STATUS_REPLY); out.write(m.status.code) }
        }
        return out.toByteArray()
    }

    /** Null for anything that is not exactly a message [encode] makes. Never throws. */
    fun decode(bytes: ByteArray): JoinMessage? {
        if (bytes.size < 4 || bytes[0] != 0x4F.toByte() || bytes[1] != 0x53.toByte() || bytes[2].toInt() != SyncLimits.VERSION) return null
        val b = ByteBuffer.wrap(bytes, 4, bytes.size - 4)
        fun take(n: Int): ByteArray? = if (b.remaining() >= n) ByteArray(n).also { b.get(it) } else null
        return try {
            val m: JoinMessage? = when (bytes[3].toInt() and 0xff) {
                T_HELLO -> { val r = take(16); val t = take(16); if (r == null || t == null) null else JoinMessage.Hello(GroupRoom(r), t) }
                T_CHALLENGE -> { val n = take(JoinLimits.NONCE_SIZE); val s = take(JoinLimits.SIGNATURE_SIZE); if (n == null || s == null) null else JoinMessage.Challenge(n, s) }
                T_REQUEST -> {
                    val len = take(2)?.let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) }
                    val sealed = len?.let { take(it) }
                    if (sealed == null) null else JoinMessage.Request(sealed)
                }
                T_REPLY -> take(1)?.let { if (it[0].toInt() == 0 || it[0].toInt() == 1) JoinMessage.Reply(it[0].toInt() == 1) else null }
                T_STATUS_HELLO -> { val r = take(16); val k = take(32); if (r == null || k == null) null else JoinMessage.StatusHello(GroupRoom(r), PublicKey(k)) }
                T_STATUS_CHALLENGE -> take(JoinLimits.NONCE_SIZE)?.let { JoinMessage.StatusChallenge(it) }
                T_STATUS_PROOF -> take(JoinLimits.SIGNATURE_SIZE)?.let { JoinMessage.StatusProof(it) }
                T_STATUS_REPLY -> take(1)?.let { c -> JoinStatus.fromCode(c[0].toInt() and 0xff)?.let { JoinMessage.StatusReply(it) } }
                else -> null
            }
            if (m != null && b.hasRemaining()) null else m
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
