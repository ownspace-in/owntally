package owntally.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.security.KeyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import owntally.crypto.KeyDerivation
import owntally.crypto.MasterSeed
import owntally.data.db.OwnTallyDatabase
import owntally.data.secrets.KeystoreSecretBox
import owntally.data.session.PeerState
import owntally.model.EventBody

/** The background sync job (decision B1, M7): scheduled once with the network as its only condition, and one pass does what the app does in front. */
@RunWith(AndroidJUnit4::class)
class BackgroundSyncInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = context as OwnTallyApplication

    private fun wipe() {
        context.deleteDatabase(OwnTallyDatabase.FILE_NAME)
        File(context.noBackupFilesDir, "secrets").deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KeystoreSecretBox.DEFAULT_ALIAS)
        app.resetContainerForTests()
    }

    @Test
    fun theJobIsScheduledOnceEveryFifteenMinutesWithOnlyTheNetworkAsACondition() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        BackgroundSync.schedule(context)
        BackgroundSync.schedule(context) // starting the app again keeps the job that is there
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackgroundSync.NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
        assertFalse(infos.single().constraints.requiresCharging())
        assertFalse(infos.single().constraints.requiresDeviceIdle())
        assertEquals(15L * 60_000, BackgroundSync.request().workSpec.intervalDuration)
    }

    @Test
    fun aPhoneNotSetUpYetDoesNothingAndTheJobStillSucceeds() {
        wipe()
        assertFalse(runBlocking { app.container.backgroundPass() })
        assertEquals(ListenableWorker.Result.success(), runBlocking { TestListenableWorkerBuilder<SyncWorker>(context).build().doWork() })
    }

    @Test
    fun onePassTriesEveryMemberOfEveryGroupAndEndsWithTheStatusSaved() {
        wipe()
        try {
            val c = app.container
            c.syncOnStart = false
            c.identity.let { it.completeFirstRun(it.newPhrase(), "Asha", "98765 43210") }
            val group = c.sessions.createGroup("Goa Trip", "INR", 0)
            val friend = KeyDerivation.groupIdentity(MasterSeed(ByteArray(64) { 5 }), 1)
            c.sessions.session(group).append(EventBody.MemberJoined(friend.publicKey, friend.encryptionKey, "Ben", "98765 00001"))
            assertEquals(null, c.sync.status(group).value.peers[friend.publicKey]?.takeIf { it.attempts > 0 }, "nothing tried before the job")

            val result = runBlocking { TestListenableWorkerBuilder<SyncWorker>(context).build().doWork() }
            assertEquals(ListenableWorker.Result.success(), result)
            val status = c.sync.status(group).value.peers[friend.publicKey]!!
            assertTrue(status.attempts >= 1, "the pass tried Ben")
            assertTrue(status.state == PeerState.UNREACHABLE || status.state == PeerState.FAILED, "and he is not on this network: ${status.state}")
        } finally {
            wipe()
        }
    }

    @Test
    fun whileTheAppIsInFrontTheJobLeavesTheSyncingToTheAppsOwnCoordinators() {
        wipe()
        try {
            val c = app.container
            c.identity.let { it.completeFirstRun(it.newPhrase(), "Asha", "98765 43210") }
            c.sessions.createGroup("Goa Trip", "INR", 0)
            c.beginSyncing()
            assertFalse(runBlocking { c.backgroundPass() })
            c.endSyncing()
            assertTrue(runBlocking { c.backgroundPass(timeoutMs = 20_000) }, "and once the app is away, the pass runs")
        } finally {
            wipe()
        }
    }
}
