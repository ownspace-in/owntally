package owntally.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import kotlin.test.Test
import org.junit.runner.RunWith
import owntally.data.db.OwnTallyDatabase
import owntally.data.secrets.KeystoreSecretBox
import owntally.ui.lock.LockMode
import owntally.ui.lock.LockSettingStore

/**
 * Not a check, so it does nothing in a normal run: it runs only when started with `-e lockSetup yes`. It leaves the app in a known state for the hand-driven lock test in docs/tasks/M3-storage-app-shell.md (first run
 * done, one group named "Goa Trip", the phone lock on or off) so the real system prompt can be driven with adb afterwards:
 * `adb shell am instrument -w -e lockSetup yes -e class owntally.app.LockSetupInstrumentedTest#leaveTheAppWithTheLockOn in.ownspace.owntally.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class LockSetupInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val container get() = (context as OwnTallyApplication).container

    private fun wipe() {
        context.deleteDatabase(OwnTallyDatabase.FILE_NAME)
        File(context.noBackupFilesDir, "secrets").deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KeystoreSecretBox.DEFAULT_ALIAS)
        (context as OwnTallyApplication).resetContainerForTests()
    }

    private fun onlyWhenAsked() = org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("lockSetup") == "yes")

    @Test
    fun leaveTheAppWithTheLockOn() {
        onlyWhenAsked()
        wipe()
        container.firstRun.complete(container.firstRun.newPhrase(), "Asha", "98765 43210")
        container.sessions.createGroup("Goa Trip", "INR", 0)
        LockSettingStore(container.settings).save(LockMode.PHONE)
    }

    @Test
    fun leaveTheAppWithTheLockOff() {
        onlyWhenAsked()
        wipe()
        container.firstRun.complete(container.firstRun.newPhrase(), "Asha", "98765 43210")
        container.sessions.createGroup("Goa Trip", "INR", 0)
    }
}
