package owntally.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One table says who may do what (M4, T4.2). The screens read it; this test runs every action by every role through replay and
 * checks the table tells the truth, so a screen can never offer something the rules refuse, nor hide something they allow.
 */
class PermissionsTest {
    private val owner = key(1)
    private val admin = key(2)
    private val member = key(3)
    private val target = key(4)
    private val outsider = key(9)

    /** A group with an owner, an admin, a plain member and a plain member to act on. */
    private fun group(): Sim = Sim().also { s ->
        s.emit(owner, EventBody.GroupCreated(GROUP, "G", "INR", encKeyFor(owner)))
        s.emit(owner, EventBody.ProfileUpdated("Asha", PHONE))
        s.join(owner, admin, "Ben")
        s.join(owner, member, "Chitra")
        s.join(owner, target, "Dev")
        s.emit(owner, EventBody.AdminAdded(admin))
    }

    private val roles = listOf(Role.OWNER to owner, Role.ADMIN to admin, Role.MEMBER to member)

    @Test fun approvingJoinsMatchesReplay() {
        for ((role, who) in roles) {
            val s = group()
            val join = s.join(who, key(30), "Eve")
            assertEquals(Permissions.approveJoins(role), join.reason(s.state()) == null, "$role")
        }
        assertFalse(Permissions.approveJoins(null))
    }

    @Test fun removingSomeoneElseMatchesReplay() {
        for ((role, who) in roles) {
            val s = group()
            val removal = s.remove(who, target)
            assertEquals(Permissions.removeOthers(role), removal.reason(s.state()) == null, "$role")
        }
    }

    @Test fun changingTheKeyMatchesReplay() {
        for ((role, who) in roles) {
            val s = group()
            val rotate = s.rotate(who)
            assertEquals(Permissions.changeKey(role), rotate.reason(s.state()) == null, "$role")
        }
    }

    @Test fun makingAndUnmakingAdminsMatchesReplay() {
        for ((role, who) in roles) {
            val make = group().let { s -> s.emit(who, EventBody.AdminAdded(target)).reason(s.state()) == null }
            val unmake = group().let { s -> s.emit(who, EventBody.AdminRemoved(admin)).reason(s.state()) == null }
            assertEquals(Permissions.changeAdmins(role), make, "make admin by $role")
            assertEquals(Permissions.changeAdmins(role), unmake, "remove admin by $role")
        }
    }

    @Test fun offeringOwnershipMatchesReplay() {
        for ((role, who) in roles) {
            val s = group()
            val to = if (who == target) member else target
            val offer = s.emit(who, EventBody.OwnershipOffered(to))
            assertEquals(Permissions.offerOwnership(role), offer.reason(s.state()) == null, "$role")
        }
    }

    @Test fun leavingMatchesReplay() {
        for ((role, who) in roles) {
            val s = group()
            val leave = s.remove(who, who)
            assertEquals(Permissions.leave(role), leave.reason(s.state()) == null, "$role")
        }
    }

    @Test fun deletingSomeoneElsesExpenseMatchesReplay() {
        for ((role, who) in roles) {
            val s = group()
            val others = s.expense(target, rupees(10), target, Split.Equal(setOf(target)))
            if (who == target) continue
            val delete = s.emit(who, EventBody.ExpenseDeleted(others.id))
            assertEquals(Permissions.deleteOthersExpenses(role), delete.reason(s.state()) == null, "$role")
        }
    }

    @Test fun nobodyWhoIsNotInTheGroupMayDoAnything() {
        val s = group()
        assertNull(null)
        for (f in listOf(Permissions::approveJoins, Permissions::removeOthers, Permissions::changeKey, Permissions::changeAdmins, Permissions::offerOwnership, Permissions::leave, Permissions::deleteOthersExpenses)) {
            assertFalse(f(null))
        }
        assertEquals(IgnoreReason.NOT_MEMBER, s.join(outsider, key(31), "Zed").reason(s.state()))
    }

    @Test fun theRoleOfAMemberIsReadFromTheState() {
        val state = group().state()
        assertEquals(Role.OWNER, Permissions.roleOf(state, owner))
        assertEquals(Role.ADMIN, Permissions.roleOf(state, admin))
        assertEquals(Role.MEMBER, Permissions.roleOf(state, member))
        assertEquals(null, Permissions.roleOf(state, outsider))
        val after = group().also { it.remove(owner, target) }.state()
        assertEquals(null, Permissions.roleOf(after, target), "a removed member has no role")
    }

    @Test fun theTableInWords() {
        assertTrue(Permissions.approveJoins(Role.OWNER) && Permissions.approveJoins(Role.ADMIN) && !Permissions.approveJoins(Role.MEMBER))
        assertTrue(Permissions.removeOthers(Role.OWNER) && !Permissions.removeOthers(Role.ADMIN) && !Permissions.removeOthers(Role.MEMBER))
        assertTrue(Permissions.leave(Role.MEMBER) && !Permissions.leave(Role.ADMIN) && !Permissions.leave(Role.OWNER))
    }
}
