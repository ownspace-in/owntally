package owntally.ui.groups

import owntally.data.session.GroupView
import owntally.model.Permissions
import owntally.model.PublicKey
import owntally.model.Role

enum class ActionKind { MAKE_OWNER, MAKE_ADMIN, REMOVE_ADMIN, REMOVE }

data class MemberAction(val kind: ActionKind, val title: String, val subtitle: String, val destructive: Boolean = false)

/** The screen for one member (design/14). [line] is "phone · role". */
data class MemberActionsModel(val name: String, val initial: String, val colourIndex: Int, val line: String, val actions: List<MemberAction>)

object MemberActionsModels {
    /** Null when the viewer may do nothing here: not the owner, looking at themselves, or the person is not in the group. */
    fun build(view: GroupView, target: PublicKey): MemberActionsModel? {
        val state = view.state
        val viewer = Permissions.roleOf(state, view.me)
        val member = state.currentMembers[target] ?: return null
        if (target == view.me || !Permissions.removeOthers(viewer)) return null
        val name = member.name.ifBlank { "Someone" }
        val actions = buildList {
            if (Permissions.offerOwnership(viewer)) add(MemberAction(ActionKind.MAKE_OWNER, "Make owner", if (state.pendingOffer == target) "Waiting for $name to accept." else "You become an admin. $name must accept."))
            if (Permissions.changeAdmins(viewer)) {
                if (member.role == Role.ADMIN) add(MemberAction(ActionKind.REMOVE_ADMIN, "Remove admin role", "$name stays in the group as a member."))
                else add(MemberAction(ActionKind.MAKE_ADMIN, "Make admin", "$name can approve joins and invite people."))
            }
            add(MemberAction(ActionKind.REMOVE, "Remove from group", "See how balances change first.", destructive = true))
        }
        val role = when (member.role) { Role.OWNER -> "Owner"; Role.ADMIN -> "Admin"; Role.MEMBER -> "Member" }
        return MemberActionsModel(name, name.take(1).uppercase(), (target.bytes()[0].toInt() and 0xff) % MembersModels.COLOURS, "${member.phone} · $role", actions)
    }
}
