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
    fun putSetting(setting: SettingEntity)

    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun setting(key: String): String?
}

@Database(entities = [GroupEntity::class, EventEntity::class, SettingEntity::class], version = 1, exportSchema = true)
abstract class OwnTallyDatabase : RoomDatabase() {
    abstract fun dao(): OwnTallyDao

    companion object {
        const val FILE_NAME = "owntally.db"

        /**
         * The database in the app's private storage (never backed up, see the manifest rules). There is deliberately no
         * `fallbackToDestructiveMigration`: a missing migration must be an error, never a silent loss of the person's events.
         */
        fun open(context: Context): OwnTallyDatabase = Room.databaseBuilder(context.applicationContext, OwnTallyDatabase::class.java, FILE_NAME).build()

        fun inMemory(context: Context): OwnTallyDatabase = Room.inMemoryDatabaseBuilder(context.applicationContext, OwnTallyDatabase::class.java).build()
    }
}
