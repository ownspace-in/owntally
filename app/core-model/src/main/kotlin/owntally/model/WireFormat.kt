package owntally.model

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Bytes that are not exactly what the encoder would have written (docs/WIRE_FORMAT.md section 4). */
class DecodeException(message: String) : Exception(message)

private class Writer {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int) = apply { require(v in 0..0xFF); out.write(v) }
    fun u16(v: Int) = apply { require(v in 0..0xFFFF); out.write(v ushr 8); out.write(v) }
    fun u32(v: Long) = apply { require(v in 0..0xFFFF_FFFFL); for (s in intArrayOf(24, 16, 8, 0)) out.write((v ushr s).toInt()) }
    fun u64(v: Long) = apply { require(v >= 0); i64(v) }
    fun i64(v: Long) = apply { for (s in 56 downTo 0 step 8) out.write((v ushr s).toInt()) }
    fun raw(b: ByteArray) = apply { out.write(b) }
    fun key(k: ByteId) = raw(k.bytes())

    fun str(s: String) = apply {
        val bytes = try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(s)).let { b -> ByteArray(b.remaining()).also { b.get(it) } }
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("text is not valid Unicode")
        }
        u16(bytes.size).raw(bytes)
    }

    fun <V> keyedMap(map: Map<PublicKey, V>, writeValue: Writer.(V) -> Unit) = apply {
        u16(map.size)
        for (k in map.keys.sorted()) { key(k); writeValue(map.getValue(k)) }
    }

    fun keySet(keys: Set<PublicKey>) = apply {
        u16(keys.size)
        for (k in keys.sorted()) key(k)
    }

    fun id(id: EventId) = apply { key(id.author).u64(id.counter) }
    fun bytes(): ByteArray = out.toByteArray()
}

private class Reader(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {
    val remaining: Int get() = end - pos

    private fun need(n: Long) {
        if (n < 0 || n > remaining) throw DecodeException("ends too early")
    }

    fun u8(): Int { need(1); return data[pos++].toInt() and 0xFF }
    fun u16(): Int = (u8() shl 8) or u8()
    fun u32(): Long { need(4); var v = 0L; repeat(4) { v = (v shl 8) or u8().toLong() }; return v }
    fun i64(): Long { need(8); var v = 0L; repeat(8) { v = (v shl 8) or u8().toLong() }; return v }
    fun u64(): Long = i64().also { if (it < 0) throw DecodeException("number too large") }

    fun bytes(n: Long): ByteArray { need(n); return data.copyOfRange(pos, pos + n.toInt()).also { pos += n.toInt() } }
    fun key() = PublicKey(bytes(32))
    fun encryptionKey() = EncryptionKey(bytes(32))

    fun str(): String {
        val raw = bytes(u16().toLong())
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString()
        } catch (e: CharacterCodingException) {
            throw DecodeException("text is not valid UTF-8")
        }
    }

    fun <V> keyedMap(readValue: Reader.() -> V): Map<PublicKey, V> {
        val count = u16()
        val result = LinkedHashMap<PublicKey, V>()
        var previous: PublicKey? = null
        repeat(count) {
            val k = key()
            if (previous != null && k <= previous!!) throw DecodeException("keys not in ascending order")
            previous = k
            result[k] = readValue()
        }
        return result
    }

    fun keySet(): Set<PublicKey> = keyedMap { }.keys

    fun id() = EventId(key(), u64())

    /** The next [n] bytes as a reader of their own. */
    fun slice(n: Long): Reader { need(n); return Reader(data, pos, pos + n.toInt()).also { pos += n.toInt() } }

    fun requireEnd() {
        if (remaining != 0) throw DecodeException("$remaining unexpected bytes left")
    }
}

/** Event payloads as bytes: docs/WIRE_FORMAT.md section 2. */
object EventCodec {
    /** Tags 1 to 17 are known event types; a body can only be unknown when its tag is 0 or 18 and above. */
    private const val FIRST_UNKNOWN_TAG = 18

    fun encodePayload(event: Event): ByteArray {
        val content = Writer()
        val tag = writeBody(content, event.body)
        return Writer().keyedMap(event.after) { u64(it) }.i64(event.time).u8(tag).u32(content.bytes().size.toLong()).raw(content.bytes()).bytes()
    }

    /** [version], [author] and [counter] come from the envelope; the rest is read from [bytes]. */
    fun decodePayload(version: Int, author: PublicKey, counter: Long, bytes: ByteArray): Event = try {
        val r = Reader(bytes)
        val after = r.keyedMap { u64() }
        val time = r.i64()
        val tag = r.u8()
        val body = readBody(tag, r.slice(r.u32()))
        r.requireEnd()
        Event(version, author, counter, after, time, body)
    } catch (e: IllegalArgumentException) {
        throw DecodeException("invalid value: ${e.message}")
    } catch (e: ArithmeticException) {
        throw DecodeException("number out of range")
    }

    private fun writeSplit(w: Writer, split: Split) {
        when (split) {
            is Split.Equal -> w.u8(1).keySet(split.participants)
            is Split.Exact -> w.u8(2).keyedMap(split.amounts) { i64(it.paise) }
        }
    }

    private fun readSplit(r: Reader): Split = when (r.u8()) {
        1 -> Split.Equal(r.keySet())
        2 -> Split.Exact(r.keyedMap { Money(i64()) })
        else -> throw DecodeException("unknown split kind")
    }

    private fun writeSealed(w: Writer, keys: Map<PublicKey, SealedKey>) =
        w.keyedMap(keys) { u16(it.bytes().size).raw(it.bytes()) }

    private fun readSealed(r: Reader): Map<PublicKey, SealedKey> = r.keyedMap { SealedKey(bytes(u16().toLong())) }

    /** Writes the content of [body] and returns its tag. */
    private fun writeBody(w: Writer, body: EventBody): Int {
        when (body) {
            is EventBody.GroupCreated -> { w.raw(body.groupId.bytes()).str(body.name); w.raw(body.currency.toByteArray(Charsets.US_ASCII)).key(body.encryptionKey); return 1 }
            is EventBody.MemberJoined -> { w.key(body.member).key(body.encryptionKey).str(body.name).str(body.phone); return 2 }
            is EventBody.ProfileUpdated -> { w.str(body.name).str(body.phone); return 3 }
            is EventBody.ExpenseAdded -> { w.str(body.description).i64(body.total.paise).key(body.paidBy).i64(body.day); writeSplit(w, body.split); return 4 }
            is EventBody.ExpenseDeleted -> { w.id(body.expense); return 5 }
            is EventBody.SettlementRecorded -> { w.key(body.from).key(body.to).i64(body.amount.paise); return 6 }
            is EventBody.SettlementConfirmed -> { w.id(body.settlement); return 7 }
            is EventBody.PaymentDisputed -> { w.id(body.settlement).u8(body.reason.code); return 8 }
            is EventBody.AdminAdded -> { w.key(body.member); return 9 }
            is EventBody.AdminRemoved -> { w.key(body.member); return 10 }
            is EventBody.OwnershipOffered -> { w.key(body.to); return 11 }
            is EventBody.OwnershipOfferCancelled -> return 12
            is EventBody.OwnershipAccepted -> return 13
            is EventBody.OwnershipDeclined -> return 16
            is EventBody.MemberRemoved -> { w.key(body.member).u32(body.newEpoch); writeSealed(w, body.sealedKeys); return 14 }
            is EventBody.KeyRotated -> { w.u32(body.newEpoch); writeSealed(w, body.sealedKeys); return 15 }
            is EventBody.KeyLinked -> { w.u32(body.epoch).raw(body.previousKey.bytes()); return 17 }
            is EventBody.Unknown -> {
                require(body.tag !in 1 until FIRST_UNKNOWN_TAG) { "tag ${body.tag} belongs to a known event type" }
                w.raw(body.content())
                return body.tag
            }
        }
    }

    private fun readBody(tag: Int, r: Reader): EventBody {
        val body: EventBody = when (tag) {
            1 -> EventBody.GroupCreated(GroupId(r.bytes(16)), r.str(), String(r.bytes(3), Charsets.US_ASCII), r.encryptionKey())
            2 -> EventBody.MemberJoined(r.key(), r.encryptionKey(), r.str(), r.str())
            3 -> EventBody.ProfileUpdated(r.str(), r.str())
            4 -> EventBody.ExpenseAdded(r.str(), Money(r.i64()), r.key(), r.i64(), readSplit(r))
            5 -> EventBody.ExpenseDeleted(r.id())
            6 -> EventBody.SettlementRecorded(r.key(), r.key(), Money(r.i64()))
            7 -> EventBody.SettlementConfirmed(r.id())
            8 -> EventBody.PaymentDisputed(r.id(), DisputeReason.fromCode(r.u8()) ?: throw DecodeException("unknown dispute reason"))
            9 -> EventBody.AdminAdded(r.key())
            10 -> EventBody.AdminRemoved(r.key())
            11 -> EventBody.OwnershipOffered(r.key())
            12 -> EventBody.OwnershipOfferCancelled
            13 -> EventBody.OwnershipAccepted
            16 -> EventBody.OwnershipDeclined
            14 -> EventBody.MemberRemoved(r.key(), r.u32(), readSealed(r))
            15 -> EventBody.KeyRotated(r.u32(), readSealed(r))
            17 -> EventBody.KeyLinked(r.u32(), PreviousKey(r.bytes(32)))
            else -> return EventBody.Unknown(tag, r.bytes(r.remaining.toLong()))
        }
        r.requireEnd()
        return body
    }
}

/**
 * What is stored and sent (docs/WIRE_FORMAT.md section 3). [signedPart] is every byte before the signature. The crypto
 * module makes and checks [nonce], [ciphertext] and [signature]; this class only lays out the bytes.
 */
class Envelope(
    val version: Int,
    val groupId: GroupId,
    val epoch: Long,
    val author: PublicKey,
    val counter: Long,
    nonce: ByteArray,
    ciphertext: ByteArray,
    signature: ByteArray,
) {
    private val nonceBytes = nonce.copyOf()
    private val ciphertextBytes = ciphertext.copyOf()
    private val signatureBytes = signature.copyOf()

    init {
        require(version in 0..0xFF) { "version is one byte" }
        require(epoch in 0..0xFFFF_FFFFL) { "epoch is 32 bits" }
        require(counter >= 1) { "counter starts at 1" }
        require(nonceBytes.size == NONCE_SIZE) { "nonce is $NONCE_SIZE bytes" }
        require(signatureBytes.size == SIGNATURE_SIZE) { "signature is $SIGNATURE_SIZE bytes" }
    }

    fun nonce(): ByteArray = nonceBytes.copyOf()
    fun ciphertext(): ByteArray = ciphertextBytes.copyOf()
    fun signature(): ByteArray = signatureBytes.copyOf()

    fun signedPart(): ByteArray =
        Writer().raw(MAGIC).u8(version).raw(groupId.bytes()).u32(epoch).key(author).u64(counter).raw(nonceBytes)
            .u32(ciphertextBytes.size.toLong()).raw(ciphertextBytes).bytes()

    fun encode(): ByteArray = signedPart() + signatureBytes

    override fun equals(other: Any?) = other is Envelope && encode().contentEquals(other.encode())
    override fun hashCode() = encode().contentHashCode()
    override fun toString() = "Envelope(${author}#$counter epoch $epoch, ${ciphertextBytes.size} bytes)"

    companion object {
        const val CURRENT_VERSION = 1
        const val NONCE_SIZE = 24
        const val SIGNATURE_SIZE = 64
        private val MAGIC = byteArrayOf(0x4F, 0x54)

        fun decode(bytes: ByteArray): Envelope {
            val r = Reader(bytes)
            if (!r.bytes(2).contentEquals(MAGIC)) throw DecodeException("not an OwnTally envelope")
            val version = r.u8()
            if (version != CURRENT_VERSION) throw DecodeException("unknown envelope version $version")
            val groupId = GroupId(r.bytes(16))
            val epoch = r.u32()
            val author = r.key()
            val counter = r.u64()
            if (counter < 1) throw DecodeException("counter starts at 1")
            val nonce = r.bytes(NONCE_SIZE.toLong())
            val ciphertext = r.bytes(r.u32().also { if (it > r.remaining - SIGNATURE_SIZE) throw DecodeException("ciphertext longer than the data") })
            val signature = r.bytes(SIGNATURE_SIZE.toLong())
            r.requireEnd()
            return Envelope(version, groupId, epoch, author, counter, nonce, ciphertext, signature)
        }
    }
}
