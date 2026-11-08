package owntally.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** A group on this phone. Its name and members are inside the encrypted events, never in a column. */
@Entity(tableName = "groups", indices = [Index(value = ["key_slot"], unique = true)])
class GroupEntity(
    @PrimaryKey @ColumnInfo(name = "group_id", typeAffinity = ColumnInfo.BLOB) val groupId: ByteArray,
    @ColumnInfo(name = "key_slot") val keySlot: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * One envelope exactly as sent (encrypted and signed). The hash makes the same bytes unique per author and counter while still
 * allowing two different envelopes with the same id, which the protocol must be able to see (docs/PROTOCOL.md section 3).
 */
@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(entity = GroupEntity::class, parentColumns = ["group_id"], childColumns = ["group_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["group_id", "author", "counter", "envelope_hash"], unique = true)],
)
class EventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "row_id") val rowId: Long,
    @ColumnInfo(name = "group_id", typeAffinity = ColumnInfo.BLOB) val groupId: ByteArray,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val author: ByteArray,
    val counter: Long,
    @ColumnInfo(name = "envelope_hash", typeAffinity = ColumnInfo.BLOB) val envelopeHash: ByteArray,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val envelope: ByteArray,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
)

/** A request to join, received by this phone. Local only; the approval is the event. */
@Entity(
    tableName = "join_requests",
    primaryKeys = ["group_id", "member_key"],
    foreignKeys = [ForeignKey(entity = GroupEntity::class, parentColumns = ["group_id"], childColumns = ["group_id"], onDelete = ForeignKey.CASCADE)],
)
class JoinRequestEntity(
    @ColumnInfo(name = "group_id", typeAffinity = ColumnInfo.BLOB) val groupId: ByteArray,
    @ColumnInfo(name = "member_key", typeAffinity = ColumnInfo.BLOB) val memberKey: ByteArray,
    @ColumnInfo(name = "encryption_key", typeAffinity = ColumnInfo.BLOB) val encryptionKey: ByteArray,
    val name: String,
    val phone: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    /** 0 waiting, 1 approved, 2 declined. */
    val state: Int,
)

/** A line of the log of envelopes a peer sent that were dropped (S4). Counts and kinds only: there is no column for any envelope content. */
@Entity(
    tableName = "sync_problems",
    foreignKeys = [ForeignKey(entity = GroupEntity::class, parentColumns = ["group_id"], childColumns = ["group_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["group_id"])],
)
class SyncProblemEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "row_id") val rowId: Long,
    @ColumnInfo(name = "group_id", typeAffinity = ColumnInfo.BLOB) val groupId: ByteArray,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val peer: ByteArray,
    /** The position of the kind in `ProblemKind`. */
    val kind: Int,
    val count: Int,
    @ColumnInfo(name = "at_seconds") val atSeconds: Long,
)

/** A ticket the host issued (M6, T6.3). No group key here: it is read from the keyring when needed. */
@Entity(
    tableName = "issued_tickets",
    foreignKeys = [ForeignKey(entity = GroupEntity::class, parentColumns = ["group_id"], childColumns = ["group_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["group_id"])],
)
class IssuedTicketEntity(
    @PrimaryKey @ColumnInfo(name = "ticket_id", typeAffinity = ColumnInfo.BLOB) val ticketId: ByteArray,
    @ColumnInfo(name = "host_id", typeAffinity = ColumnInfo.BLOB) val hostId: ByteArray,
    @ColumnInfo(name = "group_id", typeAffinity = ColumnInfo.BLOB) val groupId: ByteArray,
    val epoch: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    val hint: String,
    val used: Boolean,
)

@Entity(tableName = "settings")
class SettingEntity(@PrimaryKey @ColumnInfo(name = "key") val key: String, @ColumnInfo(name = "value") val value: String)

@Dao
interface OwnTallyDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertGroup(group: GroupEntity)

    @Query("SELECT * FROM groups ORDER BY created_at, key_slot")
    fun groups(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE group_id = :id")
    fun group(id: ByteArray): GroupEntity?

    /** Returns -1 when exactly this envelope is already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertEvent(event: EventEntity): Long

    @Query("SELECT envelope FROM events WHERE group_id = :id ORDER BY row_id")
    fun envelopes(id: ByteArray): List<ByteArray>

    /** The events go with it (cascade). */
    @Query("DELETE FROM groups WHERE group_id = :id")
    fun deleteGroup(id: ByteArray)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putJoinRequest(request: JoinRequestEntity)

    @Query("SELECT * FROM join_requests WHERE group_id = :group AND member_key = :member")
    fun joinRequest(group: ByteArray, member: ByteArray): JoinRequestEntity?

    @Query("SELECT * FROM join_requests WHERE group_id = :group ORDER BY received_at, member_key")
    fun joinRequests(group: ByteArray): List<JoinRequestEntity>

    @Query("UPDATE join_requests SET state = :state WHERE group_id = :group AND member_key = :member")
    fun setJoinRequestState(group: ByteArray, member: ByteArray, state: Int)

    @Insert
    fun insertSyncProblem(problem: SyncProblemEntity)

    @Query("SELECT * FROM sync_problems WHERE group_id = :group ORDER BY row_id DESC LIMIT :limit")
    fun syncProblems(group: ByteArray, limit: Int): List<SyncProblemEntity>

    /** Keeps the newest [keep] lines of the group. */
    @Query("DELETE FROM sync_problems WHERE group_id = :group AND row_id NOT IN (SELECT row_id FROM sync_problems WHERE group_id = :group ORDER BY row_id DESC LIMIT :keep)")
    fun pruneSyncProblems(group: ByteArray, keep: Int)

    @Query("DELETE FROM sync_problems WHERE group_id = :group")
    fun clearSyncProblems(group: ByteArray)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putSetting(setting: SettingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putIssuedTicket(ticket: IssuedTicketEntity)

    @Query("SELECT * FROM issued_tickets WHERE ticket_id = :id")
    fun issuedTicket(id: ByteArray): IssuedTicketEntity?

    /** Returns the number of rows changed: 1 only for a ticket that existed and was not yet used. */
    @Query("UPDATE issued_tickets SET used = 1 WHERE ticket_id = :id AND used = 0")
    fun markIssuedTicketUsed(id: ByteArray): Int

    @Query("SELECT * FROM issued_tickets WHERE group_id = :group AND used = 0 AND expires_at > :now ORDER BY expires_at DESC")
    fun openTickets(group: ByteArray, now: Long): List<IssuedTicketEntity>

    @Query("DELETE FROM issued_tickets WHERE expires_at <= :now")
    fun deleteExpiredTickets(now: Long)

    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun setting(key: String): String?

    @Query("DELETE FROM settings WHERE `key` = :key")
    fun deleteSetting(key: String)
}

@Database(entities = [GroupEntity::class, EventEntity::class, SettingEntity::class, JoinRequestEntity::class, SyncProblemEntity::class, IssuedTicketEntity::class], version = 4, exportSchema = true)
abstract class OwnTallyDatabase : RoomDatabase() {
    abstract fun dao(): OwnTallyDao

    companion object {
        const val FILE_NAME = "owntally.db"

        /** Version 4 adds the host's record of issued invitation tickets (M6, T6.3); nothing else changes. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `issued_tickets` (`ticket_id` BLOB NOT NULL, `host_id` BLOB NOT NULL, `group_id` BLOB NOT NULL, `epoch` INTEGER NOT NULL, " +
                        "`expires_at` INTEGER NOT NULL, `hint` TEXT NOT NULL, `used` INTEGER NOT NULL, PRIMARY KEY(`ticket_id`), " +
                        "FOREIGN KEY(`group_id`) REFERENCES `groups`(`group_id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_issued_tickets_group_id` ON `issued_tickets` (`group_id`)")
            }
        }

        /** Version 3 adds the log of dropped envelopes (M5, T5.6); nothing else changes. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_problems` (`row_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `group_id` BLOB NOT NULL, `peer` BLOB NOT NULL, " +
                        "`kind` INTEGER NOT NULL, `count` INTEGER NOT NULL, `at_seconds` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`group_id`) REFERENCES `groups`(`group_id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_problems_group_id` ON `sync_problems` (`group_id`)")
            }
        }

        /** Version 2 adds the table of join requests; nothing else changes (M4, T4.4). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `join_requests` (`group_id` BLOB NOT NULL, `member_key` BLOB NOT NULL, `encryption_key` BLOB NOT NULL, " +
                        "`name` TEXT NOT NULL, `phone` TEXT NOT NULL, `received_at` INTEGER NOT NULL, `state` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`group_id`, `member_key`), FOREIGN KEY(`group_id`) REFERENCES `groups`(`group_id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }

        /**
         * The database in the app's private storage (never backed up, see the manifest rules). There is deliberately no
         * `fallbackToDestructiveMigration`: a missing migration must be an error, never a silent loss of the person's events.
         */
        fun open(context: Context): OwnTallyDatabase = Room.databaseBuilder(context.applicationContext, OwnTallyDatabase::class.java, FILE_NAME).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

        fun inMemory(context: Context): OwnTallyDatabase = Room.inMemoryDatabaseBuilder(context.applicationContext, OwnTallyDatabase::class.java).build()
    }
}
