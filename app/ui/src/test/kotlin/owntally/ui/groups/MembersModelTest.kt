package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.data.session.AddResult
import owntally.data.session.GroupSession
import owntally.model.Role

/** What the Members screen shows to the owner, an admin and a plain member. The members are written by the owner's session; the viewer is chosen by [viewAs]. */
class MembersModelTest {
    private val w = TestWorld()
    private val id = w.newGroup("Goa Trip")
    private val s: GroupSession = w.sessions.session(id)
    private val me get() = s.view.value.me
    private val rohan = w.addFriend(s, "Rohan", 1)
    private val meera = w.addFriend(s, "Meera", 2)
    private val kabir = w.addFriend(s, "Kabir", 3)

    private fun model(viewer: owntally.model.PublicKey = me, waiting: Int = 0) = MembersModels.build(s.view.value.copy(me = viewer), waiting)

    @Test fun youComeFirstThenTheOthersByNameWithRoleBadges() {
        s.makeAdmin(rohan)
        val m = model()
        assertEquals(listOf("You", "Kabir", "Meera", "Rohan"), m.rows.map { it.label })
        assertEquals(listOf("Owner", null, null, "Admin"), m.rows.map { it.badge })
        assertEquals("98765 43210", m.rows.first().phone, "the phone is shown as it was entered")
        assertEquals("Goa Trip · 4 people", m.subtitle)
    }

    @Test fun oneAloneIsOnePerson() {
        val g = w.newGroup("Solo")
        assertEquals("Solo · 1 person", MembersModels.build(w.sessions.session(g).view.value, 0).subtitle)
    }

    @Test fun onlyTheOwnerGetsTheMenuAndNeverOnTheirOwnRow() {
        s.makeAdmin(rohan)
        assertEquals(listOf(false, true, true, true), model().rows.map { it.hasMenu })
        assertTrue(model(rohan).rows.none { it.hasMenu }, "an admin has no menu")
        assertTrue(model(meera).rows.none { it.hasMenu }, "a member has no menu")
    }

    @Test fun joinRequestsAndInviteAreOfferedToTheOwnerAndAdminsOnly() {
        s.makeAdmin(rohan)
        assertEquals(2, model(waiting = 2).joinRequestsCount)
        assertEquals(2, model(rohan, waiting = 2).joinRequestsCount)
        assertNull(model(meera, waiting = 2).joinRequestsCount)
        assertTrue(model().canInvite && model(rohan).canInvite)
        assertFalse(model(meera).canInvite)
    }

    @Test fun theRoleTextMatchesTheRulesNotTheMockup() {
        val text = model().roleLines.associate { it.role to it.text }
        assertEquals(setOf(Role.OWNER, Role.ADMIN, Role.MEMBER), text.keys)
        assertEquals("adds and removes admins, removes members, changes the key, deletes any expense, transfers ownership.", text.getValue(Role.OWNER))
        assertEquals("approves joins and invites people.", text.getValue(Role.ADMIN))
        assertEquals("adds expenses, deletes their own, can leave the group.", text.getValue(Role.MEMBER))
    }

    @Test fun removedAndLeftPeopleAreInTheirOwnSectionAndNotInTheList() {
        // Meera is removed by the owner; Kabir leaves (a leave is written by the member's own phone, so use its event through the core directly).
        assertTrue(s.removeMember(meera) is AddResult.Added)
        val m = model()
        assertEquals(listOf("You", "Kabir", "Rohan"), m.rows.map { it.label })
        assertEquals("Goa Trip · 3 people", m.subtitle)
        assertEquals(listOf("Meera"), m.removed.map { it.name })
        assertEquals(listOf("Removed"), m.removed.map { it.how })
    }

    @Test fun aMemberWhoLeftIsShownAsLeftNotRemoved() {
        val view = s.view.value
        val left = view.state.copy(
            members = view.state.members.mapValues { if (it.key == kabir) it.value.copy(removed = true) else it.value },
            removals = mapOf(kabir to owntally.model.Removal(kabir, kabir, owntally.model.EventId(kabir, 1), 0)),
        )
        val m = MembersModels.build(view.copy(state = left), 0)
        assertEquals(listOf("Left"), m.removed.map { it.how })
    }

    @Test fun nobodyRemovedMeansNoSection() {
        assertEquals(emptyList(), model().removed)
    }
}
