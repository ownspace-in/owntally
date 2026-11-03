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
import owntally.crypto.KeyDerivation
import owntally.crypto.OpenResult
import owntally.data.db.OwnTallyDatabase
import owntally.data.secrets.BoxedSecretStore
import owntally.data.secrets.FileBlobStore
import owntally.data.secrets.KeystoreSecretBox
import owntally.data.secrets.SecretLoad
import owntally.data.session.AddResult
import owntally.data.session.GroupSession
import owntally.data.store.RoomEventStore
import owntally.data.store.RoomSettingsStore
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.EventLog
import owntally.model.GroupId
import owntally.model.GroupState
import owntally.model.Money
import owntally.model.Payment
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

    private fun session(s: Stack, group: GroupId) = GroupSession(group, s.groups, s.secrets, s.identity, Clock.SYSTEM)

    /** A second member, made from the same seed (a stand-in for a friend until members can join for real). */
    private fun friend(s: Stack) = KeyDerivation.groupIdentity((s.secrets.loadMasterSeed() as SecretLoad.Found).value, 100)

    /** Asha adds a friend and pays 1,000 for both of them (and a second 400 paid by the friend), so there are real balances. */
    private fun fill(s: Stack, group: GroupId) {
        val sess = session(s, group)
        val me = sess.view.value.me
        val ben = friend(s)
        assertTrue(sess.append(EventBody.MemberJoined(ben.publicKey, ben.encryptionKey, "Ben", "98765 00002")) is AddResult.Added)
        assertTrue(sess.addExpense("Hotel", Money(100_000), me, 20_000, Split.Equal(setOf(me, ben.publicKey))) is AddResult.Added)
        assertTrue(sess.addExpense("Taxi", Money(40_000), ben.publicKey, 20_001, Split.Equal(setOf(me, ben.publicKey))) is AddResult.Added)
    }

    /** Hand calculation (paise): Hotel 50,000 each, Taxi 20,000 each. Asha +100,000 -50,000 -20,000 = +30,000; Ben +40,000 -50,000 -20,000 = -30,000. */
    private fun checkFilled(s: Stack, group: GroupId) {
        val v = session(s, group).view.value
        val ben = friend(s).publicKey
        assertEquals("Goa Trip", v.state.name)
        assertEquals(2, v.state.expenses.size)
        assertEquals(emptyList(), v.state.ignored)
        assertEquals(Money(30_000), v.balances.getValue(v.me))
        assertEquals(Money(-30_000), v.balances.getValue(ben))
        assertEquals(listOf(Payment(ben, v.me, Money(30_000))), v.plan)
        assertEquals("Asha", v.state.members.getValue(v.me).name)
        assertEquals("Ben", v.state.members.getValue(ben).name)
    }

    @Test
    fun aGroupMadeOnTheRealStackIsStillThereWhenEverythingIsOpenedAgain() {
        wipe()
        try {
            val first = open()
            first.identity.completeFirstRun(first.identity.newPhrase(), "Asha", "98765 43210")
            val group = first.groups.createGroup("Goa Trip")
            fill(first, group)
            val viewBefore = session(first, group).view.value
            first.db.close()

            val second = open() // like starting the app again
            assertEquals(IdentityState.READY, second.identity.state())
            assertEquals(listOf(group), second.groups.listGroups().map { it.groupId })
            checkFilled(second, group)
            assertEquals(viewBefore, session(second, group).view.value, "the same state after reopening everything")
            val state = replayOf(second, group)
            assertEquals(Role.OWNER, state.members.getValue(state.owner!!).role)
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
        fill(s, group)
        s.db.close()
    }

    @Test
    fun restartPhaseRead() {
        assumeTrue(args.getString("restartPhase") == "read")
        val s = open()
        assertEquals(IdentityState.READY, s.identity.state())
        val group = s.groups.listGroups().single().groupId
        checkFilled(s, group)
        s.db.close()
    }

    @Test
    fun restartPhaseCleanup() {
        assumeTrue(args.getString("restartPhase") == "cleanup")
        wipe()
    }
}
