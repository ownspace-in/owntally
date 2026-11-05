package owntally.data.store

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import owntally.data.db.OwnTallyDatabase
import owntally.model.EncryptionKey
import owntally.model.GroupId
import owntally.model.PublicKey

/** The database as a file: made from nothing, kept across closing and reopening, and agreeing with the exported schema. */
@RunWith(AndroidJUnit4::class)
class DatabaseFileInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "owntally-file-test-${System.nanoTime()}.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), OwnTallyDatabase::class.java)

    @AfterTest
    fun remove() {
        context.deleteDatabase(name)
        context.deleteDatabase("$name-migration")
    }

    private fun open() = Room.databaseBuilder(context, OwnTallyDatabase::class.java, name).addMigrations(OwnTallyDatabase.MIGRATION_1_2).build()

    @Test
    fun aDatabaseIsMadeFromNothingAndTheDataIsStillThereAfterClosingAndOpeningAgain() {
        assertFalse(context.getDatabasePath(name).exists())
        val group = GroupId(ByteArray(16) { 4 })
        open().also { db ->
            val store = RoomEventStore(db)
            store.addGroup(GroupRow(group, 0, 100), listOf(StoredEnvelope(PublicKey(ByteArray(32)), 1, ByteArray(40) { 5 })))
            RoomSettingsStore(db).put("next_slot", "1")
            db.close()
        }
        assertTrue(context.getDatabasePath(name).exists())
        open().also { db ->
            assertEquals(listOf(GroupRow(group, 0, 100)), RoomEventStore(db).groups())
            assertEquals(1, RoomEventStore(db).envelopes(group).size)
            assertEquals("1", RoomSettingsStore(db).get("next_slot"))
            db.close()
        }
    }

    @Test
    fun aVersionOneFileMadeFromTheExportedSchemaKeepsItsGroupsAndEventsWhenMigratedToTheCurrentVersion() {
        // Builds the file from schemas/.../1.json (what the M3 build created), puts data in it, migrates, and lets Room check the code agrees with 2.json.
        val group = ByteArray(16) { 6 }
        helper.createDatabase("$name-migration", 1).also { old ->
            old.execSQL("INSERT INTO groups (group_id, key_slot, created_at) VALUES (?, 3, 77)", arrayOf<Any>(group))
            old.execSQL(
                "INSERT INTO events (group_id, author, counter, envelope_hash, envelope, received_at) VALUES (?, ?, 1, ?, ?, 78)",
                arrayOf<Any>(group, ByteArray(32) { 1 }, ByteArray(32) { 2 }, ByteArray(40) { 3 }),
            )
            old.execSQL("INSERT INTO settings (`key`, `value`) VALUES ('next_slot', '4')")
            old.close()
        }
        helper.runMigrationsAndValidate("$name-migration", 2, true, OwnTallyDatabase.MIGRATION_1_2).close()
        Room.databaseBuilder(context, OwnTallyDatabase::class.java, "$name-migration").addMigrations(OwnTallyDatabase.MIGRATION_1_2).build().also { db ->
            assertEquals(listOf(GroupRow(GroupId(group), 3, 77)), RoomEventStore(db).groups())
            assertEquals(1, RoomEventStore(db).envelopes(GroupId(group)).size)
            assertEquals("4", RoomSettingsStore(db).get("next_slot"))
            val requests = RoomJoinRequestStore(db)
            assertEquals(emptyList(), requests.list(GroupId(group)))
            requests.put(JoinRequest(GroupId(group), PublicKey(ByteArray(32) { 9 }), EncryptionKey(ByteArray(32) { 8 }), "Ben", "98765 00001", 100, JoinState.WAITING))
            assertEquals(1, requests.list(GroupId(group)).size)
            db.close()
        }
    }

    @Test
    fun foreignKeysAreOnSoDeletingAGroupCannotLeaveOrphanEvents() {
        open().also { db ->
            val store = RoomEventStore(db)
            val group = GroupId(ByteArray(16) { 8 })
            store.addGroup(GroupRow(group, 0, 1), listOf(StoredEnvelope(PublicKey(ByteArray(32)), 1, ByteArray(10))))
            store.deleteGroup(group)
            val count = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM events").use { it.moveToFirst(); it.getInt(0) }
            assertEquals(0, count)
            db.close()
        }
    }
}
