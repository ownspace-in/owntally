package owntally.sync

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.TreeMap
import owntally.model.PublicKey

/** The limits of docs/WIRE_FORMAT.md section 6. */
object SyncLimits {
    const val VERSION = 1
    const val MAX_BATCH_COUNT = 200
    const val MAX_BATCH_BYTES = 262_144
    const val MAX_ENVELOPE_BYTES = 65_536
    const val MAX_SUMMARY_ENTRIES = 1_000
    const val MAX_ERROR_TEXT_BYTES = 200
    const val FINGERPRINT_SIZE = 16
}

/** Why a sync stops (the `Error` message, WIRE_FORMAT.md section 6). */
enum class ErrorCode(val number: Int) {
    UNSUPPORTED_VERSION(1), WRONG_GROUP(2), NOT_ALLOWED(3), TOO_LARGE(4), INVALID(5), TOO_MANY_BAD_ENVELOPES(6);

    companion object {
        fun fromNumber(n: Int): ErrorCode? = entries.firstOrNull { it.number == n }
    }
}

/** What a phone holds of one author: the highest counter with none missing below it, and the fingerprint at that counter. */
class AuthorState(val counter: Long, fingerprint: ByteArray) {
    private val fp = fingerprint.copyOf()

    init {
        require(counter >= 1) { "a counter is at least 1" }
        require(fp.size == SyncLimits.FINGERPRINT_SIZE) { "a fingerprint is ${SyncLimits.FINGERPRINT_SIZE} bytes" }
    }

    val fingerprint: ByteArray get() = fp.copyOf()
    override fun equals(other: Any?) = other is AuthorState && counter == other.counter && fp.contentEquals(other.fp)
    override fun hashCode() = counter.hashCode() * 31 + fp.contentHashCode()
    override fun toString() = "AuthorState($counter, ${fp.joinToString("") { "%02x".format(it) }.take(8)})"
}

/** Per author, what this phone holds. Kept in author order, which is also the order on the wire. */
class SyncSummary(entries: Map<PublicKey, AuthorState>) {
    val entries: Map<PublicKey, AuthorState> = TreeMap(entries)

    init {
        require(entries.size <= SyncLimits.MAX_SUMMARY_ENTRIES) { "a summary has at most ${SyncLimits.MAX_SUMMARY_ENTRIES} authors" }
    }

    override fun equals(other: Any?) = other is SyncSummary && entries == other.entries
    override fun hashCode() = entries.hashCode()
    override fun toString() = "SyncSummary(${entries.size} authors)"
}

sealed class SyncMessage {
    /** Opens a sync; each side sends one. */
    data class Hello(val room: GroupRoom, val summary: SyncSummary) : SyncMessage() {
        init {
            require(room.id.size == 16) { "a room is 16 bytes" }
        }
    }

    /** Whole envelopes, opaque to this layer. */
    class Envelopes(envelopes: List<ByteArray>) : SyncMessage() {
        val envelopes: List<ByteArray> = envelopes.map { it.copyOf() }

        init {
            require(envelopes.size in 1..SyncLimits.MAX_BATCH_COUNT) { "a batch has 1 to ${SyncLimits.MAX_BATCH_COUNT} envelopes" }
            require(envelopes.all { it.size in 1..SyncLimits.MAX_ENVELOPE_BYTES }) { "an envelope is 1 to ${SyncLimits.MAX_ENVELOPE_BYTES} bytes" }
            require(envelopes.sumOf { it.size } <= SyncLimits.MAX_BATCH_BYTES) { "a batch is at most ${SyncLimits.MAX_BATCH_BYTES} bytes" }
        }

        override fun equals(other: Any?) = other is Envelopes && envelopes.map { it.toList() } == other.envelopes.map { it.toList() }
        override fun hashCode() = envelopes.sumOf { it.contentHashCode() }
        override fun toString() = "Envelopes(${envelopes.size})"

        companion object {
            /** Cuts a list into messages that keep every limit, in the same order. */
            fun batches(all: List<ByteArray>): List<Envelopes> {
                val out = ArrayList<Envelopes>()
                var current = ArrayList<ByteArray>()
                var bytes = 0
                for (e in all) {
                    require(e.size in 1..SyncLimits.MAX_ENVELOPE_BYTES) { "an envelope is 1 to ${SyncLimits.MAX_ENVELOPE_BYTES} bytes" }
                    if (current.size == SyncLimits.MAX_BATCH_COUNT || bytes + e.size > SyncLimits.MAX_BATCH_BYTES) {
                        out += Envelopes(current)
                        current = ArrayList()
                        bytes = 0
                    }
                    current += e
                    bytes += e.size
                }
                if (current.isNotEmpty()) out += Envelopes(current)
                return out
            }
        }
    }

    /** "Nothing more to send this round", with the sender's summary after storing what it received. */
    data class Done(val summary: SyncSummary) : SyncMessage()

    /** "Our histories of this author disagree: send me every version you hold, and I send you mine." */
    data class Reconcile(val author: PublicKey) : SyncMessage()

    data class Error(val code: ErrorCode, val text: String) : SyncMessage() {
        init {
            require(text.toByteArray(Charsets.UTF_8).size <= SyncLimits.MAX_ERROR_TEXT_BYTES) { "error text is at most ${SyncLimits.MAX_ERROR_TEXT_BYTES} bytes" }
        }
    }
}

sealed class Decoded {
    data class Ok(val message: SyncMessage) : Decoded()

    /** The bytes are not a message this phone accepts. [code] is what to tell the other side. */
    data class Refused(val code: ErrorCode, val reason: String) : Decoded()
}

/** Bytes of the sync messages (WIRE_FORMAT.md section 6). Decoding never throws. */
object SyncCodec {
    private const val TYPE_HELLO = 1
    private const val TYPE_ENVELOPES = 2
    private const val TYPE_DONE = 3
    private const val TYPE_RECONCILE = 4
    private const val TYPE_ERROR = 5

    fun encode(message: SyncMessage): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u8(n: Int) = out.write(n)
        fun u16(n: Int) = out.write(ByteBuffer.allocate(2).putShort(n.toShort()).array())
        fun u32(n: Int) = out.write(ByteBuffer.allocate(4).putInt(n).array())
        fun u64(n: Long) = out.write(ByteBuffer.allocate(8).putLong(n).array())
        fun summary(s: SyncSummary) {
            u16(s.entries.size)
            for ((author, state) in s.entries) {
                out.write(author.bytes())
                u64(state.counter)
                out.write(state.fingerprint)
            }
        }
        out.write(byteArrayOf(0x4F, 0x53))
        u8(SyncLimits.VERSION)
        when (message) {
            is SyncMessage.Hello -> { u8(TYPE_HELLO); out.write(message.room.id); summary(message.summary) }
            is SyncMessage.Envelopes -> {
                u8(TYPE_ENVELOPES)
                u16(message.envelopes.size)
                for (e in message.envelopes) { u32(e.size); out.write(e) }
            }
            is SyncMessage.Done -> { u8(TYPE_DONE); summary(message.summary) }
            is SyncMessage.Reconcile -> { u8(TYPE_RECONCILE); out.write(message.author.bytes()) }
            is SyncMessage.Error -> {
                u8(TYPE_ERROR)
                u8(message.code.number)
                val text = message.text.toByteArray(Charsets.UTF_8)
                u16(text.size)
                out.write(text)
            }
        }
        return out.toByteArray()
    }

    private class Refuse(val code: ErrorCode, val reason: String) : Exception(reason, null, false, false)

    private class Reader(private val bytes: ByteArray, var pos: Int) {
        val left get() = bytes.size - pos
        private fun need(n: Int) { if (n < 0 || left < n) throw Refuse(ErrorCode.INVALID, "the message is cut short") }
        fun u8(): Int { need(1); return bytes[pos++].toInt() and 0xff }
        fun u16(): Int { need(2); return ((bytes[pos++].toInt() and 0xff) shl 8) or (bytes[pos++].toInt() and 0xff) }
        fun u32(): Long { need(4); var v = 0L; repeat(4) { v = (v shl 8) or (bytes[pos++].toLong() and 0xff) }; return v }
        fun u64(): Long { need(8); var v = 0L; repeat(8) { v = (v shl 8) or (bytes[pos++].toLong() and 0xff) }; return v }
        fun take(n: Int): ByteArray { need(n); return bytes.copyOfRange(pos, pos + n).also { pos += n } }
        fun end() { if (left != 0) throw Refuse(ErrorCode.INVALID, "bytes after the end of the message") }
    }

    fun decode(bytes: ByteArray): Decoded = try {
        Decoded.Ok(read(bytes))
    } catch (r: Refuse) {
        Decoded.Refused(r.code, r.reason)
    } catch (e: IllegalArgumentException) {
        Decoded.Refused(ErrorCode.INVALID, e.message ?: "invalid message")
    }

    private fun read(bytes: ByteArray): SyncMessage {
        if (bytes.size < 4 || bytes[0] != 0x4F.toByte() || bytes[1] != 0x53.toByte()) throw Refuse(ErrorCode.INVALID, "not a sync message")
        val version = bytes[2].toInt() and 0xff
        if (version != SyncLimits.VERSION) throw Refuse(ErrorCode.UNSUPPORTED_VERSION, "sync version $version is not supported")
        val r = Reader(bytes, 4)
        fun summary(): SyncSummary {
            val count = r.u16()
            if (count > SyncLimits.MAX_SUMMARY_ENTRIES) throw Refuse(ErrorCode.INVALID, "too many authors in a summary")
            val map = TreeMap<PublicKey, AuthorState>()
            var previous: PublicKey? = null
            repeat(count) {
                val author = PublicKey(r.take(32))
                if (previous != null && author <= previous!!) throw Refuse(ErrorCode.INVALID, "summary entries are not in ascending author order")
                previous = author
                val counter = r.u64()
                if (counter < 1) throw Refuse(ErrorCode.INVALID, "a counter is at least 1")
                map[author] = AuthorState(counter, r.take(SyncLimits.FINGERPRINT_SIZE))
            }
            return SyncSummary(map)
        }
        val message: SyncMessage = when (val type = bytes[3].toInt() and 0xff) {
            TYPE_HELLO -> SyncMessage.Hello(GroupRoom(r.take(16)), summary())
            TYPE_ENVELOPES -> {
                val count = r.u16()
                if (count !in 1..SyncLimits.MAX_BATCH_COUNT) throw Refuse(ErrorCode.INVALID, "a batch has 1 to ${SyncLimits.MAX_BATCH_COUNT} envelopes")
                var total = 0L
                val list = ArrayList<ByteArray>(count)
                repeat(count) {
                    val length = r.u32()
                    if (length < 1) throw Refuse(ErrorCode.INVALID, "an envelope is at least 1 byte")
                    if (length > SyncLimits.MAX_ENVELOPE_BYTES) throw Refuse(ErrorCode.TOO_LARGE, "an envelope is at most ${SyncLimits.MAX_ENVELOPE_BYTES} bytes")
                    total += length
                    if (total > SyncLimits.MAX_BATCH_BYTES) throw Refuse(ErrorCode.TOO_LARGE, "a batch is at most ${SyncLimits.MAX_BATCH_BYTES} bytes")
                    list += r.take(length.toInt())
                }
                SyncMessage.Envelopes(list)
            }
            TYPE_DONE -> SyncMessage.Done(summary())
            TYPE_RECONCILE -> SyncMessage.Reconcile(PublicKey(r.take(32)))
            TYPE_ERROR -> {
                val code = ErrorCode.fromNumber(r.u8()) ?: throw Refuse(ErrorCode.INVALID, "unknown error code")
                val length = r.u16()
                if (length > SyncLimits.MAX_ERROR_TEXT_BYTES) throw Refuse(ErrorCode.INVALID, "error text too long")
                val raw = r.take(length)
                val text = try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
                } catch (e: CharacterCodingException) {
                    throw Refuse(ErrorCode.INVALID, "error text is not UTF-8")
                }
                SyncMessage.Error(code, text)
            }
            else -> throw Refuse(ErrorCode.INVALID, "unknown message type $type")
        }
        r.end()
        return message
    }
}

/**
 * The fingerprint of an author's events (WIRE_FORMAT.md section 6, S1). It changes if any stored version of any event differs, so two
 * phones with the same counters but different versions notice at the next sync.
 */
object Fingerprint {
    val ZERO: ByteArray get() = ByteArray(SyncLimits.FINGERPRINT_SIZE)
    private val LABEL = "OTFP1".toByteArray(Charsets.US_ASCII)

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private val unsigned = Comparator<ByteArray> { a, b ->
        for (i in a.indices) {
            val c = (a[i].toInt() and 0xff).compareTo(b[i].toInt() and 0xff)
            if (c != 0) return@Comparator c
        }
        0
    }

    /** The (at most two) versions with the smallest hashes, the ones a phone keeps for one id. Distinct bytes only. */
    fun smallestTwo(versions: List<ByteArray>): List<ByteArray> =
        versions.distinctBy { hash(it).toList() }.sortedWith { a, b -> unsigned.compare(hash(a), hash(b)) }.take(2)

    /** The fingerprint at counter [counter], from the one at the counter before it and every stored version of that event. */
    fun next(previous: ByteArray, counter: Long, versions: List<ByteArray>): ByteArray {
        require(previous.size == SyncLimits.FINGERPRINT_SIZE) { "a fingerprint is ${SyncLimits.FINGERPRINT_SIZE} bytes" }
        require(versions.isNotEmpty()) { "an event has at least one version" }
        val hashes = smallestTwo(versions).map { hash(it) }.sortedWith(unsigned)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(LABEL)
        digest.update(previous)
        digest.update(ByteBuffer.allocate(8).putLong(counter).array())
        digest.update(hashes.size.toByte())
        for (h in hashes) digest.update(h)
        return digest.digest().copyOf(SyncLimits.FINGERPRINT_SIZE)
    }
}
