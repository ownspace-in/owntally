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
