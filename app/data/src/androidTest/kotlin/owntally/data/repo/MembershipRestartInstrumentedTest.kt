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
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import owntally.crypto.Clock
import owntally.crypto.KeyDerivation
import owntally.data.db.OwnTallyDatabase
import owntally.data.secrets.BoxedSecretStore
import owntally.data.secrets.FileBlobStore
import owntally.data.secrets.KeystoreSecretBox
import owntally.data.secrets.SecretLoad
import owntally.data.session.AddResult
import owntally.data.session.GroupSession
import owntally.data.store.RoomEventStore
import owntally.data.store.RoomSettingsStore
import owntally.model.GroupId
import owntally.model.Role
import owntally.model.Split

/**
 * M4 hand-back: add a member, remove one, change the key, make an admin, offer ownership, then close everything and open it again
 * (in the same process, and, with the phases at the bottom, in a new process). The state, the members, the epochs and the keys must be the same.
 */
@RunWith(AndroidJUnit4::class)
class MembershipRestartInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val args = InstrumentationRegistry.getArguments()
    private val dbName = "owntally-membership-test.db"
    private val alias = "owntally-membership-test-key"
    private val secretsDir = File(context.noBackupFilesDir, "secrets-membership-test")

    private class Stack(val db: OwnTallyDatabase, val identity: IdentityRepository, val groups: GroupRepository, val secrets: BoxedSecretStore)

    private fun open(): Stack {
        val db = Room.databaseBuilder(context, OwnTallyDatabase::class.java, dbName).addMigrations(OwnTallyDatabase.MIGRATION_1_2, OwnTallyDatabase.MIGRATION_2_3, OwnTallyDatabase.MIGRATION_3_4, OwnTallyDatabase.MIGRATION_4_5).build()
        val secrets = BoxedSecretStore(KeystoreSecretBox.create(alias), FileBlobStore(secretsDir))
        val identity = IdentityRepository(secrets, RoomSettingsStore(db))
        return Stack(db, identity, GroupRepository(secrets, RoomEventStore(db), identity, Clock.SYSTEM, SecureRandom()), secrets)
    }

    private fun wipe() {
        context.deleteDatabase(dbName)
        secretsDir.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    private fun session(s: Stack, group: GroupId) = GroupSession(group, s.groups, s.secrets, s.identity, Clock.SYSTEM)
    private fun person(s: Stack, n: Long) = KeyDerivation.groupIdentity((s.secrets.loadMasterSeed() as SecretLoad.Found).value, 100 + n)

    private fun fill(s: Stack, group: GroupId) {
        val sess = session(s, group)
        val ben = person(s, 1)
        val chitra = person(s, 2)
        assertTrue(sess.approveJoin(ben.publicKey, ben.encryptionKey, "Ben", "98765 00002") is AddResult.Added)
        assertTrue(sess.approveJoin(chitra.publicKey, chitra.encryptionKey, "Chitra", "98765 00003") is AddResult.Added)
        assertTrue(sess.removeMember(chitra.publicKey) is AddResult.Added) // epoch 2
        assertTrue(sess.rotateKey() is AddResult.Added) // epoch 3
        assertTrue(sess.makeAdmin(ben.publicKey) is AddResult.Added)
        assertTrue(sess.offerOwnership(ben.publicKey) is AddResult.Added)
        val me = sess.view.value.me
        assertTrue(sess.addExpense("Dinner", owntally.model.Money(90_000), me, 20_000, Split.Equal(setOf(me, ben.publicKey))) is AddResult.Added)
    }

    private fun check(s: Stack, group: GroupId) {
        val v = session(s, group).view.value
        val ben = person(s, 1).publicKey
        val chitra = person(s, 2).publicKey
        assertEquals(3L, v.state.epoch)
        assertEquals(setOf(1L, 2L, 3L), s.secrets.loadKeyring(group).keyring.epochs(), "every key is still on the phone")
        assertEquals(emptyList(), v.state.ignored)
        assertEquals(setOf(v.me, ben), v.state.currentMembers.keys)
        assertTrue(v.state.members.getValue(chitra).removed)
        assertEquals(Role.ADMIN, v.state.members.getValue(ben).role)
        assertEquals(ben, v.state.pendingOffer)
        assertEquals(1, v.state.expenses.size)
        assertEquals(owntally.model.Money(45_000), v.balances.getValue(v.me))
        assertEquals(0, v.counts.waitingForKey.size)
        assertEquals(0, v.counts.unreadable)
    }

    @Test
    fun membershipChangesAndTheKeysSurviveClosingAndOpeningEverythingAgain() {
        wipe()
        try {
            val first = open()
            first.identity.completeFirstRun(first.identity.newPhrase(), "Asha", "98765 43210")
            val group = first.groups.createGroup("Goa Trip")
            fill(first, group)
            val before = session(first, group).view.value
            first.db.close()
            val second = open()
            check(second, group)
            assertEquals(before, session(second, group).view.value, "the same state after reopening")
            second.db.close()
        } finally {
            wipe()
        }
    }

    /** Two phases for a real process restart (see docs/tasks/M4-membership.md). Skipped otherwise. */
    @Test
    fun restartPhaseWrite() {
        assumeTrue(args.getString("restartPhase") == "write")
        wipe()
        val s = open()
        s.identity.completeFirstRun(s.identity.newPhrase(), "Asha", "98765 43210")
        fill(s, s.groups.createGroup("Goa Trip"))
        s.db.close()
    }

    @Test
    fun restartPhaseRead() {
        assumeTrue(args.getString("restartPhase") == "read")
        val s = open()
        check(s, s.groups.listGroups().single().groupId)
        s.db.close()
    }

    @Test
    fun restartPhaseCleanup() {
        assumeTrue(args.getString("restartPhase") == "cleanup")
        wipe()
    }
}
