package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import owntally.model.PublicKey
import owntally.model.Role

/** What each viewer can do to each kind of person, for every combination (the owner may act on others; nobody else may act on anyone). */
class MemberActionsModelTest {
    private val w = TestWorld()
    private val s = w.sessions.session(w.newGroup("Goa Trip"))
    private val owner get() = s.view.value.me
    private val admin = w.addFriend(s, "Rohan", 1).also { s.makeAdmin(it) }
    private val member = w.addFriend(s, "Meera", 2)
    private val other = w.addFriend(s, "Kabir", 3)

    private fun build(viewer: PublicKey, target: PublicKey) = MemberActionsModels.build(s.view.value.copy(me = viewer), target)

    private fun kinds(viewer: PublicKey, target: PublicKey) = build(viewer, target)?.actions?.map { it.kind }

    @Test fun theOwnerLookingAtAnAdminCanMakeOwnerRemoveTheAdminRoleOrRemove() {
        assertEquals(listOf(ActionKind.MAKE_OWNER, ActionKind.REMOVE_ADMIN, ActionKind.REMOVE), kinds(owner, admin))
    }

    @Test fun theOwnerLookingAtAMemberCanMakeOwnerMakeAdminOrRemove() {
        assertEquals(listOf(ActionKind.MAKE_OWNER, ActionKind.MAKE_ADMIN, ActionKind.REMOVE), kinds(owner, member))
    }

    @Test fun theOwnerCannotActOnThemselves() {
        assertNull(build(owner, owner))
    }

    @Test fun anAdminAndAMemberGetNoActionsOnAnyone() {
        for (viewer in listOf(admin, member)) for (target in listOf(owner, admin, member, other)) assertNull(build(viewer, target), "viewer ${viewer.toHex().take(4)} target ${target.toHex().take(4)}")
    }

    @Test fun nothingCanBeDoneToSomeoneWhoIsNotInTheGroup() {
        s.removeMember(other)
        assertNull(build(owner, other))
        assertNull(build(owner, PublicKey(ByteArray(32) { 9 })))
    }

    @Test fun theHeaderShowsTheNamePhoneAndRole() {
        val m = build(owner, admin)!!
        assertEquals("Rohan", m.name)
        assertEquals("R", m.initial)
        assertEquals("98765 00001 · Admin", m.line)
        assertEquals("98765 00002 · Member", build(owner, member)!!.line)
    }

    @Test fun theWordsSayWhatHappens() {
        val a = build(owner, admin)!!.actions.associateBy { it.kind }
        assertEquals("Make owner", a.getValue(ActionKind.MAKE_OWNER).title)
        assertEquals("You become an admin. Rohan must accept.", a.getValue(ActionKind.MAKE_OWNER).subtitle)
        assertEquals("Remove admin role", a.getValue(ActionKind.REMOVE_ADMIN).title)
        assertEquals("Rohan stays in the group as a member.", a.getValue(ActionKind.REMOVE_ADMIN).subtitle)
        assertEquals("Remove from group", a.getValue(ActionKind.REMOVE).title)
        assertEquals("See how balances change first.", a.getValue(ActionKind.REMOVE).subtitle)
        assertEquals(true, a.getValue(ActionKind.REMOVE).destructive)
        val m = build(owner, member)!!.actions.associateBy { it.kind }
        assertEquals("Make admin", m.getValue(ActionKind.MAKE_ADMIN).title)
        assertEquals("Meera can approve joins and invite people.", m.getValue(ActionKind.MAKE_ADMIN).subtitle)
    }

    @Test fun theRolesAreRealRolesSoTheTableAgreesWithTheCore() {
        assertEquals(Role.ADMIN, s.view.value.state.members.getValue(admin).role)
    }
}
