package owntally.ui.groups

import owntally.data.session.GroupView
import owntally.model.Permissions
import owntally.model.PublicKey
import owntally.model.Role

data class MemberRowModel(
    val key: PublicKey,
    /** "You" for the person looking, otherwise the name. */
    val label: String,
    /** As the person entered it. */
    val phone: String,
    val initial: String,
    val colourIndex: Int,
    val role: Role,
    /** "Owner" or "Admin"; plain members have none. */
    val badge: String?,
    val isMe: Boolean,
    /** The "…" menu: the owner only, and never on their own row. */
    val hasMenu: Boolean,
)

data class RemovedRowModel(val name: String, val phone: String, /** "Removed" or "Left". */ val how: String)

data class RoleLine(val role: Role, val label: String, val text: String)

data class MembersScreenModel(
    val groupName: String,
    val subtitle: String,
    val rows: List<MemberRowModel>,
    /** The count on the "Join requests" row, or null when this person cannot approve joins and so does not see the row. */
    val joinRequestsCount: Int?,
    val canInvite: Boolean,
    val roleLines: List<RoleLine>,
    val removed: List<RemovedRowModel>,
)

/** What the Members screen shows (design/12), worked out from the group's state. The role text follows the rules in docs/DESIGN.md, not the mockup. */
object MembersModels {
    const val COLOURS = 6

    val roleLines = listOf(
        RoleLine(Role.OWNER, "Owner", "adds and removes admins, removes members, changes the key, deletes any expense, transfers ownership."),
        RoleLine(Role.ADMIN, "Admin", "approves joins and invites people."),
        RoleLine(Role.MEMBER, "Member", "adds expenses, deletes their own, can leave the group."),
    )

    fun build(view: GroupView, waitingRequests: Int): MembersScreenModel {
        val state = view.state
        val viewer = Permissions.roleOf(state, view.me)
        fun name(m: owntally.model.Member) = m.name.ifBlank { "Someone" }
        val current = state.currentMembers.values
        val rows = (current.filter { it.key == view.me } + current.filter { it.key != view.me }.sortedBy { name(it).lowercase() }).map { m ->
            val isMe = m.key == view.me
            val label = if (isMe) "You" else name(m)
            MemberRowModel(
                m.key, label, m.phone, label.take(1).uppercase(), (m.key.bytes()[0].toInt() and 0xff) % COLOURS, m.role,
                when (m.role) { Role.OWNER -> "Owner"; Role.ADMIN -> "Admin"; Role.MEMBER -> null },
                isMe, hasMenu = !isMe && Permissions.removeOthers(viewer),
            )
        }
        val removed = state.members.values.filter { it.removed }.sortedBy { name(it).lowercase() }.map {
            RemovedRowModel(name(it), it.phone, if (state.removals[it.key]?.let { r -> r.by == r.member } == true) "Left" else "Removed")
        }
        val n = rows.size
        return MembersScreenModel(
            state.name ?: "Group", "${state.name ?: "Group"} · ${if (n == 1) "1 person" else "$n people"}", rows,
            if (Permissions.approveJoins(viewer)) waitingRequests else null,
            Permissions.approveJoins(viewer), roleLines, removed,
        )
    }
}
