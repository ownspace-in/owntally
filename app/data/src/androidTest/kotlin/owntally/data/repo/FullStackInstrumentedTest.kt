package owntally.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import owntally.crypto.Clock
import owntally.crypto.EventSealing
import owntally.crypto.OpenResult
import owntally.data.db.OwnTallyDatabase
import owntally.data.secrets.BoxedSecretStore
import owntally.data.secrets.FileBlobStore
import owntally.data.secrets.KeystoreSecretBox
import owntally.data.store.RoomEventStore
import owntally.data.store.RoomSettingsStore
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.EventLog
import owntally.model.GroupId
import owntally.model.GroupState
import owntally.model.Money
import owntally.model.Role
import owntally.model.Split
import owntally.model.replay

/**
 * The real pieces together: Room on a file, secrets sealed by the Android Keystore in files. "Restart" is making every object
 * again from the same files, and, for the real thing, a new process (see RestartPhases below).
 */
@RunWith(AndroidJUnit4::class)
class FullStackInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val args = InstrumentationRegistry.getArguments()
    private val dbName = "owntally-fullstack-test.db"
    private val alias = "owntally-fullstack-test-key"
    private val secretsDir = File(context.noBackupFilesDir, "secrets-fullstack-test")

    private class Stack(val db: OwnTallyDatabase, val identity: IdentityRepository, val groups: GroupRepository, val secrets: BoxedSecretStore)

    private fun open(): Stack {
        val db = Room.databaseBuilder(context, OwnTallyDatabase::class.java, dbName).build()
        val secrets = BoxedSecretStore(KeystoreSecretBox.create(alias), FileBlobStore(secretsDir))
        val identity = IdentityRepository(secrets, RoomSettingsStore(db))
        return Stack(db, identity, GroupRepository(secrets, RoomEventStore(db), identity, Clock.SYSTEM, SecureRandom()), secrets)
    }

    private fun wipe() {
        context.deleteDatabase(dbName)
        secretsDir.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    private fun replayOf(s: Stack, group: GroupId): GroupState {
        val ring = s.secrets.loadKeyring(group).keyring
        val log = EventLog()
        for (e in s.groups.envelopes(group).envelopes) (EventSealing.open(e, ring) as OpenResult.Opened).let { log.append(it.event) }
        return replay(log)
    }

    private fun addExpense(s: Stack, group: GroupId, counter: Long) {
        val slot = s.groups.group(group)!!.keySlot
        val me = s.identity.groupIdentity(slot)!!
        val key = s.secrets.loadKeyring(group).keyring[1]!!
        val event = Event(1, me.publicKey, counter, mapOf(me.publicKey to counter - 1), 5, EventBody.ExpenseAdded("Dinner", Money(60000), me.publicKey, 20000, Split.Equal(setOf(me.publicKey))))
        assertEquals(AppendResult.Added, s.groups.appendEnvelope(group, EventSealing.seal(me, group, 1, key, event)))
    }

    @Test
    fun aGroupMadeOnTheRealStackIsStillThereWhenEverythingIsOpenedAgain() {
        wipe()
        try {
            val first = open()
            first.identity.completeFirstRun(first.identity.newPhrase(), "Asha", "98765 43210")
            val group = first.groups.createGroup("Goa Trip")
            addExpense(first, group, 3)
            first.db.close()

            val second = open() // like starting the app again
            assertEquals(IdentityState.READY, second.identity.state())
            assertEquals(listOf(group), second.groups.listGroups().map { it.groupId })
            val state = replayOf(second, group)
            assertEquals("Goa Trip", state.name)
            assertEquals(1, state.expenses.size)
            assertEquals(Role.OWNER, state.members.getValue(state.owner!!).role)
            assertEquals("Asha", state.members.getValue(state.owner!!).name)
            second.db.close()
        } finally {
            wipe()
        }
    }

    @Test
    fun ifTheKeystoreKeyIsLostTheGroupsStayButTheirEventsCannotBeOpened() {
        wipe()
        try {
            val first = open()
            first.identity.completeFirstRun(first.identity.newPhrase(), "Asha", "98765 43210")
            val group = first.groups.createGroup("Goa Trip")
            first.db.close()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

            val second = open()
            assertEquals(IdentityState.SEED_UNREADABLE, second.identity.state())
            assertEquals(listOf(group), second.groups.listGroups().map { it.groupId }, "the rows are not lost")
            assertEquals(setOf(1L), second.secrets.loadKeyring(group).unreadableEpochs)
            assertTrue(second.secrets.loadKeyring(group).keyring.epochs().isEmpty())
            assertNull(second.identity.groupIdentity(0))
            second.db.close()
        } finally {
            wipe()
        }
    }

    /** Two phases for a real process restart, run by the commands in docs/tasks/M3-storage-app-shell.md. Skipped otherwise. */
    @Test
    fun restartPhaseWrite() {
        assumeTrue(args.getString("restartPhase") == "write")
        wipe()
        val s = open()
        s.identity.completeFirstRun(s.identity.newPhrase(), "Asha", "98765 43210")
        val group = s.groups.createGroup("Goa Trip")
        addExpense(s, group, 3)
        addExpense(s, group, 4)
        s.db.close()
    }

    @Test
    fun restartPhaseRead() {
        assumeTrue(args.getString("restartPhase") == "read")
        val s = open()
        assertEquals(IdentityState.READY, s.identity.state())
        val group = s.groups.listGroups().single().groupId
        val state = replayOf(s, group)
        assertEquals("Goa Trip", state.name)
        assertEquals(2, state.expenses.size)
        assertEquals("Asha", state.members.getValue(state.owner!!).name)
        assertEquals(emptyList(), state.ignored)
        s.db.close()
    }

    @Test
    fun restartPhaseCleanup() {
        assumeTrue(args.getString("restartPhase") == "cleanup")
        wipe()
    }
}
