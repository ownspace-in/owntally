package owntally.data.secrets

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.SecretKeySpec
import android.security.keystore.KeyInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import owntally.crypto.GroupKey
import owntally.crypto.MasterSeed
import owntally.model.GroupId

/** The real Android Keystore, on a device or emulator. */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretBoxInstrumentedTest {
    private val alias = "owntally-test-box-${System.nanoTime()}"
    private val context = "owntally/v1/test".toByteArray()

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @AfterTest
    fun removeTheTestKey() {
        keystore().deleteEntry(alias)
    }

    @Test
    fun theKeyIsCreatedOnceAndAnotherInstanceOpensWhatTheFirstSealed() {
        val first = KeystoreSecretBox.create(alias)
        val sealed = first.seal(ByteArray(32) { it.toByte() }, context)
        val second = KeystoreSecretBox.create(alias) // like the next start of the app
        assertContentEquals(ByteArray(32) { it.toByte() }, second.open(sealed, context))
    }

    @Test
    fun theKeyCannotBeReadOutOfTheKeystoreAndIsAes256Gcm() {
        KeystoreSecretBox.create(alias)
        val real = keystore().getKey(alias, null)
        assertNotNull(real, "the Keystore holds the key under its alias")
        assertNull((real as SecretKey).encoded, "the key bytes can never be exported")
        val info = SecretKeyFactory.getInstance(real.algorithm, "AndroidKeyStore").getKeySpec(real, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        assertTrue(info.blockModes.contains("GCM"))
        assertEquals(false, info.isUserAuthenticationRequired, "no unlock needed: the app lock is a separate feature")
    }

    @Test
    fun aDeletedKeyMakesStoredSecretsUnreadableNotACrashAndANewSeedCanBeSaved() {
        val dir = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "secret-test-deleted")
        dir.deleteRecursively()
        try {
            val seed = MasterSeed(ByteArray(64) { (it + 3).toByte() })
            val group = GroupId(ByteArray(16) { 7 })
            BoxedSecretStore(KeystoreSecretBox.create(alias), FileBlobStore(dir)).apply {
                saveMasterSeed(seed)
                saveGroupKey(group, 1, GroupKey(ByteArray(32) { 1 }))
            }
            keystore().deleteEntry(alias) // the key is gone, for example after a restore on another phone

            val after = BoxedSecretStore(KeystoreSecretBox.create(alias), FileBlobStore(dir))
            assertEquals(SecretLoad.Unreadable, after.loadMasterSeed())
            assertEquals(setOf(1L), after.loadKeyring(group).unreadableEpochs)

            val fresh = MasterSeed(ByteArray(64) { 9 })
            after.saveMasterSeed(fresh)
            assertEquals(SecretLoad.Found(fresh), after.loadMasterSeed())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun anotherKeystoreKeyCannotOpenTheSecrets() {
        val other = "$alias-other"
        try {
            val sealed = KeystoreSecretBox.create(alias).seal(ByteArray(16), context)
            assertNull(KeystoreSecretBox.create(other).open(sealed, context))
        } finally {
            keystore().deleteEntry(other)
        }
    }

    @Test
    fun aRawKeyOfTheSameSizeIsNotTheKeystoreKey() {
        // Sanity: a software AES key made here is unrelated to the Keystore key.
        val sealed = KeystoreSecretBox.create(alias).seal(ByteArray(16), context)
        assertNull(AeadSecretBox(newSoftwareAead()).open(sealed, context))
        assertNotNull(SecretKeySpec(ByteArray(32), "AES"))
    }

    private fun newSoftwareAead(): com.google.crypto.tink.Aead {
        com.google.crypto.tink.aead.AeadConfig.register()
        return com.google.crypto.tink.KeysetHandle.generateNew(com.google.crypto.tink.KeyTemplates.get("AES256_GCM"))
            .getPrimitive(com.google.crypto.tink.RegistryConfiguration.get(), com.google.crypto.tink.Aead::class.java)
    }
}
