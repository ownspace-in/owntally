package owntally.app

import android.content.Context
import java.io.File
import java.security.SecureRandom
import owntally.crypto.Clock
import owntally.crypto.RecoveryPhrase
import owntally.data.db.OwnTallyDatabase
import owntally.data.repo.GroupRepository
import owntally.data.repo.IdentityRepository
import owntally.data.repo.IdentityState
import owntally.data.secrets.BoxedSecretStore
import owntally.data.secrets.FileBlobStore
import owntally.data.secrets.KeystoreSecretBox
import owntally.data.secrets.SecretStore
import owntally.data.secrets.SecretStoreException
import owntally.data.session.Sessions
import owntally.data.store.RoomEventStore
import owntally.data.store.RoomSettingsStore
import kotlinx.coroutines.launch
import owntally.ui.AppStart
import owntally.ui.groups.DebugTools
import owntally.ui.onboarding.FirstRun

/**
 * Builds the app's parts by hand, once (no dependency-injection library). Everything is created the first time it is needed, so
 * starting the app does no more than it must. The secret files live in a folder Android never backs up.
 */
class AppContainer(private val context: Context) {
    val secrets: SecretStore by lazy { BoxedSecretStore(KeystoreSecretBox.create(), FileBlobStore(File(context.noBackupFilesDir, "secrets"))) }
    private val database: OwnTallyDatabase by lazy { OwnTallyDatabase.open(context) }
    val identity: IdentityRepository by lazy { IdentityRepository(secrets, settings) }
    val groups: GroupRepository by lazy { GroupRepository(secrets, RoomEventStore(database), identity, Clock.SYSTEM, SecureRandom()) }

    val settings: RoomSettingsStore by lazy { RoomSettingsStore(database) }
    val sessions: Sessions by lazy { Sessions(groups, secrets, identity, Clock.SYSTEM, settings) }
    val invites: owntally.data.session.Invites by lazy {
        owntally.data.session.Invites(owntally.data.store.RoomTicketRecords(database), groups, secrets, identity, sessions, Clock.SYSTEM)
    }
    private val pendingJoins: owntally.data.store.RoomPendingJoinStore by lazy { owntally.data.store.RoomPendingJoinStore(database) }

    /** Joining groups (M6): reaches the host over the local network, like any other sync. */
    val joinGroup: owntally.data.session.JoinGroup by lazy {
        owntally.data.session.JoinGroup(
            groups, secrets, identity, pendingJoins, sessions, Clock.SYSTEM,
            { group, key -> transportOf(group, key) }, onApproved = { group, host -> sync.startWith(group, host) },
        )
    }

    // ---- reaching other phones (M6) ---------------------------------------------------------------------------------------------------

    private val networkScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** This phone on the local network: one port for every group and key, found by the others through Android's service discovery. */
    private val localNode: owntally.sync.LocalNode by lazy { owntally.sync.LocalNode(NsdLocalDiscovery(context), networkScope) }

    /** The key this phone uses in the group: its own group's, or the one reserved for an invitation it opened and has not been admitted through yet. */
    private fun identityOf(group: owntally.model.GroupId, key: owntally.model.PublicKey): owntally.crypto.GroupIdentity? {
        // A group of this phone, an invitation it opened, or, while a restored phone is still finding out which key it had in a group, any key
        // derived from the group's id (M11: the creator's, then the member keys of generation 0, 1, ...).
        val known = listOfNotNull(groups.group(group)?.keySlot, pendingJoins.get(group)?.keySlot)
        val derived = sequence {
            yield(owntally.crypto.KeySlots.creator(owntally.model.GroupIds.randomPart(group)))
            for (generation in 0L until 32L) yield(owntally.crypto.KeySlots.member(group, generation))
        }
        for (slot in known + derived) identity.groupIdentity(slot)?.takeIf { it.publicKey == key }?.let { return it }
        return null
    }

    /**
     * Every way to reach the other members with the key this phone uses in the group (M7): the same network (TLS first and the plain link if TLS
     * does not connect, decision L6), then Iroh and our relay. Made once for each key and kept while the app syncs, because Iroh's endpoint and the
     * relay's sockets must not be opened again for every call. Nothing if the phone has no key for the group.
     */
    private fun transportOf(group: owntally.model.GroupId, key: owntally.model.PublicKey): owntally.sync.Transport {
        val id = identityOf(group, key) ?: return owntally.sync.NoTransport
        val local = localNode.secureFirstTransportFor(id)
        if (!internetEnabled) return local
        return ways.computeIfAbsent(key) { InternetWays(context, id, networkScope, local) }.transport
    }

    private val ways = java.util.concurrent.ConcurrentHashMap<owntally.model.PublicKey, InternetWays>()

    /** Closes Iroh's endpoints and the relay's sockets (they are made again when next needed). */
    private fun stopInternet() {
        val stopping = ways.values.toList()
        ways.clear()
        if (stopping.isNotEmpty()) networkScope.launch { stopping.forEach { it.stop() } }
    }

    /** For tests that must not reach the internet (the screens with pretend friends): only the same network is used. */
    @Volatile var internetEnabled: Boolean = true

    /** For tests that do not want a phone looking for friends on the network while they run (the sync screens with pretend friends). */
    @Volatile var syncOnStart: Boolean = true

    private var following: kotlinx.coroutines.Job? = null

    /**
     * Syncing while the app is open (decision S3): every group that can be opened is started, and so is every group that appears later.
     * The coordinator answers the members who connect, syncs now, after each change and every few minutes. Call when the app comes to the front.
     */
    @Synchronized
    fun beginSyncing() {
        if (!syncOnStart || following?.isActive == true) return
        following = networkScope.launch {
            sessions.summaries.collect { list -> for (g in list) if (g.readable && !g.youWereRemoved) sync.start(g.groupId) }
        }
    }

    /** Stops all of it when the app leaves the front: the groups' coordinators and the port. (The background job calls `backgroundPass`.) */
    @Synchronized
    fun endSyncing() {
        following?.cancel(); following = null
        for (g in sessions.summaries.value) sync.stop(g.groupId)
        localNode.close()
        stopInternet()
    }

    /**
     * One background pass (decision B1, M7): every group once, then the leaves still to be delivered, then done. It does nothing while the app is
     * in front (the coordinators there are already syncing), before the person has set the phone up, or when the stored keys cannot be read.
     * Returns whether a pass ran. Android picks when (about every 15 minutes at best), so the time given is short of what Android allows a job.
     */
    suspend fun backgroundPass(timeoutMs: Long = BACKGROUND_PASS_MS): Boolean {
        if (following?.isActive == true) return false
        val ready = try { identity.state() == IdentityState.READY } catch (e: SecretStoreException) { false }
        if (!ready) return false
        try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { sync.runOnce() }
        } finally {
            localNode.close()
            if (following?.isActive != true) stopInternet() // unless the app came to the front meanwhile: it is using them
        }
        return true
    }

    /** An invite link tapped outside the app, waiting until the person is past first-run setup and the lock (decision A1, M9). */
    val pendingInvites: owntally.data.session.PendingInvites by lazy { owntally.data.session.PendingInvites(secrets, Clock.SYSTEM) }

    /** A link that opened the app: kept if it is an invite link (null or refused ones leave a message for the screen). Off the main thread: the Keystore. */
    fun inviteLinkArrived(link: String?) {
        networkScope.launch {
            try {
                if (link == null) pendingInvites.refuse() else pendingInvites.receive(link)
            } catch (e: SecretStoreException) {
                pendingInvites.refuse() // the link could not be kept; the person can tap it again
            }
        }
    }

    val joinRequests: owntally.data.session.JoinRequests by lazy { owntally.data.session.JoinRequests(owntally.data.store.RoomJoinRequestStore(database), sessions, Clock.SYSTEM) }
    /** Syncing with the other members (M5), now over real connections (M6): the Sync screen and its "Sync now" read this. */
    val sync: owntally.data.session.SyncCoordinator by lazy {
        owntally.data.session.SyncCoordinator(
            sessions, groups, { group, key -> transportOf(group, key) },
            owntally.data.session.SyncRecorder(owntally.data.store.RoomSyncProblemLog(database), Clock.SYSTEM), Clock.SYSTEM,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
            // Only a phone whose person may invite (the owner and admins) answers people who hold an invitation.
            joinBackend = { id ->
                if (!invites.canInvite(id)) null
                else groups.group(id)?.let { identity.groupIdentity(it.keySlot) }?.let { host -> owntally.data.session.GroupJoinBackend(id, host, invites, joinRequests, sessions) }
            },
        )
    }
    /**
     * Nearby for one group (M6, T6.11): built when its screen is opened, started only by Start. Needs Google Play services; a phone without
     * them is told so on the screen.
     */
    val nearby: (owntally.model.GroupId) -> owntally.ui.nearby.NearbyController = { group -> NearbyControllerImpl(context, group, sessions, sync, groups, identity) }
    val debugTools: DebugTools? by lazy { DebugToolsFactory.create(this) }

    val firstRun: FirstRun = object : FirstRun {
        override fun newPhrase(): RecoveryPhrase = identity.newPhrase()
        override fun complete(phrase: RecoveryPhrase, name: String, phone: String) = identity.completeFirstRun(phrase, name, phone)
    }

    /** What to show first. Opens the Keystore, so call it off the main thread. */
    fun start(): AppStart = try {
        when (identity.state()) {
            IdentityState.NOT_CREATED -> AppStart.NeedsFirstRun
            IdentityState.READY -> AppStart.Ready
            IdentityState.SEED_UNREADABLE -> AppStart.SeedUnreadable
        }
    } catch (e: SecretStoreException) {
        AppStart.StorageUnavailable
    }

    companion object {
        /** A job that runs for more than about 10 minutes is stopped by Android; this leaves room for the pass to end cleanly. */
        const val BACKGROUND_PASS_MS = 8 * 60_000L
    }
}
