package owntally.app

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import owntally.crypto.Clock
import owntally.data.repo.GroupRepository
import owntally.data.repo.IdentityRepository
import owntally.data.secrets.InMemorySecretStore
import owntally.data.session.AddResult
import owntally.data.session.JoinRequests
import owntally.data.session.Sessions
import owntally.data.store.InMemoryEventStore
import owntally.data.store.InMemoryJoinRequestStore
import owntally.data.store.InMemorySettingsStore
import owntally.model.EventBody
import owntally.model.Money
import owntally.model.Role
import owntally.model.Split

/** The debug-build helpers that stand in for other people until joining by link exists (M6). They exist in debug builds only. */
class PretendMembersTest {
    private val secrets = InMemorySecretStore()
    private val settings = InMemorySettingsStore()
    private val clock = Clock { 1_800_000_000L }
    private val identity = IdentityRepository(secrets, settings).also { it.completeFirstRun(it.newPhrase(), "Asha", "98765 43210") }
    private val groups = GroupRepository(secrets, InMemoryEventStore(), identity, clock, SecureRandom())
    private val sessions = Sessions(groups, secrets, identity, clock, settings)
    private val requests = JoinRequests(InMemoryJoinRequestStore(), sessions, clock)
    private val pretend = PretendMembers(secrets, groups, requests, clock)
    private val id = sessions.createGroup("Goa Trip", "INR", 0)
    private val session = sessions.session(id)

    @Test fun friendsAreAddedOneByOneUpToFivePeopleWithUniqueNames() {
        val names = generateSequence { pretend.addFriend(session) }.toList()
        assertEquals(listOf("Ben (test)", "Meera (test)", "Kabir (test)", "Isha (test)"), names)
        assertEquals(5, session.view.value.state.currentMembers.size)
        assertEquals(null, pretend.addFriend(session), "a sixth person is not added")
        assertEquals(5, session.view.value.state.currentMembers.values.map { it.name }.toSet().size)
        assertEquals(5, session.view.value.state.currentMembers.values.map { it.phone }.toSet().size, "each has its own phone number")
    }

    @Test fun aPretendRequestAppearsInTheWaitingListAndCanBeApproved() {
        val name = pretend.addRequest(session)!!
        assertEquals(listOf(name), requests.waiting(id).map { it.name })
        val r = requests.waiting(id).single()
        assertTrue(requests.approve(id, r.member, name) is owntally.data.session.ApproveResult.Approved)
        assertNotNull(session.view.value.state.currentMembers[r.member])
        val second = pretend.addRequest(session)!!
        assertTrue(second != name, "a new name each time")
    }

    @Test fun aPretendMemberCanAnswerAnOwnershipOfferAndTheGroupAgrees() {
        pretend.addFriend(session)
        val ben = pretend.friends(session).single()
        assertTrue(session.offerOwnership(ben.key) is AddResult.Added)
        assertTrue(pretend.act(session, ben, EventBody.OwnershipAccepted) is AddResult.Added)
        val state = session.view.value.state
        assertEquals(ben.key, state.owner)
        assertEquals(Role.ADMIN, state.members.getValue(session.view.value.me).role, "this person became an admin")
    }

    @Test fun aPretendDeclineIsSeenByTheOwner() {
        pretend.addFriend(session)
        val ben = pretend.friends(session).single()
        session.offerOwnership(ben.key)
        assertTrue(pretend.act(session, ben, EventBody.OwnershipDeclined) is AddResult.Added)
        assertEquals(ben.key, session.view.value.state.declinedOffer)
    }

    @Test fun anEventThePretendMemberMayNotWriteIsReportedNotHidden() {
        pretend.addFriend(session)
        val ben = pretend.friends(session).single()
        val r = pretend.act(session, ben, EventBody.OwnershipAccepted) // nothing was offered
        assertTrue(r is AddResult.Refused, "$r")
    }

    @Test fun aPretendMemberCanWriteAnExpenseAndLeave() {
        pretend.addFriend(session)
        val ben = pretend.friends(session).single()
        val me = session.view.value.me
        assertTrue(pretend.act(session, ben, EventBody.ExpenseAdded("Taxi (pretend)", Money(60_000), ben.key, 20_000, Split.Equal(setOf(me, ben.key)))) is AddResult.Added)
        assertEquals(1, session.view.value.state.expenses.size)
        assertTrue(pretend.act(session, ben, EventBody.MemberRemoved(ben.key, session.view.value.state.epoch, emptyMap())) is AddResult.Added)
        assertTrue(ben.key !in session.view.value.state.currentMembers)
        assertEquals(2L, session.view.value.state.epoch, "the owner's phone changed the key after the leave (T4.11)")
    }

    @Test fun theActionsOfferOnlyWhatMakesSenseNow() {
        assertEquals(listOf("Add pretend friend: Ben (test)", "Add pretend join request"), pretend.actions(session).map { it.label })
        pretend.addFriend(session)
        val labels = pretend.actions(session).map { it.label }
        assertTrue("As Ben (test): write an expense" in labels)
        assertTrue("As Ben (test): leave the group" in labels)
        assertTrue(labels.none { it.contains("accept ownership") }, "no offer, so nothing to accept")
        session.offerOwnership(pretend.friends(session).single().key)
        val after = pretend.actions(session).map { it.label }
        assertTrue("As Ben (test): accept ownership" in after)
        assertTrue("As Ben (test): decline ownership" in after)
    }
}
