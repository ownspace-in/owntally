package owntally.data.secrets

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import owntally.crypto.GroupKey
import owntally.crypto.MasterSeed
import owntally.model.GroupId

/**
 * "The secret survives an app restart", for real: run the write phase, let the process end, then run the read phase in a new
 * process (see docs/tasks/M3-storage-app-shell.md for the commands). Without a `restartPhase` argument both do nothing, so a
 * normal full run of the instrumented tests is not affected.
 */
@RunWith(AndroidJUnit4::class)
class RestartInstrumentedTest {
    private val args = InstrumentationRegistry.getArguments()
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "secrets-restart-test")
    private val alias = "owntally-restart-test-key"
    private val seed = MasterSeed(ByteArray(64) { (it * 3 + 1).toByte() })
    private val group = GroupId(ByteArray(16) { 0x5A })
    private fun key(n: Int) = GroupKey(ByteArray(32) { (n * 40 + it).toByte() })

    private fun store() = BoxedSecretStore(KeystoreSecretBox.create(alias), FileBlobStore(dir))

    @Test
    fun write() {
        assumeTrue(args.getString("restartPhase") == "write")
        dir.deleteRecursively()
        store().apply {
            saveMasterSeed(seed)
            saveGroupKey(group, 1, key(1))
            saveGroupKey(group, 2, key(2))
        }
        assertTrue(dir.list()!!.size == 3)
    }

    @Test
    fun read() {
        assumeTrue(args.getString("restartPhase") == "read")
        val s = store()
        assertEquals(SecretLoad.Found(seed), s.loadMasterSeed(), "the seed written by the earlier process")
        val ring = s.loadKeyring(group)
        assertEquals(setOf(1L, 2L), ring.keyring.epochs())
        assertEquals(key(1), ring.keyring[1])
        assertEquals(key(2), ring.keyring[2])
        assertEquals(emptySet(), ring.unreadableEpochs)
    }

    @Test
    fun cleanUp() {
        assumeTrue(args.getString("restartPhase") == "cleanup")
        dir.deleteRecursively()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

}
