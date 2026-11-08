package owntally.data.store

import owntally.data.db.EventEntity
import owntally.data.db.GroupEntity
import owntally.data.db.JoinRequestEntity
import owntally.data.db.IssuedTicketEntity
import owntally.data.db.OwnTallyDatabase
import owntally.data.db.SyncProblemEntity
import owntally.data.db.SettingEntity
import owntally.crypto.TicketRecord
import owntally.crypto.TicketRecords
import owntally.model.EncryptionKey
import owntally.model.GroupId
import owntally.model.PublicKey

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
    override fun remove(key: String) = dao.deleteSetting(key)
}

/** [JoinRequestStore] on the Room database. */
class RoomJoinRequestStore(db: OwnTallyDatabase) : JoinRequestStore {
    private val dao = db.dao()

    private fun JoinRequestEntity.toModel() = JoinRequest(
        GroupId(groupId), PublicKey(memberKey), EncryptionKey(encryptionKey), name, phone, receivedAt, JoinState.entries[state],
    )

    override fun put(request: JoinRequest) = dao.putJoinRequest(
        JoinRequestEntity(
            request.groupId.bytes(), request.member.bytes(), request.encryptionKey.bytes(), request.name, request.phone,
            request.receivedAtSeconds, request.state.ordinal,
        ),
    )

    override fun get(groupId: GroupId, member: PublicKey): JoinRequest? = dao.joinRequest(groupId.bytes(), member.bytes())?.toModel()
    override fun list(groupId: GroupId): List<JoinRequest> = dao.joinRequests(groupId.bytes()).map { it.toModel() }
    override fun setState(groupId: GroupId, member: PublicKey, state: JoinState) = dao.setJoinRequestState(groupId.bytes(), member.bytes(), state.ordinal)
}

/** [SyncProblemLog] on the Room database: the latest [maxPerGroup] lines of each group are kept. */
class RoomSyncProblemLog(db: OwnTallyDatabase, private val maxPerGroup: Int = InMemorySyncProblemLog.DEFAULT_MAX) : SyncProblemLog {
    private val dao = db.dao()

    override fun add(problem: SyncProblem) {
        dao.insertSyncProblem(SyncProblemEntity(0, problem.group.bytes(), problem.peer.bytes(), problem.kind.ordinal, problem.count, problem.atSeconds))
        dao.pruneSyncProblems(problem.group.bytes(), maxPerGroup)
    }

    override fun recent(group: GroupId, limit: Int): List<SyncProblem> =
        dao.syncProblems(group.bytes(), limit).map { SyncProblem(GroupId(it.groupId), PublicKey(it.peer), ProblemKind.entries[it.kind], it.count, it.atSeconds) }

    override fun clear(group: GroupId) = dao.clearSyncProblems(group.bytes())
}

/** [TicketRecords] on the Room database: issued tickets and which are used survive a restart. */
class RoomTicketRecords(db: OwnTallyDatabase) : TicketRecords {
    private val dao = db.dao()

    override fun put(record: TicketRecord) =
        dao.putIssuedTicket(IssuedTicketEntity(record.ticketId(), record.hostId.bytes(), record.groupId.bytes(), record.epoch, record.expiresAtSeconds, record.hint, record.used))

    override fun get(ticketId: ByteArray): TicketRecord? = dao.issuedTicket(ticketId)?.let {
        TicketRecord(it.ticketId, PublicKey(it.hostId), GroupId(it.groupId), it.epoch, it.expiresAt, it.hint, it.used)
    }

    override fun open(group: GroupId, nowSeconds: Long): List<TicketRecord> = dao.openTickets(group.bytes(), nowSeconds).map {
        TicketRecord(it.ticketId, PublicKey(it.hostId), GroupId(it.groupId), it.epoch, it.expiresAt, it.hint, it.used)
    }

    override fun markUsed(ticketId: ByteArray): Boolean = dao.markIssuedTicketUsed(ticketId) == 1
    override fun deleteExpired(nowSeconds: Long) = dao.deleteExpiredTickets(nowSeconds)
}
