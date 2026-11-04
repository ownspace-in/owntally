package owntally.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException
import owntally.model.Envelope
import owntally.model.GroupId
import owntally.model.PublicKey

/**
 * Signing and checking envelopes (docs/CRYPTO.md section 4). The signature covers a fixed prefix and every byte of the
 * envelope before the signature, so it cannot be moved to another group, epoch, event slot or purpose. Ed25519 comes from Tink.
 */
object EnvelopeSigning {
    private val PREFIX = "owntally/v1/event".toByteArray()

    /** Builds the envelope for [identity] with its signature. The author is always the identity's public key. */
    fun sign(identity: GroupIdentity, version: Int, groupId: GroupId, epoch: Long, counter: Long, nonce: ByteArray, ciphertext: ByteArray): Envelope {
        val unsigned = Envelope(version, groupId, epoch, identity.publicKey, counter, nonce, ciphertext, ByteArray(Envelope.SIGNATURE_SIZE))
        val seed = identity.signingSeed()
        val signature = try {
            Ed25519Sign(seed).sign(PREFIX + unsigned.signedPart())
        } finally {
            seed.fill(0)
        }
        return Envelope(version, groupId, epoch, identity.publicKey, counter, nonce, ciphertext, signature)
    }

    /** True only if the signature is a valid signature by the envelope's author. Never throws on bad input. */
    fun verify(envelope: Envelope): Boolean = try {
        Ed25519Verify(envelope.author.bytes()).verify(envelope.signature(), PREFIX + envelope.signedPart())
        true
    } catch (e: GeneralSecurityException) {
        false
    }

    /** As [verify], and the author must also be one of [members]. Whether a member may do the thing is a replay rule (M1). */
    fun verifyFrom(envelope: Envelope, members: Collection<PublicKey>): Boolean = envelope.author in members && verify(envelope)
}
