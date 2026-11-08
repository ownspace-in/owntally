package owntally.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import owntally.model.GroupId
import owntally.model.PublicKey

/**
 * The proofs of the join handshake (docs/tasks/M6-nearby-invites.md, T6.2). Ed25519 and XChaCha20-Poly1305 come from Tink. Every
 * signature starts with its own label, so a signature made for one purpose is never accepted for another, and covers the group, the
 * host and a nonce the host chose for this one connection, so none can be replayed on another connection.
 *
 * - The host proves it holds the key named in the ticket ([signHost]).
 * - The joiner's request is sealed with the group key from the ticket, bound to the ticket, the host and the nonce ([sealRequest]):
 *   only someone holding the ticket's key can make one the host will open, and a copy of it is useless on another connection.
 * - The joiner signs the request ([signRequest]) so the host knows the joiner holds the key it names, and later signs a status
 *   question ([signStatus]) so only that key's holder can ask about the request.
 */
object JoinCrypto {
    const val NONCE_SIZE = 32
    private val HOST = "owntally/v1/join-host".toByteArray()
    private val REQUEST = "owntally/v1/join-request".toByteArray()
    private val STATUS = "owntally/v1/join-status".toByteArray()
    private val SEALED = "owntally/v1/join-sealed".toByteArray()
    private val PREVIEW = "owntally/v1/join-preview".toByteArray()

    fun newNonce(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }

    private fun data(label: ByteArray, vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(label)
        for (p in parts) out.write(p)
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

    private fun verify(key: PublicKey, message: ByteArray, signature: ByteArray): Boolean = try {
        Ed25519Verify(key.bytes()).verify(signature, message)
        true
    } catch (e: GeneralSecurityException) {
        false
    }

    fun signHost(host: GroupIdentity, group: GroupId, ticketId: ByteArray, nonce: ByteArray): ByteArray =
        sign(host, data(HOST, group.bytes(), ticketId, nonce))

    fun verifyHost(hostId: PublicKey, group: GroupId, ticketId: ByteArray, nonce: ByteArray, signature: ByteArray): Boolean =
        verify(hostId, data(HOST, group.bytes(), ticketId, nonce), signature)

    fun signRequest(joiner: GroupIdentity, group: GroupId, hostId: PublicKey, nonce: ByteArray, body: ByteArray): ByteArray =
        sign(joiner, data(REQUEST, group.bytes(), hostId.bytes(), nonce, body))

    fun verifyRequest(joinerKey: PublicKey, group: GroupId, hostId: PublicKey, nonce: ByteArray, body: ByteArray, signature: ByteArray): Boolean =
        verify(joinerKey, data(REQUEST, group.bytes(), hostId.bytes(), nonce, body), signature)

    fun signStatus(joiner: GroupIdentity, group: GroupId, hostId: PublicKey, nonce: ByteArray): ByteArray =
        sign(joiner, data(STATUS, group.bytes(), hostId.bytes(), nonce))

    fun verifyStatus(joinerKey: PublicKey, group: GroupId, hostId: PublicKey, nonce: ByteArray, signature: ByteArray): Boolean =
        verify(joinerKey, data(STATUS, group.bytes(), hostId.bytes(), nonce), signature)

    private fun associated(ticketId: ByteArray, hostId: PublicKey, nonce: ByteArray) = data(SEALED, ticketId, hostId.bytes(), nonce)

    /** The nonce Tink makes (24 bytes) is kept in front of the ciphertext. */
    fun sealRequest(key: GroupKey, ticketId: ByteArray, hostId: PublicKey, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key.bytes()
        return try {
            XChaCha20Poly1305(k).encrypt(plaintext, associated(ticketId, hostId, nonce))
        } finally {
            k.fill(0)
        }
    }

    /** The plaintext, or null if this is not a request sealed with [key] for this ticket, host and nonce. Never throws. */
    fun openRequest(key: GroupKey, ticketId: ByteArray, hostId: PublicKey, nonce: ByteArray, sealed: ByteArray): ByteArray? {
        val k = key.bytes()
        return try {
            XChaCha20Poly1305(k).decrypt(sealed, associated(ticketId, hostId, nonce))
        } catch (e: GeneralSecurityException) {
            null
        } finally {
            k.fill(0)
        }
    }

    /** The host's description of the group (name, owner, member count), sealed with the ticket's group key and bound like a request but with its own label, so one can never be taken for the other. */
    fun sealPreview(key: GroupKey, ticketId: ByteArray, hostId: PublicKey, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key.bytes()
        return try {
            XChaCha20Poly1305(k).encrypt(plaintext, data(PREVIEW, ticketId, hostId.bytes(), nonce))
        } finally {
            k.fill(0)
        }
    }

    fun openPreview(key: GroupKey, ticketId: ByteArray, hostId: PublicKey, nonce: ByteArray, sealed: ByteArray): ByteArray? {
        val k = key.bytes()
        return try {
            XChaCha20Poly1305(k).decrypt(sealed, data(PREVIEW, ticketId, hostId.bytes(), nonce))
        } catch (e: GeneralSecurityException) {
            null
        } finally {
            k.fill(0)
        }
    }
}
