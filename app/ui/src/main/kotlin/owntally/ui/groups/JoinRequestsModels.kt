package owntally.ui.groups

import owntally.data.session.GroupView
import owntally.data.store.JoinRequest
import owntally.data.store.JoinState
import owntally.model.MAX_MEMBERS
import owntally.model.Permissions
import owntally.model.PublicKey
import owntally.model.isNameTaken

/** "12 min ago". A time a little in the future (clocks differ) counts as just now. */
object RelativeTime {
    fun ago(now: Long, then: Long): String {
        val s = now - then
        return when {
            s < 60 -> "just now"
            s < 3_600 -> "${s / 60} min ago"
            s < 86_400 -> (s / 3_600).let { if (it == 1L) "1 hr ago" else "$it hrs ago" }
            else -> (s / 86_400).let { if (it == 1L) "1 day ago" else "$it days ago" }
        }
    }
}

data class JoinRequestRow(val member: PublicKey, val name: String, val phone: String, val initial: String, val colourIndex: Int, val ago: String)

/** An invitation this phone made that still works (design/11, "Open invites"). [id] is what Revoke is given back. */
data class OpenInviteInfo(val id: String, val expiresAtSeconds: Long)

data class OpenInviteRow(val id: String, val title: String, val detail: String)

data class JoinRequestsScreenModel(
    val groupName: String,
    val heading: String,
    val rows: List<JoinRequestRow>,
    val empty: String?,
    /** The group has the most people it can have, so Approve will say no. */
    val full: Boolean,
    val note: String,
    val openInvitesHeading: String = "OPEN INVITES",
    val openInvites: List<OpenInviteRow> = emptyList(),
    /** Shown instead of rows when there is no open invite; the "New invite" button is always there. */
    val noOpenInvites: String? = null,
    val newInviteLabel: String = "New invite",
)

object JoinRequestsModels {
    /** Null when this person cannot approve joins. [now] is in seconds. */
    fun build(view: GroupView, requests: List<JoinRequest>, now: Long, openInvites: List<OpenInviteInfo> = emptyList()): JoinRequestsScreenModel? {
        if (!Permissions.approveJoins(Permissions.roleOf(view.state, view.me))) return null
        val waiting = requests.filter { it.state == JoinState.WAITING }.sortedBy { it.receivedAtSeconds }
        val rows = waiting.map {
            JoinRequestRow(it.member, it.name, it.phone, it.name.trim().take(1).uppercase(), (it.member.bytes()[0].toInt() and 0xff) % MembersModels.COLOURS, RelativeTime.ago(now, it.receivedAtSeconds))
        }
        return JoinRequestsScreenModel(
            view.state.name ?: "Group", "Waiting for you · ${rows.size}", rows, if (rows.isEmpty()) "No one is waiting to join." else null,
            view.state.currentMembers.size >= MAX_MEMBERS,
            "Approving lets the person read every expense in this group. Decline anyone you don't recognise.",
            openInvites = openInvites.filter { it.expiresAtSeconds > now }.sortedBy { it.expiresAtSeconds }
                .map { OpenInviteRow(it.id, "Invite sent by you", "Unused · expires in ${InviteModels.timeLeft(it.expiresAtSeconds - now)}") },
            noOpenInvites = if (openInvites.none { it.expiresAtSeconds > now }) "No open invites." else null,
        )
    }
}

/** The name field when approving, checked as it is typed. The name taken rule is the core's own [isNameTaken], the one replay enforces. */
data class ApproveCheck(val name: String, val problem: String?) {
    val canApprove: Boolean get() = problem == null

    companion object {
        fun of(view: GroupView, typed: String): ApproveCheck {
            val name = typed.trim()
            val problem = when {
                view.state.currentMembers.size >= MAX_MEMBERS -> "This group already has $MAX_MEMBERS people, the most it can have. Remove someone first."
                name.isEmpty() -> "Type a name."
                name.length > 40 -> "Use at most 40 letters."
                isNameTaken(view.state, name) -> "Someone in this group already has this name. Try adding an initial, like Rohan V."
                else -> owntally.model.NameText.problem(name)
            }
            return ApproveCheck(name, problem)
        }
    }
}
