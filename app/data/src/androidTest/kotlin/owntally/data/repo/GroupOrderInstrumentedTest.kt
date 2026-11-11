package owntally.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import owntally.data.db.OwnTallyDatabase
import owntally.data.store.GroupRow
import owntally.data.store.RoomEventStore
import owntally.model.GroupId

/**
 * M11: a group's key slot is a hash of its id, so it cannot break a tie between groups made in the same second; they must come back in the order
 * they were made (the list on the Groups tab). Checked on a real Room database.
 */
@RunWith(AndroidJUnit4::class)
class GroupOrderInstrumentedTest {
    @Test
    fun groupsMadeInTheSameSecondComeBackInTheOrderTheyWereMade() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), OwnTallyDatabase::class.java).build()
        try {
            val store = RoomEventStore(db)
            // Slots and ids in the opposite order to creation, so neither can explain a pass.
            val rows = listOf(9L, 5L, 1L).mapIndexed { i, slot -> GroupRow(GroupId(ByteArray(16) { (3 - i).toByte() }), slot, 1_800_000_000L) }
            rows.forEach { store.addGroup(it, emptyList()) }
            assertEquals(rows.map { it.groupId }, store.groups().map { it.groupId })
            store.addGroup(GroupRow(GroupId(ByteArray(16) { 50 }), 7L, 1_799_999_999L), emptyList())
            assertEquals(GroupId(ByteArray(16) { 50 }), store.groups().first().groupId, "an earlier second still comes first")
        } finally {
            db.close()
        }
    }
}
