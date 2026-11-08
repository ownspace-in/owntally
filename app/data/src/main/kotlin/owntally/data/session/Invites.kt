package owntally.data.session

import owntally.crypto.Clock
import owntally.crypto.GroupKeys
import owntally.crypto.TicketRecords
import owntally.crypto.TicketRegistry
import owntally.data.repo.GroupRepository
import owntally.data.repo.IdentityRepository
import owntally.data.secrets.SecretStore
import owntally.model.GroupId

/**
 * The host's tickets for each group (M6, T6.3). The records live in [records] (the database), so issued and used tickets survive
 * a restart; the group keys are read from the keyring when a ticket is looked up, so no second copy of a key is stored.
 */
class Invites(
    private val records: TicketRecords,
    private val groups: GroupRepository,
    private val secrets: SecretStore,
    private val identity: IdentityRepository,
    private val clock: Clock,
) {
    private val keys = GroupKeys { group, epoch -> secrets.loadKeyring(group).keyring[epoch] }

    /** The registry of tickets issued by this phone's key in the group; null if the phone has no key for it (not a group of this phone). */
    fun registry(group: GroupId): TicketRegistry? {
        val row = groups.group(group) ?: return null
        val host = identity.groupIdentity(row.keySlot) ?: return null
        return TicketRegistry(host.publicKey, clock, records = records, keys = keys)
    }
}
