package owntally.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import owntally.model.PublicKey

/**
 * The signature a member gives the WebSocket relay to prove it holds the key it connects as (M12, finding N3; docs/CRYPTO.md section 7a). The relay
 * sends a fresh nonce; the member signs a label, the room, its own key and the nonce. The label keeps the signature from being taken for any other
 * purpose, the nonce makes it good for one connection only, the room for one room, the key for one key. Ed25519 from Tink; small-order keys are
 * refused like everywhere else ([WeakKeys]). The relay (Go) checks the same bytes; the vectors in `crypto-vectors.json` are read by both.
 */
object RelayAuth {
    const val NONCE_SIZE = 32
    private val LABEL = "owntally/v1/relay-auth".toByteArray()

    fun newNonce(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }

    /** Label, the room's length as one byte, the room (16 to 32 bytes), the member key (32 bytes) and the nonce (32 bytes). */
    private fun message(room: ByteArray, key: PublicKey, nonce: ByteArray): ByteArray {
        require(room.size in 16..32) { "a room is 16 to 32 bytes" }
        require(nonce.size == NONCE_SIZE) { "a nonce is $NONCE_SIZE bytes" }
        val out = ByteArrayOutputStream()
        out.write(LABEL)
        out.write(room.size)
        out.write(room)
        out.write(key.bytes())
        out.write(nonce)
        return out.toByteArray()
    }

    fun sign(identity: GroupIdentity, room: ByteArray, nonce: ByteArray): ByteArray {
        val message = message(room, identity.publicKey, nonce)
        val seed = identity.signingSeed()
        return try {
            Ed25519Sign(seed).sign(message)
        } finally {
            seed.fill(0)
        }
    }

    /** True only for a good signature by [key] over this room and nonce. Never throws on bad input. */
    fun verify(key: PublicKey, room: ByteArray, nonce: ByteArray, signature: ByteArray): Boolean {
        if (WeakKeys.isWeak(key.bytes())) return false
        return try {
            Ed25519Verify(key.bytes()).verify(signature, message(room, key, nonce))
            true
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
