package owntally.app

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import owntally.data.repo.GroupRepository
import owntally.data.repo.IdentityRepository
import owntally.data.session.NearbySession
import owntally.data.session.NearbyStatus
import owntally.data.session.Sessions
import owntally.data.session.SyncCoordinator
import owntally.model.GroupId
import owntally.sync.GroupRoom
import owntally.sync.NearbyTransport
import owntally.ui.nearby.NearbyAvailability
import owntally.ui.nearby.NearbyController

/** Nearby for one group on a real phone: nothing is made until [start], and [stop] ends all of it (advertising, discovery, connections). */
class NearbyControllerImpl(
    private val context: Context,
    private val group: GroupId,
    private val sessions: Sessions,
    private val sync: SyncCoordinator,
    private val groups: GroupRepository,
    private val identity: IdentityRepository,
) : NearbyController {
    private val mutable = MutableStateFlow(NearbyStatus())
    override val status: StateFlow<NearbyStatus> get() = mutable
    private var scope: CoroutineScope? = null
    private var transport: NearbyTransport? = null
    private var session: NearbySession? = null
    private var follow: Job? = null

    override fun availability(): NearbyAvailability =
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) NearbyAvailability.AVAILABLE else NearbyAvailability.NO_PLAY_SERVICES

    @Synchronized
    override fun start() {
        if (session != null || availability() != NearbyAvailability.AVAILABLE) return
        val me = groups.group(group)?.let { identity.groupIdentity(it.keySlot) } ?: return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        val room = GroupRoom.fromGroupId(group.bytes())
        val port = PlayNearbyPort(context, "owntally.nearby.$room")
        val t = NearbyTransport(port, me, s)
        transport = t
        val n = NearbySession(group, sessions, sync, t, s)
        session = n
        follow = s.launch { n.status.collect { mutable.value = it } }
        n.start()
    }

    @Synchronized
    override fun stop() {
        session?.stop()
        transport?.stop()
        mutable.value = mutable.value.copy(running = false)
        follow?.cancel(); follow = null
        scope?.cancel(); scope = null
        session = null; transport = null
    }

    @Synchronized
    override fun lookAgain() { session?.lookAgain() }
}
