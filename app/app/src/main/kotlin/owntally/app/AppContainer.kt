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
    /**
     * Joining groups (M6). No way to reach other phones is built in yet, so the host can never be reached and every invitation says so
     * ("not reachable yet"); the screens and the stored state work as they will with real connections.
     */
    val joinGroup: owntally.data.session.JoinGroup by lazy {
        owntally.data.session.JoinGroup(
            groups, secrets, identity, owntally.data.store.RoomPendingJoinStore(database), sessions, Clock.SYSTEM,
            { _, _ -> owntally.sync.NoTransport }, onApproved = { group, host -> sync.startWith(group, host) },
        )
    }
    val joinRequests: owntally.data.session.JoinRequests by lazy { owntally.data.session.JoinRequests(owntally.data.store.RoomJoinRequestStore(database), sessions, Clock.SYSTEM) }
    /**
     * Syncing with the other members (M5). No real way to reach other phones is built in yet (M6), so every member shows "Not reachable
     * yet" and nothing is started here; the Sync screen and its "Sync now" read this.
     */
    val sync: owntally.data.session.SyncCoordinator by lazy {
        owntally.data.session.SyncCoordinator(
            sessions, groups, { _, _ -> owntally.sync.NoTransport },
            owntally.data.session.SyncRecorder(owntally.data.store.RoomSyncProblemLog(database), Clock.SYSTEM), Clock.SYSTEM,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
            // Only a phone whose person may invite (the owner and admins) answers people who hold an invitation.
            joinBackend = { id ->
                if (!invites.canInvite(id)) null
                else groups.group(id)?.let { identity.groupIdentity(it.keySlot) }?.let { host -> owntally.data.session.GroupJoinBackend(id, host, invites, joinRequests, sessions) }
            },
        )
    }
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
