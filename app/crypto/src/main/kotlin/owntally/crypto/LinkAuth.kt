package owntally.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import owntally.model.PublicKey

/**
 * The proofs of a local link's handshake (docs/tasks/M6-nearby-invites.md, T6.7): each side shows it holds the key it connects as. Ed25519
 * from Tink. A proof covers a label for its side, both phones' keys, the room, both nonces and what each side offered, so it cannot be
 * used by the other side, on another connection, for another pair of phones or with other terms.
 */
object LinkAuth {
    const val NONCE_SIZE = 32
    private val SERVER = "owntally/v1/link-server".toByteArray()
    private val CLIENT = "owntally/v1/link-client".toByteArray()
    private val TLS = "owntally/v1/tls-binding".toByteArray()

    fun newNonce(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }

    private fun data(label: ByteArray, transcript: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(label)
        out.write(transcript)
        return out.toByteArray()
    }

    private fun sign(identity: GroupIdentity, message: ByteArray): ByteArray {
        val seed = identity.signingSeed()
        return try {
            Ed25519Sign(seed).sign(message)
        } finally {
            seed.fill(0)
        }
    }

    private fun verify(key: PublicKey, message: ByteArray, signature: ByteArray): Boolean = if (WeakKeys.isWeak(key.bytes())) false else try {
        Ed25519Verify(key.bytes()).verify(signature, message)
        true
    } catch (e: GeneralSecurityException) {
        false
    }

    /** What the answering side signs: the transcript is everything both sides said before it (built by the caller, the same on both sides). */
    fun signAsServer(identity: GroupIdentity, transcript: ByteArray): ByteArray = sign(identity, data(SERVER, transcript))
    fun verifyServer(key: PublicKey, transcript: ByteArray, signature: ByteArray): Boolean = verify(key, data(SERVER, transcript), signature)
    fun signAsClient(identity: GroupIdentity, transcript: ByteArray): ByteArray = sign(identity, data(CLIENT, transcript))
    fun verifyClient(key: PublicKey, transcript: ByteArray, signature: ByteArray): Boolean = verify(key, data(CLIENT, transcript), signature)

    /**
     * Ties a TLS certificate's key to a member's key (T6.8): the member signs the certificate's public key (its SubjectPublicKeyInfo bytes).
     * A phone that is shown such a certificate and checks this signature against the key it meant to reach knows the other end of the TLS
     * session holds that member's key.
     */
    fun signTlsBinding(identity: GroupIdentity, certificateKey: ByteArray): ByteArray = sign(identity, data(TLS, certificateKey))
    fun verifyTlsBinding(key: PublicKey, certificateKey: ByteArray, signature: ByteArray): Boolean = verify(key, data(TLS, certificateKey), signature)
}
