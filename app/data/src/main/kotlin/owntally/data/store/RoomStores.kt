package owntally.data.store

import owntally.data.db.EventEntity
import owntally.data.db.GroupEntity
import owntally.data.db.OwnTallyDatabase
import owntally.data.db.SettingEntity
import owntally.model.GroupId

/** [EventStore] on the Room database. Queries block, so call it from a background thread. */
class RoomEventStore(private val db: OwnTallyDatabase, private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }) : EventStore {
    private val dao = db.dao()

    private fun entity(groupId: GroupId, e: StoredEnvelope) =
        EventEntity(0, groupId.bytes(), e.author.bytes(), e.counter, sha256(e.bytes), e.bytes, nowSeconds())

    override fun addGroup(row: GroupRow, envelopes: List<StoredEnvelope>) {
        db.runInTransaction {
            dao.insertGroup(GroupEntity(row.groupId.bytes(), row.keySlot, row.createdAtSeconds))
            for (e in envelopes) dao.insertEvent(entity(row.groupId, e))
        }
    }

    override fun groups(): List<GroupRow> = dao.groups().map { GroupRow(GroupId(it.groupId), it.keySlot, it.createdAt) }

    override fun group(id: GroupId): GroupRow? = dao.group(id.bytes())?.let { GroupRow(GroupId(it.groupId), it.keySlot, it.createdAt) }

    override fun addEnvelope(groupId: GroupId, envelope: StoredEnvelope): Boolean {
        var added = false
        db.runInTransaction {
            check(dao.group(groupId.bytes()) != null) { "no such group" }
            added = dao.insertEvent(entity(groupId, envelope)) != -1L
        }
        return added
    }

    override fun envelopes(groupId: GroupId): List<ByteArray> = dao.envelopes(groupId.bytes())

    override fun deleteGroup(groupId: GroupId) = dao.deleteGroup(groupId.bytes())
}

/** [SettingsStore] on the Room database. */
class RoomSettingsStore(db: OwnTallyDatabase) : SettingsStore {
    private val dao = db.dao()

    override fun get(key: String): String? = dao.setting(key)
    override fun put(key: String, value: String) = dao.putSetting(SettingEntity(key, value))
}
