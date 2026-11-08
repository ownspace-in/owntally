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
        val slot = groups.group(group)?.keySlot ?: pendingJoins.get(group)?.keySlot ?: return null
        return identity.groupIdentity(slot)?.takeIf { it.publicKey == key }
    }

    /** TLS first and the plain link if TLS does not connect (decision L6); nothing if the phone has no key for the group. */
    private fun transportOf(group: owntally.model.GroupId, key: owntally.model.PublicKey): owntally.sync.Transport =
        identityOf(group, key)?.let { localNode.secureFirstTransportFor(it) } ?: owntally.sync.NoTransport

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

    /** Stops all of it when the app leaves the front: the groups' coordinators and the port. (The background pass of M6 will call `runOnce`.) */
    @Synchronized
    fun endSyncing() {
        following?.cancel(); following = null
        for (g in sessions.summaries.value) sync.stop(g.groupId)
        localNode.close()
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
}
