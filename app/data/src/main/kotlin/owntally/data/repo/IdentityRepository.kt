package owntally.data.repo

import owntally.crypto.Bip39
import owntally.crypto.GroupIdentity
import owntally.crypto.KeyDerivation
import owntally.crypto.RecoveryPhrase
import owntally.data.secrets.SecretLoad
import owntally.data.secrets.SecretStore
import owntally.data.store.SettingsStore
import owntally.model.EventBody

enum class IdentityState {
    /** First run has not been completed. */
    NOT_CREATED,
    READY,

    /** A seed is stored but cannot be opened (the Keystore key is gone or changed): the person must restore from the phrase. */
    SEED_UNREADABLE,
}

/** The name and phone number used as the default when joining or creating a group. */
data class Profile(val name: String, val phone: String)

/**
 * The person on this phone: the master seed, the default profile and the key slots. The recovery phrase is shown once and
 * never stored; only the seed made from it is (sealed, by [SecretStore]).
 */
class IdentityRepository(private val secrets: SecretStore, private val settings: SettingsStore) {
    fun state(): IdentityState = when (secrets.loadMasterSeed()) {
        is SecretLoad.Found -> IdentityState.READY
        SecretLoad.Missing -> IdentityState.NOT_CREATED
        SecretLoad.Unreadable -> IdentityState.SEED_UNREADABLE
    }

    /** A new phrase to show. Nothing is saved until [completeFirstRun], so closing the app mid-way just starts again. */
    fun newPhrase(): RecoveryPhrase = Bip39.generate()

    /**
     * Saves the default profile and then the seed. The seed goes last: its presence is what counts as "first run done", so the
     * profile always exists by then. Throws if first run was already done, or if the name or phone are not valid.
     */
    fun completeFirstRun(phrase: RecoveryPhrase, name: String, phone: String) {
        check(state() == IdentityState.NOT_CREATED) { "first run is already done" }
        EventBody.ProfileUpdated(name, phone) // the same rules as in events; throws IllegalArgumentException
        settings.put(NAME, name)
        settings.put(PHONE, phone)
        secrets.saveMasterSeed(Bip39.seed(phrase))
    }

    fun defaultProfile(): Profile? {
        val name = settings.get(NAME) ?: return null
        val phone = settings.get(PHONE) ?: return null
        return Profile(name, phone)
    }

    /** This person's keys in the group that uses [slot], or null if there is no usable seed. */
    fun groupIdentity(slot: Long): GroupIdentity? {
        val seed = (secrets.loadMasterSeed() as? SecretLoad.Found)?.value ?: return null
        return KeyDerivation.groupIdentity(seed, slot)
    }

    /**
     * For tests: a key slot that has never been handed out, for a made-up member who is not tied to a group id. The app does not use it any more:
     * since M11 a group's slot is made from the group's id ([owntally.crypto.KeySlots]), so nothing has to be counted or remembered.
     */
    @Synchronized
    internal fun reserveSlot(usedSlots: Collection<Long>): Long {
        val counter = settings.get(NEXT_SLOT)?.toLongOrNull() ?: 0L
        val slot = maxOf(counter, nextFreeSlot(usedSlots))
        check(slot <= MAX_SLOT) { "no key slots left" }
        settings.put(NEXT_SLOT, (slot + 1).toString())
        return slot
    }

    companion object {
        private const val NAME = "default_name"
        private const val PHONE = "default_phone"
        private const val NEXT_SLOT = "next_slot"
        private const val MAX_SLOT = 0xFFFF_FFFFL

        /** One above the highest slot in use, or 0. Throws rather than wrap around. */
        internal fun nextFreeSlot(used: Collection<Long>): Long {
            val next = if (used.isEmpty()) 0L else used.max() + 1
            check(next <= MAX_SLOT) { "no key slots left" }
            return next
        }
    }
}
