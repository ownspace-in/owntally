package owntally.data.secrets

import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * The [SecretBox] used on a phone: an AES-256-GCM key that lives in the Android Keystore and cannot be read out of it (Tink's
 * Keystore integration). The key is created the first time. Deleting it, or restoring the app on another phone, makes every
 * stored secret unreadable, which the rest of the app treats as "restore from the recovery phrase".
 */
object KeystoreSecretBox {
    const val DEFAULT_ALIAS = "owntally-secret-box-v1"

    fun create(alias: String = DEFAULT_ALIAS): SecretBox = try {
        AeadSecretBox(AndroidKeystoreKmsClient.getOrGenerateNewAeadKey("${AndroidKeystoreKmsClient.PREFIX}$alias"))
    } catch (e: GeneralSecurityException) {
        throw SecretStoreException("the Keystore is not available (${e.javaClass.simpleName})")
    } catch (e: IOException) {
        throw SecretStoreException("the Keystore is not available (${e.javaClass.simpleName})")
    }
}
