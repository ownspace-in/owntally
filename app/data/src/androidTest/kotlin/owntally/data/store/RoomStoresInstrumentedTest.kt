package owntally.data.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import owntally.data.db.OwnTallyDatabase
import owntally.model.EncryptionKey
import owntally.model.GroupId
import owntally.model.PublicKey

/** The same behaviour as the in-memory store the JVM tests use, on the real database. */
@RunWith(AndroidJUnit4::class)
class RoomStoresInstrumentedTest {
    private lateinit var db: OwnTallyDatabase
    private lateinit var store: RoomEventStore
    private val a = GroupId(ByteArray(16) { 1 })
    private val b = GroupId(ByteArray(16) { 2 })
    private val author = PublicKey(ByteArray(32) { 7 })
    private fun env(n: Int, size: Int = 50) = StoredEnvelope(author, n.toLong(), ByteArray(size) { (n + it).toByte() })

    @BeforeTest
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), OwnTallyDatabase::class.java).build()
        store = RoomEventStore(db) { 1_800_000_000 }
    }

    @AfterTest
    fun close() = db.close()

    @Test
    fun aGroupAndItsFirstEnvelopesAreStoredTogether() {
        store.addGroup(GroupRow(a, 0, 100), listOf(env(1), env(2)))
        assertEquals(listOf(GroupRow(a, 0, 100)), store.groups())
        assertEquals(GroupRow(a, 0, 100), store.group(a))
        assertEquals(2, store.envelopes(a).size)
        assertNull(store.group(b))
    }

    @Test
    fun theBytesComeBackExactly() {
        val big = StoredEnvelope(author, 1, ByteArray(60_000) { (it * 31).toByte() })
        store.addGroup(GroupRow(a, 0, 100), listOf(big))
        assertContentEquals(big.bytes, store.envelopes(a).single())
    }

    @Test
    fun aGroupIdOrSlotAlreadyUsedIsRefusedAndNothingIsAdded() {
        store.addGroup(GroupRow(a, 0, 100), listOf(env(1)))
        assertFailsWith<Exception> { store.addGroup(GroupRow(a, 5, 100), listOf(env(9))) }
        assertFailsWith<Exception> { store.addGroup(GroupRow(b, 0, 100), listOf(env(9))) }
        assertEquals(listOf(a), store.groups().map { it.groupId })
        assertEquals(1, store.envelopes(a).size)
        assertEquals(0, store.envelopes(b).size)
    }

    @Test
    fun theSameEnvelopeIsStoredOnceAndDifferentOnesWithTheSameIdAreBothKept() {
        store.addGroup(GroupRow(a, 0, 100), emptyList())
        assertTrue(store.addEnvelope(a, env(3)))
        assertFalse(store.addEnvelope(a, env(3)), "identical bytes")
        val other = StoredEnvelope(author, 3, ByteArray(50) { 99 }) // same author and counter, different content
        assertTrue(store.addEnvelope(a, other))
        assertEquals(2, store.envelopes(a).size)
    }

    @Test
    fun anEnvelopeForAGroupThatDoesNotExistIsAnError() {
        assertFailsWith<IllegalStateException> { store.addEnvelope(a, env(1)) }
    }

    @Test
    fun envelopesComeBackInTheOrderTheyWereAdded() {
        store.addGroup(GroupRow(a, 0, 100), listOf(env(5)))
        store.addEnvelope(a, env(2))
        store.addEnvelope(a, env(9))
        assertEquals(listOf(5, 2, 9), store.envelopes(a).map { it[0].toInt() })
    }

    @Test
    fun groupsAreListedByCreationTimeThenSlot() {
        store.addGroup(GroupRow(b, 1, 200), emptyList())
        store.addGroup(GroupRow(a, 0, 200), emptyList())
        store.addGroup(GroupRow(GroupId(ByteArray(16) { 3 }), 2, 100), emptyList())
        assertEquals(listOf(2L, 0L, 1L), store.groups().map { it.keySlot })
    }

    @Test
    fun deletingAGroupRemovesItsEnvelopesAndOnlyItsEnvelopes() {
        store.addGroup(GroupRow(a, 0, 100), listOf(env(1), env(2)))
        store.addGroup(GroupRow(b, 1, 100), listOf(env(3)))
        store.deleteGroup(a)
        assertNull(store.group(a))
        assertEquals(emptyList(), store.envelopes(a))
        assertEquals(1, store.envelopes(b).size)
        store.deleteGroup(a) // again: nothing there, nothing happens
    }

    @Test
    fun settingsArePutReplacedAndRead() {
        val settings = RoomSettingsStore(db)
        assertNull(settings.get("next_slot"))
        settings.put("next_slot", "3")
        settings.put("next_slot", "4")
        assertEquals("4", settings.get("next_slot"))
        settings.put("default_name", "Åsha ₹")
        assertEquals("Åsha ₹", settings.get("default_name"))
    }

    @Test
    fun joinRequestsAreStoredPerGroupReplacedBySameMemberChangedInStateAndDeletedWithTheGroup() {
        val requests = RoomJoinRequestStore(db)
        store.addGroup(GroupRow(a, 0, 1), emptyList())
        store.addGroup(GroupRow(b, 1, 2), emptyList())
        val ben = PublicKey(ByteArray(32) { 5 })
        fun request(group: GroupId, name: String, at: Long) = JoinRequest(group, ben, EncryptionKey(ByteArray(32) { 6 }), name, "98765 00001", at, JoinState.WAITING)
        requests.put(request(a, "Ben", 10))
        requests.put(request(b, "Ben B", 11))
        requests.put(request(a, "Benny", 12))
        assertEquals(listOf("Benny"), requests.list(a).map { it.name })
        assertEquals(listOf("Ben B"), requests.list(b).map { it.name })
        requests.setState(a, ben, JoinState.DECLINED)
        assertEquals(JoinState.DECLINED, requests.get(a, ben)!!.state)
        assertEquals(JoinState.WAITING, requests.get(b, ben)!!.state)
        store.deleteGroup(a)
        assertEquals(emptyList(), requests.list(a))
        assertNull(requests.get(a, ben))
    }

    @Test
    fun theProblemLogKeepsTheNewestLinesPerGroupAndHoldsNoEnvelopeContent() {
        store.addGroup(GroupRow(a, 0, 1), emptyList())
        store.addGroup(GroupRow(b, 1, 2), emptyList())
        val log = RoomSyncProblemLog(db, maxPerGroup = 4)
        val peer = PublicKey(ByteArray(32) { 9 })
        repeat(7) { n -> log.add(SyncProblem(a, peer, ProblemKind.MALFORMED, n + 1, 100L + n)) }
        log.add(SyncProblem(b, peer, ProblemKind.CUT_OFF, 1, 200))
        assertEquals(listOf(7, 6, 5, 4), log.recent(a, 50).map { it.count }, "the newest first, the oldest dropped")
        assertEquals(1, log.recent(b, 50).size)
        assertEquals(ProblemKind.CUT_OFF, log.recent(b, 50).single().kind)
        log.clear(a)
        assertEquals(emptyList(), log.recent(a, 50))
        assertEquals(1, log.recent(b, 50).size)
        // The table has no column that could hold bytes of an envelope: only ids, a kind, a count and a time.
        val columns = db.openHelper.readableDatabase.query("PRAGMA table_info(sync_problems)").use { c -> generateSequence { if (c.moveToNext()) c.getString(1) else null }.toList() }
        assertEquals(listOf("row_id", "group_id", "peer", "kind", "count", "at_seconds"), columns)
    }
}
