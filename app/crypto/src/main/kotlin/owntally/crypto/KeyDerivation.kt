package owntally.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import owntally.model.EncryptionKey
import owntally.model.PublicKey

/**
 * One member's keys in one group (docs/CRYPTO.md section 2): an Ed25519 signing key pair, whose public half is the
 * member's [publicKey], and an X25519 key pair, whose public half is the [encryptionKey]. The private halves are
 * secrets: they are reachable only inside this module and [toString] shows none of them.
 */
class GroupIdentity internal constructor(
    val slot: Long,
    private val signSeed: ByteArray,
    val publicKey: PublicKey,
    private val encryptionPrivate: ByteArray,
    val encryptionKey: EncryptionKey,
) {
    internal fun signingSeed(): ByteArray = signSeed.copyOf()
    internal fun encryptionPrivateKey(): ByteArray = encryptionPrivate.copyOf()

    override fun equals(other: Any?) = other is GroupIdentity && slot == other.slot && publicKey == other.publicKey && encryptionKey == other.encryptionKey
    override fun hashCode() = publicKey.hashCode()
    override fun toString() = "GroupIdentity(slot=$slot, [redacted])"
}

object KeyDerivation {
    private val SALT = "owntally/v1".toByteArray()
    private const val MAX_SLOT = 0xFFFF_FFFFL

    /**
     * The keys for numbered [slot] (0 to 2^32 - 1), derived from [seed] with HKDF-SHA256 (Tink). Different slots give unrelated
     * keys, so members of different groups cannot be linked.
     */
    fun groupIdentity(seed: MasterSeed, slot: Long): GroupIdentity {
        require(slot in 0..MAX_SLOT) { "slot must fit in 32 bits" }
        val ikm = seed.bytes()
        val signSeed = Hkdf.computeHkdf("HmacSha256", ikm, SALT, "group/$slot/sign".toByteArray(), 32)
        val encryptionPrivate = Hkdf.computeHkdf("HmacSha256", ikm, SALT, "group/$slot/encrypt".toByteArray(), 32)
        ikm.fill(0)
        val signingPair = Ed25519Sign.KeyPair.newKeyPairFromSeed(signSeed)
        return GroupIdentity(
            slot, signSeed, PublicKey(signingPair.publicKey),
            encryptionPrivate, EncryptionKey(X25519.publicFromPrivate(encryptionPrivate)),
        )
    }
}
