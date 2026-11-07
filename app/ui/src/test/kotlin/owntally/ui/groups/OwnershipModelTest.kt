package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import owntally.model.EventBody
import owntally.model.PublicKey
import owntally.model.Role

/** Both sides of an ownership request. Only the owner's phone writes here, so the other person's view is the same state seen as that person ([copy] of `me`). */
class OwnershipModelTest {
    private val w = TestWorld()
    private val id = w.newGroup("Goa Trip")
    private val s = w.sessions.session(id)
    private val me get() = s.view.value.me
    private val rohan = w.addFriend(s, "Rohan", 1).also { s.makeAdmin(it) }
    private val meera = w.addFriend(s, "Meera", 2)

    private fun offer(target: PublicKey = rohan) = OwnerOfferModels.build(s.view.value, target)
    private fun asRohan() = s.view.value.copy(me = rohan)

    // ---- the owner's side (mockups 14b, 14c) ----------------------------------------------------------------------------

    @Test fun beforeAnyRequestTheOwnerConfirmsWithTheCorrectedText() {
        val m = offer()!!
        assertEquals(OfferStage.CONFIRM, m.stage)
        assertEquals("Make Rohan the owner?", m.title)
        assertEquals(
            listOf(
                "Rohan will be able to remove members, change the group key, and add and remove admins. You will become an admin and keep every expense and balance.",
                "Nothing changes until Rohan accepts, which they can do the next time their phone syncs.",
            ),
            m.body,
        )
        assertEquals("Send request", m.primary)
        assertNull(m.replaces)
        assertEquals("98765 00001 · Admin", m.line)
    }

    @Test fun afterSendingItWaitsAndCanBeCancelled() {
        s.offerOwnership(rohan)
        val m = offer()!!
        assertEquals(OfferStage.WAITING, m.stage)
        assertEquals("Waiting for Rohan to accept", m.title)
        assertEquals(listOf("You stay the owner until they accept. If they don't, you can cancel this request at any time."), m.body)
        assertEquals("Cancel request", m.primary)
    }

    @Test fun afterADeclineTheOwnerSeesItAndCanAskAgainOrChooseSomeoneElse() {
        s.offerOwnership(rohan)
        val declined = declinedState()
        val m = OwnerOfferModels.build(declined, rohan)!!
        assertEquals(OfferStage.DECLINED, m.stage)
        assertEquals("Rohan declined", m.title)
        assertEquals("Ask Rohan again", m.primary)
        assertEquals("Choose someone else", m.secondary)
    }

    @Test fun aRequestToSomeoneElseIsReplacedAndTheScreenSaysSo() {
        s.offerOwnership(meera)
        val m = offer(rohan)!!
        assertEquals(OfferStage.CONFIRM, m.stage)
        assertEquals("This replaces your waiting request to Meera.", m.replaces)
    }

    @Test fun onlyTheOwnerGetsTheScreenAndNotForThemselvesOrSomeoneGone() {
        assertNull(OwnerOfferModels.build(s.view.value, me))
        assertNull(OwnerOfferModels.build(asRohan(), meera))
        s.removeMember(meera)
        assertNull(offer(meera))
    }

    // ---- the offered person's side (D1) ----------------------------------------------------------------------------------

    @Test fun onlyTheOfferedPersonSeesTheBanner() {
        assertNull(OwnershipBanners.build(asRohan()))
        s.offerOwnership(rohan)
        assertNull(OwnershipBanners.build(s.view.value), "the owner does not")
        assertNull(OwnershipBanners.build(s.view.value.copy(me = meera)), "nor anyone else")
        val b = OwnershipBanners.build(asRohan())!!
        assertEquals("Asha wants to make you the owner of Goa Trip", b.text)
        assertEquals("Become the owner of Goa Trip?", b.confirmTitle)
        assertEquals(
            "You will be able to remove members, change the group key, and add and remove admins. Asha will become an admin.",
            b.confirmBody,
        )
    }

    @Test fun theBannerGoesAwayWhenTheOfferEnds() {
        s.offerOwnership(rohan)
        s.cancelOffer()
        assertNull(OwnershipBanners.build(asRohan()))
    }

    @Test fun theBannerGoesAwayWhenTheOfferMovesToSomeoneElse() {
        s.offerOwnership(rohan)
        s.offerOwnership(meera)
        assertNull(OwnershipBanners.build(asRohan()))
        assertEquals(true, OwnershipBanners.build(s.view.value.copy(me = meera)) != null)
    }

    @Test fun theGroupsCardHasTheTagOnlyForTheOfferedPerson() {
        val g = owntally.data.session.GroupSummary(id, "Goa Trip", 0, 3, 0, owntally.model.Money.ZERO, true, ownershipOfferForMe = true)
        assertEquals(true, GroupsModels.list(listOf(g)).cards.single().ownershipOffer)
        assertEquals(false, GroupsModels.list(listOf(g.copy(ownershipOfferForMe = false))).cards.single().ownershipOffer)
    }

    @Test fun theMemberActionsScreenTellsAWaitingRequestApart() {
        s.offerOwnership(rohan)
        val a = MemberActionsModels.build(s.view.value, rohan)!!.actions.first { it.kind == ActionKind.MAKE_OWNER }
        assertEquals("Waiting for Rohan to accept.", a.subtitle)
        val other = MemberActionsModels.build(s.view.value, meera)!!.actions.first { it.kind == ActionKind.MAKE_OWNER }
        assertEquals("You become an admin. Meera must accept.", other.subtitle)
    }

    @Test fun roleAfterAcceptingIsTheCoresBusinessButTheNamesAreRight() {
        assertEquals(Role.OWNER, s.view.value.state.members.getValue(me).role)
    }

    /** The state after Rohan's phone wrote a decline. The core sets `declinedOffer` (ReplayTest checks that with real events); here it is set directly. */
    private fun declinedState(): owntally.data.session.GroupView {
        val v = s.view.value
        return v.copy(state = v.state.copy(pendingOffer = null, declinedOffer = rohan))
    }
}
