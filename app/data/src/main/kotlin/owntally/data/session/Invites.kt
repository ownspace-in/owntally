package owntally.data.session

import owntally.crypto.Clock
import owntally.crypto.GroupKeys
import owntally.crypto.Ticket
import owntally.crypto.TicketRecords
import owntally.crypto.TicketRegistry
import owntally.data.repo.GroupRepository
import owntally.data.repo.IdentityRepository
import owntally.data.secrets.SecretStore
import owntally.model.GroupId
import owntally.model.Role

/** An invitation as the screen shows it. It holds the ticket, which is a secret: [toString] shows nothing. */
class Invite(private val ticket: Ticket) {
    val ticketId: ByteArray get() = ticket.ticketId()
    val expiresAtSeconds: Long get() = ticket.expiresAtSeconds

    /** The text to share and to put in the QR code: the link of DESIGN.md "Pairing: the ticket", with the ticket after the `#` (never sent to the web host). */
    val link: String get() = Invites.LINK_START + ticket.encode()

    override fun toString() = "Invite([redacted])"
}

/**
 * The host's invitations for each group (M6, T6.3 and T6.4). The records live in [records] (the database), so issued and used tickets
 * survive a restart; the group keys are read from the keyring when a ticket is looked up, so no second copy of a key is stored.
 * Only the owner and admins may invite (DESIGN.md).
 */
class Invites(
    private val records: TicketRecords,
    private val groups: GroupRepository,
    private val secrets: SecretStore,
    private val identity: IdentityRepository,
    private val sessions: Sessions,
    private val clock: Clock,
) {
    private val keys = GroupKeys { group, epoch -> secrets.loadKeyring(group).keyring[epoch] }

    /** The registry of tickets issued by this phone's key in the group; null if the phone has no key for it (not a group of this phone). */
    fun registry(group: GroupId): TicketRegistry? {
        val row = groups.group(group) ?: return null
        val host = identity.groupIdentity(row.keySlot) ?: return null
        return TicketRegistry(host.publicKey, clock, records = records, keys = keys)
    }

    /** True for a current member who is the owner or an admin. */
    fun canInvite(group: GroupId): Boolean {
        if (groups.group(group) == null) return false
        val view = sessions.session(group).view.value
        val me = view.state.currentMembers[view.me] ?: return false
        return me.role == Role.OWNER || me.role == Role.ADMIN
    }

    /** The newest invitation that still works, if there is one. */
    fun current(group: GroupId): Invite? {
        if (!canInvite(group)) return null
        return registry(group)?.open(group)?.firstOrNull()?.let { Invite(it) }
    }

    /** Every invitation that still works, newest first (the list on the Join requests screen). */
    fun open(group: GroupId): List<Invite> {
        if (!canInvite(group)) return emptyList()
        return registry(group)?.open(group)?.map { Invite(it) }.orEmpty()
    }

    /** A new invitation for the group's current key. Null if this person may not invite or the phone has no current key. */
    fun create(group: GroupId): Invite? {
        if (!canInvite(group)) return null
        val registry = registry(group) ?: return null
        val epoch = sessions.session(group).view.value.state.epoch
        val key = secrets.loadKeyring(group).keyring[epoch] ?: return null
        return Invite(registry.issue(group, epoch, key))
    }

    /** Cancels an invitation: it stops working at once. Anyone may be asked, but only the owner and admins have one to cancel. */
    fun revoke(group: GroupId, invite: Invite) {
        if (canInvite(group)) registry(group)?.revoke(invite.ticketId)
    }

    companion object {
        const val LINK_START = "https://joinowntally.ownspace.in/j#"
    }
}
