package owntally.data.repo

import java.security.SecureRandom
import owntally.crypto.Clock
import owntally.crypto.RecoveryPhrase
import owntally.data.secrets.AeadSecretBox
import owntally.data.secrets.BoxedSecretStore
import owntally.data.secrets.InMemoryBlobStore
import owntally.data.secrets.SecretBox
import owntally.data.secrets.newAead
import owntally.data.store.InMemoryEventStore
import owntally.data.store.InMemorySettingsStore

/** Everything a repository needs, held in memory, with a Tink AES-GCM key standing in for the Keystore. */
class TestEnv(val box: SecretBox = AeadSecretBox(newAead())) {
    val blobs = InMemoryBlobStore()
    val secrets = BoxedSecretStore(box, blobs)
    val settings = InMemorySettingsStore()
    val events = InMemoryEventStore()
    var nowSeconds = 1_800_000_000L
    val clock = Clock { nowSeconds }
    val identity = IdentityRepository(secrets, settings)
    val groups = GroupRepository(secrets, events, identity, clock, SecureRandom())

    /** Runs the first-run steps with a phrase and returns it. */
    fun firstRun(name: String = "Asha", phone: String = "98765 43210"): RecoveryPhrase =
        identity.newPhrase().also { identity.completeFirstRun(it, name, phone) }
}
