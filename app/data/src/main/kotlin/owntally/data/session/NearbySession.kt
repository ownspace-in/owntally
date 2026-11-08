package owntally.data.session

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import owntally.model.GroupId
import owntally.model.PublicKey
import owntally.sync.GroupRoom
import owntally.sync.PeerUnreachable
import owntally.sync.SyncOutcome
import owntally.sync.SyncRole
import owntally.sync.Transport
import owntally.sync.TransportUnavailable

enum class NearbyPeer { LOOKING, SYNCING, DONE, NOT_FOUND, FAILED }

/** What the Nearby screen shows: [running] from Start to Stop, and for each member of the group how it is going. */
data class NearbyStatus(val running: Boolean = false, val peers: Map<PublicKey, NearbyPeer> = emptyMap())

/**
 * A Nearby sync for one group, started by the person (DESIGN.md: Nearby runs only when asked; each try waits [timeoutMs]). It looks for every
 * other member, syncs with each one found, and answers any member who finds this phone first (of two phones the one with the smaller key opens
 * the connection, see `NearbyTransport`). A member not found within the wait is marked so; the session goes on listening until stopped.
 */
class NearbySession(
    private val group: GroupId,
    private val sessions: Sessions,
    private val coordinator: SyncCoordinator,
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 15_000,
) {
    private val mutable = MutableStateFlow(NearbyStatus())
    val status: StateFlow<NearbyStatus> get() = mutable
    private val jobs = ArrayList<Job>()
    private val waiting = ConcurrentHashMap<PublicKey, Job>()

    private fun set(peer: PublicKey, state: NearbyPeer) {
        while (true) {
            val before = mutable.value
            if (mutable.compareAndSet(before, before.copy(peers = before.peers + (peer to state)))) return
        }
    }

    @Synchronized
    fun start() {
        if (mutable.value.running) return
        val session = try { sessions.session(group) } catch (e: IllegalStateException) { return }
        val me = session.view.value.me
        val peers = session.view.value.state.currentMembers.keys - me
        mutable.value = NearbyStatus(true, peers.associateWith { NearbyPeer.LOOKING })
        val room = GroupRoom.fromGroupId(group.bytes())
        jobs += scope.launch {
            // Whoever finds us first: answer.
            transport.incoming(room).collect { link ->
                scope.launch {
                    val who = link.peer
                    set(who, NearbyPeer.SYNCING)
                    waiting.remove(who)?.cancel()
                    val ok = runCatching { coordinator.syncOverLink(group, link, SyncRole.RESPONDER) }.getOrNull()?.outcome == SyncOutcome.COMPLETED
                    set(who, if (ok) NearbyPeer.DONE else NearbyPeer.FAILED)
                }
            }
        }
        for (peer in peers) dial(room, peer)
    }

    private fun dial(room: GroupRoom, peer: PublicKey) {
        jobs += scope.launch {
            val timeout = scope.launch { delay(timeoutMs); if (mutable.value.peers[peer] == NearbyPeer.LOOKING) set(peer, NearbyPeer.NOT_FOUND) }
            waiting[peer] = timeout
            try {
                val link = transport.connect(room, peer)
                timeout.cancel()
                set(peer, NearbyPeer.SYNCING)
                val ok = coordinator.syncOverLink(group, link, SyncRole.INITIATOR)?.outcome == SyncOutcome.COMPLETED
                set(peer, if (ok) NearbyPeer.DONE else NearbyPeer.FAILED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportUnavailable) {
                // The other phone opens this connection: it stays "looking" until it does or the wait runs out.
            } catch (e: PeerUnreachable) {
                timeout.cancel()
                set(peer, NearbyPeer.NOT_FOUND)
            } catch (e: Exception) {
                timeout.cancel()
                set(peer, NearbyPeer.FAILED)
            }
        }
    }

    /** "Look again": tries once more for every member that was not found or whose sync did not finish. Does nothing when stopped. */
    @Synchronized
    fun lookAgain() {
        if (!mutable.value.running) return
        val room = GroupRoom.fromGroupId(group.bytes())
        for ((peer, state) in mutable.value.peers) {
            if (state == NearbyPeer.NOT_FOUND || state == NearbyPeer.FAILED) { set(peer, NearbyPeer.LOOKING); dial(room, peer) }
        }
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }; jobs.clear()
        waiting.values.forEach { it.cancel() }; waiting.clear()
        mutable.value = mutable.value.copy(running = false)
    }
}
