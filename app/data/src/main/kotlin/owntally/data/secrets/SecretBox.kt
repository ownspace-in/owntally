package owntally.data.secrets

import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException

/** Something went wrong keeping a secret. The message never contains the secret, a key or a group id. */
class SecretStoreException(message: String) : Exception(message)

/**
 * Seals secrets so they can be kept on disk, and opens them again. [context] is bound into the sealed bytes (as associated data),
 * so a blob made for one place cannot be opened as another: the master seed, and each epoch's key of each group, have their own.
 */
interface SecretBox {
    /** Throws [SecretStoreException] if sealing is impossible (for example the Keystore is unavailable). */
    fun seal(plain: ByteArray, context: ByteArray): ByteArray

    /** The secret, or null if [sealed] was not made by this box for this [context] or has been changed. Never throws. */
    fun open(sealed: ByteArray, context: ByteArray): ByteArray?
}

/** A [SecretBox] on a Tink [Aead]. On Android the Aead is backed by a key in the Android Keystore (see [KeystoreSecretBox]). */
class AeadSecretBox(private val aead: Aead) : SecretBox {
    override fun seal(plain: ByteArray, context: ByteArray): ByteArray = try {
        aead.encrypt(plain, context)
    } catch (e: GeneralSecurityException) {
        // The exception's own message is dropped on purpose: it could contain what was being sealed.
        throw SecretStoreException("could not seal (${e.javaClass.simpleName})")
    }

    override fun open(sealed: ByteArray, context: ByteArray): ByteArray? = try {
        aead.decrypt(sealed, context)
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: RuntimeException) {
        null
    }
}
