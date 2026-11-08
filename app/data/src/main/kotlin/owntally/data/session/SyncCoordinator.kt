package owntally.data.session

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import owntally.crypto.Clock
import owntally.data.repo.GroupRepository
import owntally.model.GroupId
import owntally.model.PublicKey
import owntally.sync.GroupRoom
import owntally.sync.Link
import owntally.sync.LinkKind
import owntally.sync.PeerUnreachable
import owntally.sync.SyncEngine
import owntally.sync.SyncOutcome
import owntally.sync.SyncResult
import owntally.sync.SyncRole
import owntally.sync.Transport

/** When and how often phones sync (decision S3, DESIGN.md): every few minutes while open, and the waits after a peer could not be reached. */
data class SyncConfig(
    val intervalMs: Long = 5 * 60_000L,
    /** After the first, second and later failed tries: 1, 5 and 30 minutes (the last is repeated). */
    val retryDelaysMs: List<Long> = listOf(60_000L, 300_000L, 1_800_000L),
    val stallTimeoutMs: Long = 30_000L,
)

enum class PeerState { NEVER, SYNCING, OK, UNREACHABLE, FAILED }

/** What the Sync screen shows about one member. [notShared] counts entries this phone holds that the member's last summary lacked. */
data class PeerStatus(
    val state: PeerState = PeerState.NEVER,
    val attempts: Int = 0,
    val failures: Int = 0,
    val lastAttemptSeconds: Long? = null,
    val lastSuccessSeconds: Long? = null,
    val nextTrySeconds: Long? = null,
    val notShared: Int = 0,
    val kind: LinkKind? = null,
    /** How the last link was protected on the network (a local link: TLS or plain), if it says. */
    val security: owntally.sync.LinkSecurity? = null,
)

data class SyncStatus(
    val peers: Map<PublicKey, PeerStatus> = emptyMap(),
    /** The notice of the latest run in which envelopes were dropped, until dismissed (decision S4). */
    val notice: RunNotice? = null,
) {
    val syncingCount: Int get() = peers.values.count { it.state == PeerState.SYNCING }
}

/**
 * Syncs the groups of this phone with the other members (M5, T5.8): answers the members who connect, connects to them when the app opens,
 * after each change this phone's user makes, and every few minutes while the group is open; tries again after 1, 5 and 30 minutes when a
 * member cannot be reached; and publishes a [SyncStatus] for each group. After every run the group's session is read again, so the owner's
 * phone changes the key by itself after a leave (M4, D2). [runOnce] is one pass over all the groups, for the background job (M6).
 */
class SyncCoordinator(
    private val sessions: Sessions,
    private val groups: GroupRepository,
    /** The transport for one group, for the key this phone uses in it. */
    private val transportFor: (GroupId, PublicKey) -> Transport,
    private val recorder: SyncRecorder,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val config: SyncConfig = SyncConfig(),
    /** What lets this phone answer people who hold one of its invitations (M6): null for a group where this person may not invite. */
    private val joinBackend: (GroupId) -> owntally.sync.JoinHostBackend? = { null },
) {
    private class PeerRun {
        var running = false
        var again = false
        var retry: Job? = null
    }

    private class Group(val id: GroupId, val session: GroupSession, val self: PublicKey, val transport: Transport) {
        val room: GroupRoom = GroupRoom.fromGroupId(id.bytes())
        val peers = ConcurrentHashMap<PublicKey, PeerRun>()
        val jobs = ArrayList<Job>()
    }

    private val statuses = ConcurrentHashMap<GroupId, MutableStateFlow<SyncStatus>>()
    private val started = ConcurrentHashMap<GroupId, Group>()
    private val introducers = ConcurrentHashMap<GroupId, Set<PublicKey>>()

    private fun flow(group: GroupId) = statuses.getOrPut(group) { MutableStateFlow(SyncStatus()) }

    fun status(group: GroupId): StateFlow<SyncStatus> = flow(group)

    fun dismissNotice(group: GroupId) = flow(group).update { it.copy(notice = null) }

    /** The newest lines of the log of dropped entries (S4), for the note on the Sync screen. */
    fun problems(group: GroupId, limit: Int = 5) = recorder.recent(group, limit)

    private fun now() = clock.nowSeconds()

    private fun MutableStateFlow<SyncStatus>.update(change: (SyncStatus) -> SyncStatus) {
        while (true) {
            val before = value
            if (compareAndSet(before, change(before))) return
        }
    }

    private fun peerUpdate(g: Group, peer: PublicKey, change: (PeerStatus) -> PeerStatus) =
        flow(g.id).update { st -> st.copy(peers = st.peers + (peer to change(st.peers[peer] ?: PeerStatus()))) }

    private fun open(group: GroupId): Group? = try {
        val session = sessions.session(group)
        val self = session.view.value.me
        Group(group, session, self, transportFor(group, self))
    } catch (e: IllegalStateException) {
        null // no such group, or no usable seed
    }

    /** Starts syncing a group that is open: answers members who connect, syncs now, after each local change, and every few minutes. Idempotent. */
    @Synchronized
    fun start(group: GroupId) {
        if (started.containsKey(group)) return
        val g = open(group) ?: return
        started[group] = g
        g.jobs += scope.launch { g.transport.incoming(g.room).collect { link -> scope.launch { respond(g, link) } } }
        g.jobs += scope.launch {
            syncPeers(g, manual = false)
            while (isActive) {
                delay(config.intervalMs)
                syncPeers(g, manual = false)
            }
        }
        g.jobs += scope.launch {
            g.session.view.map { it.ownCounter }.distinctUntilChanged().drop(1).collect { syncPeers(g, manual = false) }
        }
    }

    /**
     * Starts a group this person was just admitted to and syncs with the phone that approved them at once (M6). A new member knows no one
     * yet, so without this nobody would be tried until the host's next retry; [introducer] is tried at once (and again after 1, 5 and 30
     * minutes if it cannot be reached) and trusted until their own admission arrives.
     */
    fun startWith(group: GroupId, introducer: PublicKey) {
        introducers.merge(group, setOf(introducer)) { a, b -> a + b }
        start(group)
        val g = started[group] ?: return
        scope.launch { attempt(g, introducer, manual = true) }
    }

    @Synchronized
    fun stop(group: GroupId) {
        val g = started.remove(group) ?: return
        g.jobs.forEach { it.cancel() }
        g.peers.values.forEach { it.retry?.cancel() }
    }

    /** "Sync now": every member, without waiting for a retry time, and returns when all have been tried. */
    suspend fun syncNow(group: GroupId) {
        val g = started[group] ?: open(group) ?: return
        syncPeers(g, manual = true).joinAll()
    }

    private var delivery: Job? = null

    /** While running, tries to deliver the leave of every group this person left, now and every few minutes, until a member has it. */
    @Synchronized
    fun startDelivery() {
        if (delivery?.isActive == true) return
        delivery = scope.launch {
            while (isActive) {
                deliverLeaves()
                delay(config.intervalMs)
            }
        }
    }

    @Synchronized
    fun stopDelivery() {
        delivery?.cancel()
        delivery = null
    }

    /**
     * For each group this person has left: connect to the members one by one and hand over the leave; when a member's closing summary proves
     * it has it ([LeaveProof]), the group is deleted with its keys (D4). Nothing is deleted before that.
     */
    suspend fun deliverLeaves() {
        for (left in sessions.leftGroups()) {
            val g = open(left.id) ?: continue
            for (peer in peersOf(g)) {
                val result = runEngine(g, peer) ?: continue
                if (LeaveProof.delivered(g.session.view.value.state, g.self, left.leaveCounter, peer, result)) {
                    sessions.deleteLeft(left.id)
                    break
                }
            }
        }
    }

    /** One pass over every group on this phone, then done (the background job of M6 calls this). */
    suspend fun runOnce() {
        deliverLeaves()
        for (summary in sessions.summaries.value) {
            if (!summary.readable || summary.youWereRemoved) continue
            syncNow(summary.groupId)
        }
    }

    private fun peersOf(g: Group): Set<PublicKey> = g.session.view.value.state.currentMembers.keys - g.self

    private fun syncPeers(g: Group, manual: Boolean): List<Job> = peersOf(g).map { peer -> scope.launch { attempt(g, peer, manual) } }

    private suspend fun attempt(g: Group, peer: PublicKey, manual: Boolean) {
        val run = g.peers.getOrPut(peer) { PeerRun() }
        if (!manual) {
            val next = flow(g.id).value.peers[peer]?.nextTrySeconds
            if (next != null && next > now()) return // waiting for its retry time
        }
        synchronized(run) {
            if (run.running) { run.again = true; return } // a sync with this member is under way: one more when it ends
            run.running = true
        }
        try {
            while (true) {
                synchronized(run) { run.again = false }
                syncOnce(g, peer, run)
                if (!synchronized(run) { run.again }) break
            }
        } finally {
            synchronized(run) { run.running = false }
        }
    }

    private suspend fun syncOnce(g: Group, peer: PublicKey, run: PeerRun) {
        peerUpdate(g, peer) { it.copy(state = PeerState.SYNCING, attempts = it.attempts + 1, lastAttemptSeconds = now()) }
        val result: SyncResult = try {
            runEngine(g, peer, throwUnreachable = true)!!
        } catch (e: CancellationException) {
            throw e
        } catch (e: owntally.sync.TransportUnavailable) {
            // No real transport yet (until M6): not a failure, so no waiting time and no retry.
            peerUpdate(g, peer) { it.copy(state = PeerState.NEVER) }
            return
        } catch (e: PeerUnreachable) {
            failed(g, peer, run, PeerState.UNREACHABLE)
            return
        } catch (e: Exception) {
            failed(g, peer, run, PeerState.FAILED)
            return
        }
        finished(g, peer, result)
        if (result.outcome == SyncOutcome.COMPLETED) {
            run.retry?.cancel()
            run.retry = null
            peerUpdate(g, peer) {
                it.copy(state = PeerState.OK, failures = 0, lastSuccessSeconds = now(), nextTrySeconds = null, notShared = notShared(g, result), kind = result.via, security = result.security)
            }
        } else {
            failed(g, peer, run, PeerState.FAILED)
        }
    }

    /** Connects and runs the engine as the initiator; null if the member cannot be reached (unless [throwUnreachable]). */
    private suspend fun runEngine(g: Group, peer: PublicKey, throwUnreachable: Boolean = false): SyncResult? {
        val store = RepositorySyncStore(g.id, groups)
        return try {
            val link = g.transport.connect(g.room, peer)
            try {
                SyncEngine(g.room, store, config.stallTimeoutMs, access = MembershipAccess(g.session, store, introducers[g.id].orEmpty())).run(link, SyncRole.INITIATOR)
            } finally {
                link.close()
            }.also { g.session.refresh() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (throwUnreachable) throw e
            null
        }
    }

    /** The peer could not be reached or the sync did not finish: wait 1, then 5, then 30 minutes (and again 30) before trying again. */
    private fun failed(g: Group, peer: PublicKey, run: PeerRun, state: PeerState) {
        var delayMs = 0L
        peerUpdate(g, peer) {
            val failures = it.failures + 1
            delayMs = config.retryDelaysMs[minOf(failures - 1, config.retryDelaysMs.size - 1)]
            it.copy(state = state, failures = failures, nextTrySeconds = now() + delayMs / 1000)
        }
        run.retry?.cancel()
        run.retry = scope.launch {
            delay(delayMs)
            attempt(g, peer, manual = false)
        }
    }

    /** After a run, either way: note what was dropped (log and the one notice) and read the session again (the owner's key duty). */
    private fun finished(g: Group, peer: PublicKey, result: SyncResult) {
        recorder.record(g.id, peer, result)?.let { notice -> flow(g.id).update { it.copy(notice = notice) } }
        g.session.refresh()
    }

    private fun notShared(g: Group, result: SyncResult): Int {
        val theirs = (result.peerFinal ?: result.peerSummary)?.entries ?: return 0
        return RepositorySyncStore(g.id, groups).summary().entries.entries.sumOf { (author, mine) ->
            maxOf(0L, mine.counter - (theirs[author]?.counter ?: 0L)).toInt()
        }
    }

    /**
     * Someone connected. The first message tells a join (a person holding an invitation) from a sync: a join goes to the join host if this
     * phone may invite, anything else to the sync engine. The first message is handed on, so neither side loses it.
     */
    private suspend fun respond(g: Group, link: Link) {
        val peer = link.peer
        val outcome: SyncResult? = try {
            kotlinx.coroutines.coroutineScope {
                val inbox = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                val pump = launch { try { link.received.collect { inbox.send(it) } } finally { inbox.close() } }
                try {
                    val first = kotlinx.coroutines.withTimeoutOrNull(config.stallTimeoutMs) { inbox.receiveCatching().getOrNull() }
                    if (first == null) return@coroutineScope null
                    val wrapped = object : Link by link {
                        override val received = kotlinx.coroutines.flow.flow { emit(first); for (m in inbox) emit(m) }
                    }
                    if (owntally.sync.JoinCodec.isJoin(first)) {
                        joinBackend(g.id)?.let { owntally.sync.JoinHost(it, g.room, config.stallTimeoutMs).serve(wrapped) }
                        null
                    } else {
                        val store = RepositorySyncStore(g.id, groups)
                        SyncEngine(g.room, store, config.stallTimeoutMs, access = MembershipAccess(g.session, store)).run(wrapped, SyncRole.RESPONDER)
                    }
                } finally {
                    pump.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        } finally {
            link.close()
        }
        val result = outcome ?: return
        finished(g, peer, result)
        if (result.outcome == SyncOutcome.COMPLETED && peer in peersOf(g)) {
            peerUpdate(g, peer) {
                it.copy(state = if (it.state == PeerState.SYNCING) it.state else PeerState.OK, lastSuccessSeconds = now(), notShared = notShared(g, result), kind = link.kind, security = link.security)
            }
        }
    }
}
