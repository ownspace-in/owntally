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

    private fun open() = Room.databaseBuilder(context, OwnTallyDatabase::class.java, name).build()

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
    fun theVersionOneFileMadeFromTheExportedSchemaOpensWithTheCurrentCode() {
        // Builds the file from schemas/.../1.json (not from the code) and lets Room check the code agrees with it.
        helper.createDatabase("$name-migration", 1).close()
        helper.runMigrationsAndValidate("$name-migration", 1, true)
        Room.databaseBuilder(context, OwnTallyDatabase::class.java, "$name-migration").build().also { db ->
            assertEquals(emptyList(), RoomEventStore(db).groups())
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
