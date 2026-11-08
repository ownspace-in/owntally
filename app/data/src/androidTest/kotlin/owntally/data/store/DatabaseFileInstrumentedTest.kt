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
import owntally.crypto.TicketRecord
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

    private fun open() = Room.databaseBuilder(context, OwnTallyDatabase::class.java, name).addMigrations(OwnTallyDatabase.MIGRATION_1_2, OwnTallyDatabase.MIGRATION_2_3, OwnTallyDatabase.MIGRATION_3_4, OwnTallyDatabase.MIGRATION_4_5).build()

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
        helper.runMigrationsAndValidate("$name-migration", 5, true, OwnTallyDatabase.MIGRATION_1_2, OwnTallyDatabase.MIGRATION_2_3, OwnTallyDatabase.MIGRATION_3_4, OwnTallyDatabase.MIGRATION_4_5).close()
        Room.databaseBuilder(context, OwnTallyDatabase::class.java, "$name-migration").addMigrations(OwnTallyDatabase.MIGRATION_1_2, OwnTallyDatabase.MIGRATION_2_3, OwnTallyDatabase.MIGRATION_3_4, OwnTallyDatabase.MIGRATION_4_5).build().also { db ->
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

    @Test
    fun aVersionTwoFileKeepsItsDataAndGetsTheEmptyProblemLogWhenMigratedToThree() {
        val group = ByteArray(16) { 5 }
        helper.createDatabase("$name-migration", 2).also { old ->
            old.execSQL("INSERT INTO groups (group_id, key_slot, created_at) VALUES (?, 1, 50)", arrayOf<Any>(group))
            old.execSQL("INSERT INTO join_requests (group_id, member_key, encryption_key, name, phone, received_at, state) VALUES (?, ?, ?, 'Ben', '98765 00001', 60, 0)", arrayOf<Any>(group, ByteArray(32) { 1 }, ByteArray(32) { 2 }))
            old.close()
        }
        helper.runMigrationsAndValidate("$name-migration", 5, true, OwnTallyDatabase.MIGRATION_2_3, OwnTallyDatabase.MIGRATION_3_4, OwnTallyDatabase.MIGRATION_4_5).close()
        Room.databaseBuilder(context, OwnTallyDatabase::class.java, "$name-migration").addMigrations(OwnTallyDatabase.MIGRATION_2_3, OwnTallyDatabase.MIGRATION_3_4, OwnTallyDatabase.MIGRATION_4_5).build().also { db ->
            assertEquals(listOf(GroupRow(GroupId(group), 1, 50)), RoomEventStore(db).groups())
            assertEquals(1, RoomJoinRequestStore(db).list(GroupId(group)).size, "the join request survived")
            val log = RoomSyncProblemLog(db)
            assertEquals(emptyList(), log.recent(GroupId(group), 10))
            log.add(SyncProblem(GroupId(group), PublicKey(ByteArray(32) { 7 }), ProblemKind.BAD_SIGNATURE, 2, 99))
            assertEquals(1, log.recent(GroupId(group), 10).size)
            RoomEventStore(db).deleteGroup(GroupId(group))
            assertEquals(emptyList(), log.recent(GroupId(group), 10), "the log goes with the group")
            db.close()
        }
    }

    @Test
    fun aVersionThreeFileKeepsItsDataAndGetsTheEmptyTicketTableWhenMigratedToFour() {
        val group = ByteArray(16) { 4 }
        helper.createDatabase("$name-migration", 3).also { old ->
            old.execSQL("INSERT INTO groups (group_id, key_slot, created_at) VALUES (?, 2, 40)", arrayOf<Any>(group))
            old.execSQL("INSERT INTO sync_problems (group_id, peer, kind, count, at_seconds) VALUES (?, ?, 0, 1, 41)", arrayOf<Any>(group, ByteArray(32) { 1 }))
            old.close()
        }
        helper.runMigrationsAndValidate("$name-migration", 5, true, OwnTallyDatabase.MIGRATION_3_4, OwnTallyDatabase.MIGRATION_4_5).close()
        Room.databaseBuilder(context, OwnTallyDatabase::class.java, "$name-migration").addMigrations(OwnTallyDatabase.MIGRATION_3_4, OwnTallyDatabase.MIGRATION_4_5).build().also { db ->
            assertEquals(listOf(GroupRow(GroupId(group), 2, 40)), RoomEventStore(db).groups())
            assertEquals(1, RoomSyncProblemLog(db).recent(GroupId(group), 10).size, "the problem log survived")
            val tickets = RoomTicketRecords(db)
            val id = ByteArray(16) { 9 }
            assertEquals(null, tickets.get(id))
            tickets.put(TicketRecord(id, PublicKey(ByteArray(32) { 3 }), GroupId(group), 1, 5_000, "", false))
            assertEquals(true, tickets.markUsed(id))
            RoomEventStore(db).deleteGroup(GroupId(group))
            assertEquals(null, tickets.get(id), "deleted with the group")
            db.close()
        }
    }

    @Test
    fun anIssuedTicketIsStillSingleUseAfterTheDatabaseFileIsClosedAndOpenedAgain() {
        val group = GroupId(ByteArray(16) { 6 })
        val host = PublicKey(ByteArray(32) { 0x11 })
        val key = owntally.crypto.GroupKey(ByteArray(32) { it.toByte() })
        val clock = owntally.crypto.Clock { 1_800_000_000L }
        fun registry(db: OwnTallyDatabase) = owntally.crypto.TicketRegistry(host, clock, records = RoomTicketRecords(db), keys = owntally.crypto.GroupKeys { _, _ -> key })
        val ticket = open().let { db ->
            RoomEventStore(db).addGroup(GroupRow(group, 0, 1), emptyList())
            registry(db).issue(group, 1, key).also { db.close() }
        }
        val used = open().let { db ->
            val r = registry(db)
            assertEquals(true, r.find(ticket.ticketId()) != null, "issued before, found after the restart")
            r.accept(ticket).also { db.close() }
        }
        assertEquals(true, used)
        open().let { db ->
            assertEquals(false, registry(db).accept(ticket), "used before the second restart, still used")
            val raw = db.openHelper.readableDatabase.query("SELECT * FROM issued_tickets").use { c -> (0 until c.columnCount).map { c.getColumnName(it) } }
            assertEquals(listOf("ticket_id", "host_id", "group_id", "epoch", "expires_at", "hint", "used"), raw, "no key column")
            db.close()
        }
    }

    @Test
    fun aVersionFourFileKeepsItsDataAndGetsTheEmptyPendingJoinTableWhenMigratedToFive() {
        val group = ByteArray(16) { 3 }
        helper.createDatabase("$name-migration", 4).also { old ->
            old.execSQL("INSERT INTO groups (group_id, key_slot, created_at) VALUES (?, 1, 30)", arrayOf<Any>(group))
            old.close()
        }
        helper.runMigrationsAndValidate("$name-migration", 5, true, OwnTallyDatabase.MIGRATION_4_5).close()
        Room.databaseBuilder(context, OwnTallyDatabase::class.java, "$name-migration").addMigrations(OwnTallyDatabase.MIGRATION_4_5).build().also { db ->
            assertEquals(listOf(GroupRow(GroupId(group), 1, 30)), RoomEventStore(db).groups())
            val pending = RoomPendingJoinStore(db)
            assertEquals(emptyList(), pending.all())
            val other = GroupId(ByteArray(16) { 9 })
            pending.put(PendingJoin(other, PublicKey(ByteArray(32) { 2 }), ByteArray(16) { 4 }, 7, 1, 5_000, "Rohan", "98765 43210", PendingState.WAITING, "Goa Trip", "Asha", 5, 100))
            val back = pending.get(other)!!
            assertEquals(PendingState.WAITING, back.state); assertEquals("Goa Trip", back.groupName); assertEquals(5, back.memberCount); assertEquals(7L, back.keySlot)
            pending.put(back.copy(state = PendingState.APPROVED))
            assertEquals(PendingState.APPROVED, pending.get(other)!!.state)
            pending.delete(other)
            assertEquals(null, pending.get(other))
            db.close()
        }
    }
}
