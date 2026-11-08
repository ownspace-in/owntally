package owntally.data.session

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import owntally.crypto.Clock
import owntally.crypto.Ticket
import owntally.data.repo.GroupRepository
import owntally.data.repo.IdentityRepository
import owntally.data.secrets.SecretStore
import owntally.data.store.PendingJoin
import owntally.data.store.PendingJoinStore
import owntally.data.store.PendingState
import owntally.model.EventBody
import owntally.model.GroupId
import owntally.sync.GroupRoom
import owntally.sync.JoinClient
import owntally.sync.JoinOutcome
import owntally.sync.JoinStatus
import owntally.sync.Link
import owntally.sync.Transport

/** What opening an invitation found (M6, T6.5). */
sealed class OpenResult {
    /** The text is not an invitation (or is damaged). */
    data object NotAnInvite : OpenResult()

    /** The invitation's time is up. */
    data object Expired : OpenResult()

    data class AlreadyInGroup(val group: GroupId) : OpenResult()

    /** Opened. [preview] says whether the host described the group (decision J1); [join] has what it said (empty names if it could not be asked). */
    data class Opened(val join: PendingJoin, val preview: PreviewState) : OpenResult()
}

enum class PreviewState {
    /** The host answered: the group's name, owner and member count are in the invitation. */
    FOUND,

    /** The host could not be reached now; the person can still ask to join. */
    HOST_UNREACHABLE,

    /** The host says the invitation no longer works (used, expired or revoked). */
    NO_LONGER_VALID,

    /** Whoever answered could not prove it is the host the invitation names. */
    NOT_VERIFIED,
}

sealed class RequestResult {
    data object Waiting : RequestResult()
    data object HostUnreachable : RequestResult()
    data object NoLongerValid : RequestResult()
    data object NotVerified : RequestResult()

    /** The name or number would not be accepted by the group; nothing was sent. */
    data object InvalidDetails : RequestResult()
    data object NoSuchInvitation : RequestResult()
}

sealed class CheckResult {
    data object StillWaiting : CheckResult()

    /** Admitted: the group is on this phone and its history is on its way. */
    data object Approved : CheckResult()
    data object Declined : CheckResult()

    /** The host has no record of the request (it was lost, or the host's phone was reset): ask for a new invitation. */
    data object Unknown : CheckResult()
    data object HostUnreachable : CheckResult()
    data object NoSuchInvitation : CheckResult()
}

/**
 * The joiner's side of joining a group (M6, T6.5; DESIGN.md "Join by link"). An invitation is opened (the host is asked what the group
 * is called), the person types a name and number, the request is sent, and the phone checks from time to time whether the host's person
 * approved it; on approval the group appears on this phone and the history comes by sync. What the phone has done so far is kept in the
 * database ([pending]) so closing the app loses nothing, and the group key is kept with the other keys from the moment the invitation is
 * opened, so the history can be read as soon as it arrives.
 */
class JoinGroup(
    private val groups: GroupRepository,
    private val secrets: SecretStore,
    private val identity: IdentityRepository,
    private val pending: PendingJoinStore,
    private val sessions: Sessions,
    private val clock: Clock,
    /** The transport for a group, for the key this phone uses in it. */
    private val transportFor: (GroupId, owntally.model.PublicKey) -> Transport,
    /** Called when the host has approved: start syncing the group, beginning with the host (who knows the group; this phone knows no one yet). */
    private val onApproved: (GroupId, owntally.model.PublicKey) -> Unit = { _, _ -> },
    private val connectTimeoutMs: Long = 15_000,
) {
    /** The name and number the person gave at first run, offered as the starting point of the join form. */
    fun defaultProfile() = identity.defaultProfile()

    fun pending(): List<PendingJoin> = pending.all()
    fun pending(group: GroupId): PendingJoin? = pending.get(group)

    private fun ticketOf(p: PendingJoin): Ticket? {
        val key = secrets.loadKeyring(p.group).keyring[p.epoch] ?: return null
        return Ticket(p.ticketId, p.host, p.group, p.epoch, key, p.expiresAtSeconds, "")
    }

    /** Connects to the host with the key this phone uses in the group; null if it cannot. */
    private suspend fun connect(group: GroupId, keySlot: Long, host: owntally.model.PublicKey): Link? {
        val me = identity.groupIdentity(keySlot) ?: return null
        return try {
            withTimeoutOrNull(connectTimeoutMs) { transportFor(group, me.publicKey).connect(GroupRoom.fromGroupId(group.bytes()), host) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null // a transport that cannot connect says so in its own way; to the person it is all "not reachable"
        }
    }

    /** Opens what the person pasted or scanned: an invitation link or the bare ticket. */
    suspend fun open(text: String): OpenResult {
        val ticket = Ticket.fromShared(text) ?: return OpenResult.NotAnInvite
        if (ticket.isExpiredForJoiner(clock.nowSeconds())) return OpenResult.Expired
        if (groups.group(ticket.groupId) != null) return OpenResult.AlreadyInGroup(ticket.groupId)
        val earlier = pending.get(ticket.groupId)
        if (earlier != null && earlier.state != PendingState.OPENED && earlier.ticketId.contentEquals(ticket.ticketId())) return OpenResult.Opened(earlier, PreviewState.FOUND)

        val slot = earlier?.keySlot ?: identity.reserveSlot(groups.listGroups().map { it.keySlot } + pending.all().map { it.keySlot })
        secrets.saveGroupKey(ticket.groupId, ticket.epoch, ticket.groupKey)
        val base = PendingJoin(
            ticket.groupId, ticket.hostId, ticket.ticketId(), slot, ticket.epoch, ticket.expiresAtSeconds, earlier?.name.orEmpty(), earlier?.phone.orEmpty(),
            PendingState.OPENED, "", "", 0, earlier?.openedAtSeconds ?: clock.nowSeconds(),
        )
        pending.put(base)
        val state = fetchPreview(base, ticket)
        return OpenResult.Opened(pending.get(ticket.groupId) ?: base, state)
    }

    /** Asks the host again what the group is, for an invitation already opened (the first try may have found it out of reach). */
    suspend fun refreshPreview(group: GroupId): PreviewState? {
        val p = pending.get(group) ?: return null
        val ticket = ticketOf(p) ?: return null
        return fetchPreview(p, ticket)
    }

    private suspend fun fetchPreview(base: PendingJoin, ticket: Ticket): PreviewState {
        val link = connect(ticket.groupId, base.keySlot, ticket.hostId) ?: return PreviewState.HOST_UNREACHABLE
        val me = identity.groupIdentity(base.keySlot) ?: return PreviewState.HOST_UNREACHABLE
        val result = try {
            JoinClient(ticket, me, "", "").preview(link)
        } finally {
            runCatching { link.close() }
        }
        val p = result.preview
        return when {
            p != null -> { pending.put(base.copy(groupName = p.groupName, ownerName = p.ownerName, memberCount = p.memberCount)); PreviewState.FOUND }
            result.outcome == JoinOutcome.REFUSED -> PreviewState.NO_LONGER_VALID
            result.outcome == JoinOutcome.HOST_NOT_VERIFIED -> PreviewState.NOT_VERIFIED
            else -> PreviewState.HOST_UNREACHABLE
        }
    }

    /** Sends the request with the name and number the person typed. Can be repeated until the host has it. */
    suspend fun request(group: GroupId, name: String, phone: String): RequestResult {
        val p = pending.get(group) ?: return RequestResult.NoSuchInvitation
        val ticket = ticketOf(p) ?: return RequestResult.NoSuchInvitation
        if (p.state == PendingState.WAITING) return RequestResult.Waiting
        if (runCatching { EventBody.ProfileUpdated(name.trim(), phone.trim()) }.isFailure) return RequestResult.InvalidDetails
        val me = identity.groupIdentity(p.keySlot) ?: return RequestResult.HostUnreachable
        val link = connect(group, p.keySlot, p.host) ?: return RequestResult.HostUnreachable
        val outcome = try {
            JoinClient(ticket, me, name.trim(), phone.trim()).requestToJoin(link)
        } finally {
            runCatching { link.close() }
        }
        return when (outcome) {
            JoinOutcome.REQUESTED -> {
                pending.put(p.copy(name = name.trim(), phone = phone.trim(), state = PendingState.WAITING))
                RequestResult.Waiting
            }
            // The host did not take it. Maybe it did take an earlier try whose answer got lost: ask before giving up.
            JoinOutcome.REFUSED -> when (statusOf(p, ticket)) {
                JoinStatus.PENDING -> { pending.put(p.copy(name = name.trim(), phone = phone.trim(), state = PendingState.WAITING)); RequestResult.Waiting }
                JoinStatus.APPROVED -> { pending.put(p.copy(name = name.trim(), phone = phone.trim(), state = PendingState.WAITING)); RequestResult.Waiting }
                else -> RequestResult.NoLongerValid
            }
            JoinOutcome.HOST_NOT_VERIFIED -> RequestResult.NotVerified
            JoinOutcome.INTERRUPTED, JoinOutcome.TIMED_OUT -> RequestResult.HostUnreachable
        }
    }

    private suspend fun statusOf(p: PendingJoin, ticket: Ticket): JoinStatus? {
        val me = identity.groupIdentity(p.keySlot) ?: return null
        val link = connect(p.group, p.keySlot, p.host) ?: return null
        return try {
            JoinClient(ticket, me, p.name, p.phone).status(link).status
        } finally {
            runCatching { link.close() }
        }
    }

    /** Asks the host what became of the request. On approval the group is made on this phone and [onApproved] starts the sync. */
    suspend fun check(group: GroupId): CheckResult {
        val p = pending.get(group) ?: return CheckResult.NoSuchInvitation
        if (p.state == PendingState.OPENED) return CheckResult.StillWaiting
        val ticket = ticketOf(p) ?: return CheckResult.NoSuchInvitation
        return when (statusOf(p, ticket)) {
            null -> CheckResult.HostUnreachable
            JoinStatus.PENDING -> CheckResult.StillWaiting
            JoinStatus.UNKNOWN -> CheckResult.Unknown
            JoinStatus.DECLINED -> { pending.put(p.copy(state = PendingState.DECLINED)); CheckResult.Declined }
            JoinStatus.APPROVED -> {
                groups.joinGroup(group, p.keySlot)
                pending.put(p.copy(state = PendingState.APPROVED))
                sessions.refresh()
                onApproved(group, p.host)
                CheckResult.Approved
            }
        }
    }

    /** True, and the invitation's record is dropped, once this person is a member as the group's own events show (the history has arrived). */
    fun completeIfMember(group: GroupId): Boolean {
        val p = pending.get(group) ?: return false
        if (p.state != PendingState.APPROVED) return false
        val view = try { sessions.session(group).view.value } catch (e: IllegalStateException) { return false }
        if (view.me !in view.state.currentMembers) return false
        pending.delete(group)
        return true
    }

    /** Gives up on an invitation: its record and the group key kept for it are deleted (if no group was made from it). */
    fun discard(group: GroupId) {
        pending.get(group) ?: return
        pending.delete(group)
        if (groups.group(group) == null) secrets.deleteGroup(group)
    }

    /**
     * For a phone restored from a recovery phrase (CRYPTO.md section 2, clarification C8): which of the first [maxSlots] key slots is already
     * a member of the invitation's group, if any. The slots are tried in order. The host answers for a key only to its holder.
     */
    suspend fun findMySlot(ticket: Ticket, maxSlots: Int = 16): Long? {
        for (slot in 0L until maxSlots) {
            val me = identity.groupIdentity(slot) ?: return null
            val link = connect(ticket.groupId, slot, ticket.hostId) ?: return null
            val status = try {
                JoinClient(ticket, me, "", "").status(link).status
            } finally {
                runCatching { link.close() }
            }
            if (status == JoinStatus.APPROVED) return slot
        }
        return null
    }
}
