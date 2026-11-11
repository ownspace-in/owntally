package owntally.data.store

import java.security.MessageDigest
import owntally.model.GroupId
import owntally.model.PublicKey

/** One group as kept on this phone. The name and members are inside the encrypted events, never here. */
data class GroupRow(val groupId: GroupId, val keySlot: Long, val createdAtSeconds: Long)

/** An envelope's bytes with the two fields used to look it up. */
class StoredEnvelope(val author: PublicKey, val counter: Long, val bytes: ByteArray)

/**
 * Where groups and their envelopes are kept. Persistence only: nothing here decrypts or checks anything. Envelopes are stored
 * exactly as they are sent (encrypted and signed). Two different envelopes with the same author and counter are both kept, because
 * the protocol decides what to do with them (docs/PROTOCOL.md section 3).
 */
interface EventStore {
    /** Adds a group and its first envelopes together, or nothing. Throws if the group id or the key slot is already used. */
    fun addGroup(row: GroupRow, envelopes: List<StoredEnvelope>)

    /** In the order the groups were created. */
    fun groups(): List<GroupRow>
    fun group(id: GroupId): GroupRow?

    /** False if exactly these bytes are already stored. Throws if the group does not exist. */
    fun addEnvelope(groupId: GroupId, envelope: StoredEnvelope): Boolean

    /** In the order they were added. */
    fun envelopes(groupId: GroupId): List<ByteArray>

    /** Removes the group and all its envelopes. */
    fun deleteGroup(groupId: GroupId)
}

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

/** For tests and previews. Behaves like the database: used ids and slots are refused, identical envelopes are stored once. */
class InMemoryEventStore : EventStore {
    private class Entry(val author: PublicKey, val counter: Long, val bytes: ByteArray)

    private val rows = LinkedHashMap<GroupId, GroupRow>()
    private val entries = HashMap<GroupId, MutableList<Entry>>()

    /** Test hook: how many times the stored envelopes of a group were read (F8: a stranger must not make a phone read a group's history). */
    @Volatile
    var envelopeReads = 0

    /** Test hook: the next [addGroup] fails as a database error would. */
    @Volatile
    var failNextAddGroup = false

    @Synchronized
    override fun addGroup(row: GroupRow, envelopes: List<StoredEnvelope>) {
        if (failNextAddGroup) {
            failNextAddGroup = false
            throw IllegalStateException("database failure")
        }
        check(row.groupId !in rows) { "group already exists" }
        check(rows.values.none { it.keySlot == row.keySlot }) { "key slot already used" }
        rows[row.groupId] = row
        entries[row.groupId] = envelopes.map { Entry(it.author, it.counter, it.bytes.copyOf()) }.toMutableList()
    }

    @Synchronized
    override fun groups(): List<GroupRow> = rows.values.sortedBy { it.createdAtSeconds } // a stable sort: the same second keeps the order the groups were added in

    @Synchronized
    override fun group(id: GroupId): GroupRow? = rows[id]

    @Synchronized
    override fun addEnvelope(groupId: GroupId, envelope: StoredEnvelope): Boolean {
        val list = entries[groupId] ?: throw IllegalStateException("no such group")
        if (list.any { it.author == envelope.author && it.counter == envelope.counter && it.bytes.contentEquals(envelope.bytes) }) return false
        list += Entry(envelope.author, envelope.counter, envelope.bytes.copyOf())
        return true
    }

    @Synchronized
    override fun envelopes(groupId: GroupId): List<ByteArray> { envelopeReads++; return entries[groupId].orEmpty().map { it.bytes.copyOf() } }

    @Synchronized
    override fun deleteGroup(groupId: GroupId) {
        rows.remove(groupId)
        entries.remove(groupId)
    }

    /** Test hook: every stored envelope's bytes, to check nothing is readable. */
    @Synchronized
    fun rawEnvelopes(): List<ByteArray> = entries.values.flatten().map { it.bytes.copyOf() }

    /** Test hook: stores bytes that are not an envelope, as a damaged database might. */
    @Synchronized
    fun addRawForTest(groupId: GroupId, bytes: ByteArray) {
        entries.getValue(groupId) += Entry(PublicKey(ByteArray(32)), 1, bytes.copyOf())
    }
}
