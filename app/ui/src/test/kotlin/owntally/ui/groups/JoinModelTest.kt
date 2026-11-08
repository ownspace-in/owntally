package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.data.session.CheckResult
import owntally.data.session.PreviewState
import owntally.data.session.RequestResult
import owntally.data.store.PendingJoin
import owntally.data.store.PendingState
import owntally.model.GroupId
import owntally.model.PublicKey

class JoinModelTest {
    private val now = 1_800_000_000L
    private fun join(state: PendingState = PendingState.OPENED, name: String = "Goa Trip", owner: String = "Asha", count: Int = 5, expiresIn: Long = 27_720) =
        PendingJoin(GroupId(ByteArray(16) { 1 }), PublicKey(ByteArray(32) { 2 }), ByteArray(16) { 3 }, 4, 1, now + expiresIn, "", "", state, name, owner, count, now - 60)

    // ---- the form (design/09) -------------------------------------------------------------------------------------------------------

    @Test fun theFormShowsWhoInvitedYouTheGroupAndHowLongTheInviteWorks() {
        val m = JoinModels.form(join(), "", "", now)
        assertEquals("You were invited", m.heading)
        assertEquals("Goa Trip", m.title)
        assertEquals("GT", m.initials)
        assertEquals("Owner: Asha · 5 members", m.subtitle)
        assertEquals("This invite works once and expires in 7 hrs 42 min.", m.expiry)
        assertNull(m.unreachableNote)
        assertEquals("Members of Goa Trip will see this name and number. Nothing else is shared.", m.privacyNote)
        assertEquals("An admin must approve you before you see any expenses.", m.footnote)
        assertEquals("Request to join", m.button)
    }

    @Test fun aGroupOfOneIsOneMemberAndTheExpiredInviteSaysSo() {
        assertEquals("Owner: Asha · 1 member", JoinModels.form(join(count = 1), "", "", now).subtitle)
        assertEquals("This invite has expired. Ask an admin for a new one.", JoinModels.form(join(expiresIn = 0), "", "", now).expiry)
        assertFalse(JoinModels.form(join(expiresIn = 0), "Rohan", "98765 43210", now).canRequest, "nothing to send with an invite that is over")
    }

    @Test fun whenTheHostWasNotReachedTheFormSaysSoAndStillLetsThePersonAsk() {
        val m = JoinModels.form(join(name = "", owner = "", count = 0), "Rohan", "98765 43210", now)
        assertEquals("A group", m.title)
        assertEquals("AG", m.initials)
        assertEquals("", m.subtitle)
        assertEquals("The person who invited you isn't reachable right now, so we can't show the group yet. You can still ask to join.", m.unreachableNote)
        assertTrue(m.canRequest)
        assertEquals("Members of the group will see this name and number. Nothing else is shared.", m.privacyNote)
    }

    @Test fun theRequestNeedsAGoodNameAndNumberWithTheSameWordsAsFirstRun() {
        fun f(n: String, p: String) = JoinModels.form(join(), n, p, now)
        assertTrue(f("Rohan", "+91 98765 43210").canRequest)
        assertEquals("Enter your name.", f("  ", "98765 43210").nameError)
        assertEquals("Use 40 characters or fewer.", f("x".repeat(41), "98765 43210").nameError)
        assertEquals("Enter your phone number.", f("Rohan", "").phoneError)
        assertEquals("Use only digits, spaces and + - ( ) .", f("Rohan", "abc12345").phoneError)
        assertEquals("Use at least 5 digits.", f("Rohan", "12 -- 34").phoneError)
        assertFalse(f("", "").canRequest)
    }

    @Test fun theErrorsOnlyAppearOnceThereIsSomethingTypedOrTheButtonWasTapped() {
        val untouched = JoinModels.form(join(), "", "", now)
        assertNull(untouched.shownNameError); assertNull(untouched.shownPhoneError)
        val tapped = JoinModels.form(join(), "", "", now, showErrors = true)
        assertEquals("Enter your name.", tapped.shownNameError); assertEquals("Enter your phone number.", tapped.shownPhoneError)
        assertEquals("Use 5 to 20 characters.", JoinModels.form(join(), "R", "12", now).shownPhoneError)
    }

    // ---- waiting (design/09b) ----------------------------------------------------------------------------------------------------

    @Test fun waitingNamesTheOwnerAndSaysWhatToDoIfItDoesNotConnect() {
        val m = JoinModels.waiting(join(PendingState.WAITING), now)
        assertEquals("Waiting for approval", m.title)
        assertEquals("Asha or another admin needs to open OwnTally and approve your request. You can close this screen and come back later.", m.body)
        assertEquals("If it doesn't connect", m.helpTitle)
        assertEquals(listOf("Ask an admin to open the app, then tap Retry.", "If the invite expired, an admin can send you a new one."), m.helpLines)
        assertEquals("Retry", m.retry)
        assertEquals("Goa Trip", m.groupTitle)
    }

    @Test fun waitingWithoutKnowingTheOwnerSaysAnAdmin() {
        assertEquals("An admin needs to open OwnTally and approve your request. You can close this screen and come back later.", JoinModels.waiting(join(PendingState.WAITING, owner = ""), now).body)
    }

    @Test fun approvedAndDeclinedAreEachTheirOwnScreen() {
        val a = JoinModels.waiting(join(PendingState.APPROVED), now)
        assertEquals("You're in", a.title)
        assertEquals("Asha approved your request. Getting the group's history…", a.body)
        assertNull(a.retry)
        val d = JoinModels.waiting(join(PendingState.DECLINED), now)
        assertEquals("Your request was declined", d.title)
        assertEquals("Nobody is told why. You can ask an admin for a new invite.", d.body)
        assertEquals("Close", d.retry)
    }

    // ---- what to say when something goes wrong -----------------------------------------------------------------------------------

    @Test fun everyWayItCanGoWrongHasAPlainSentenceThatNamesTheHostWhenKnown() {
        val j = join()
        assertEquals("Asha's phone isn't reachable. Ask Asha to open OwnTally, then tap Retry.", JoinModels.problem(j, RequestResult.HostUnreachable))
        assertEquals("The phone of the person who invited you isn't reachable. Ask them to open OwnTally, then tap Retry.", JoinModels.problem(join(owner = ""), RequestResult.HostUnreachable))
        assertEquals("This invite was already used, has expired or was cancelled. Ask an admin for a new one.", JoinModels.problem(j, RequestResult.NoLongerValid))
        assertEquals("This invite could not be checked, so nothing was sent. Ask an admin for a new one.", JoinModels.problem(j, RequestResult.NotVerified))
        assertNull(JoinModels.problem(j, RequestResult.Waiting))
        assertEquals("This invite is no longer here. Open it again from the link.", JoinModels.problem(j, RequestResult.NoSuchInvitation))
        assertEquals("Check the name and number.", JoinModels.problem(j, RequestResult.InvalidDetails))
    }

    @Test fun theCheckResultsAreWorded() {
        val j = join(PendingState.WAITING)
        assertNull(JoinModels.checkNote(j, CheckResult.StillWaiting))
        assertEquals("Asha's phone isn't reachable. Ask Asha to open OwnTally, then tap Retry.", JoinModels.checkNote(j, CheckResult.HostUnreachable))
        assertEquals("Asha's phone has no record of your request. Ask an admin for a new invite.", JoinModels.checkNote(j, CheckResult.Unknown))
        assertNull(JoinModels.checkNote(j, CheckResult.Approved))
    }

    @Test fun whatTheOpenedInviteHadToSayAboutThePreview() {
        assertEquals("This invite was already used, has expired or was cancelled. Ask an admin for a new one.", JoinModels.previewProblem(PreviewState.NO_LONGER_VALID))
        assertEquals("The phone that answered is not the one this invite names. Don't use this invite; ask an admin for a new one.", JoinModels.previewProblem(PreviewState.NOT_VERIFIED))
        assertNull(JoinModels.previewProblem(PreviewState.FOUND)); assertNull(JoinModels.previewProblem(PreviewState.HOST_UNREACHABLE))
    }

    // ---- the Groups list ----------------------------------------------------------------------------------------------------------

    @Test fun anInvitationInProgressIsAGroupsCardWithItsState() {
        fun card(s: PendingState, name: String = "Goa Trip") = JoinModels.card(join(s, name = name), now)
        assertEquals("Goa Trip", card(PendingState.OPENED).title); assertEquals("Not sent yet", card(PendingState.OPENED).status)
        assertEquals("Waiting for approval", card(PendingState.WAITING).status)
        assertEquals("Getting the history…", card(PendingState.APPROVED).status)
        assertEquals("Declined", card(PendingState.DECLINED).status)
        assertEquals("Invitation", card(PendingState.OPENED, name = "").title)
        assertEquals("GT", card(PendingState.WAITING).initials)
    }

    @Test fun pasteResultsAreWorded() {
        assertEquals("That doesn't look like an OwnTally invite. Paste the whole link or code.", JoinModels.pasteProblem(owntally.data.session.OpenResult.NotAnInvite))
        assertEquals("This invite has expired. Ask an admin for a new one.", JoinModels.pasteProblem(owntally.data.session.OpenResult.Expired))
        assertEquals("You are already in this group.", JoinModels.pasteProblem(owntally.data.session.OpenResult.AlreadyInGroup(GroupId(ByteArray(16)))))
    }
}
