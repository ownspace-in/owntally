package owntally.crypto

import com.google.crypto.tink.subtle.XChaCha20Poly1305
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import owntally.model.DecodeException
import owntally.model.Envelope
import owntally.model.Event
import owntally.model.EventCodec
import owntally.model.GroupId
import owntally.model.PublicKey

/** A group's 32-byte symmetric key for one epoch. A secret: [toString] shows nothing. */
class GroupKey(bytes: ByteArray) {
    private val data: ByteArray

    init {
        require(bytes.size == SIZE) { "a group key is $SIZE bytes" }
        data = bytes.copyOf()
    }

    fun bytes(): ByteArray = data.copyOf()
    override fun equals(other: Any?) = other is GroupKey && MessageDigest.isEqual(data, other.data)
    override fun hashCode() = 0
    override fun toString() = "GroupKey([redacted])"

    companion object {
        const val SIZE = 32
        fun generate(random: SecureRandom = SecureRandom()) = GroupKey(ByteArray(SIZE).also { random.nextBytes(it) })
    }
}

/** Every epoch's [GroupKey] a phone holds for one group. Old epochs stay so old events can still be read. */
class GroupKeyring(val groupId: GroupId) {
    private val keys = ConcurrentHashMap<Long, GroupKey>()

    fun add(epoch: Long, key: GroupKey) {
        require(epoch >= 1) { "epochs start at 1" }
        keys[epoch] = key
    }

    operator fun get(epoch: Long): GroupKey? = keys[epoch]
    fun epochs(): Set<Long> = keys.keys.toSortedSet()

    override fun toString() = "GroupKeyring(epochs=${epochs()})"
}

enum class RejectReason { WRONG_GROUP, BAD_SIGNATURE, DECRYPTION_FAILED, MALFORMED }

sealed class OpenResult {
    data class Opened(val event: Event) : OpenResult()
    data class Rejected(val reason: RejectReason) : OpenResult()

    /** The envelope is fine so far but is written under an epoch whose key this phone does not have yet. */
    data class NeedKey(val epoch: Long) : OpenResult()

    /**
     * The signature is good but the author is not among the members the caller knows (yet). Nothing was decrypted. The caller keeps
     * the envelope and tries again when it learns of more members: the admission may simply not have arrived yet.
     */
    data object UnknownAuthor : OpenResult()
}

/**
 * Encrypts an event's payload with the epoch's group key and signs the envelope, and does the reverse (docs/CRYPTO.md
 * section 5). XChaCha20-Poly1305 comes from Tink, which makes a fresh random 24-byte nonce for every call.
 */
object EventSealing {
    private val PREFIX = "owntally/v1/payload".toByteArray()

    /** The associated data that ties a ciphertext to its group, epoch, author and counter. */
    internal fun associatedData(groupId: GroupId, epoch: Long, author: PublicKey, counter: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(PREFIX)
        out.write(groupId.bytes())
        for (s in intArrayOf(24, 16, 8, 0)) out.write((epoch ushr s).toInt())
        out.write(author.bytes())
        for (s in 56 downTo 0 step 8) out.write((counter ushr s).toInt())
        return out.toByteArray()
    }

    /** The event must be written by [identity]. */
    fun seal(identity: GroupIdentity, groupId: GroupId, epoch: Long, key: GroupKey, event: Event): Envelope {
        require(epoch >= 1) { "epochs start at 1" }
        require(event.author == identity.publicKey) { "the event was not written by this identity" }
        val plaintext = EventCodec.encodePayload(event)
        val keyBytes = key.bytes()
        val sealed = try {
            // Tink returns the nonce (24 bytes) followed by the ciphertext and tag; the envelope keeps them apart.
            XChaCha20Poly1305(keyBytes).encrypt(plaintext, associatedData(groupId, epoch, event.author, event.counter))
        } finally {
            keyBytes.fill(0)
        }
        return EnvelopeSigning.sign(
            identity, event.version, groupId, epoch, event.counter,
            sealed.copyOfRange(0, Envelope.NONCE_SIZE), sealed.copyOfRange(Envelope.NONCE_SIZE, sealed.size),
        )
    }

    /**
     * Checks the group and the signature first (cheap), then, when [members] is given, that the author is one of them (owner
     * decision, 4 Nov 2026, clarification C7), and only then decrypts and decodes. Pass null when no member is known yet (before the
     * group's creating event has been read). Never throws on bad input.
     */
    fun open(envelope: Envelope, keyring: GroupKeyring, members: Collection<PublicKey>? = null): OpenResult {
        if (envelope.groupId != keyring.groupId) return OpenResult.Rejected(RejectReason.WRONG_GROUP)
        if (!EnvelopeSigning.verify(envelope)) return OpenResult.Rejected(RejectReason.BAD_SIGNATURE)
        if (members != null && envelope.author !in members) return OpenResult.UnknownAuthor
        val key = keyring[envelope.epoch] ?: return OpenResult.NeedKey(envelope.epoch)
        val keyBytes = key.bytes()
        val plaintext = try {
            XChaCha20Poly1305(keyBytes).decrypt(envelope.nonce() + envelope.ciphertext(), associatedData(envelope.groupId, envelope.epoch, envelope.author, envelope.counter))
        } catch (e: GeneralSecurityException) {
            return OpenResult.Rejected(RejectReason.DECRYPTION_FAILED)
        } finally {
            keyBytes.fill(0)
        }
        return try {
            OpenResult.Opened(EventCodec.decodePayload(envelope.version, envelope.author, envelope.counter, plaintext))
        } catch (e: DecodeException) {
            OpenResult.Rejected(RejectReason.MALFORMED)
        }
    }
}
