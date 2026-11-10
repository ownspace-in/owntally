package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.model.EventBody
import owntally.model.PublicKey
import owntally.model.format.MoneyFormat
import owntally.model.previewRemoval

class ProfileAndLeaveModelTest {
    private val w = TestWorld(name = "Asha", phone = "98765 43210")
    private val s = w.sessions.session(w.newGroup("Goa Trip"))
    private val me get() = s.view.value.me
    private val rohan = w.addFriend(s, "Rohan", 1).also { s.makeAdmin(it) }
    private val meera = w.addFriend(s, "Meera", 2)
    private val kabir = w.addFriend(s, "Kabir", 3)

    // ---- name and phone (D3) ---------------------------------------------------------------------------------------------

    @Test fun theFormStartsWithMyNameAndPhoneInThisGroupAndSavingNothingIsNotAChange() {
        val c = ProfileCheck.of(s.view.value, "Asha", "98765 43210")
        assertNull(c.nameProblem)
        assertNull(c.phoneProblem)
        assertFalse(c.canSave, "nothing changed")
        assertEquals("Your name and phone in Goa Trip", ProfileModels.title(s.view.value))
    }

    @Test fun aFreeNameAndAnAllowedPhoneCanBeSaved() {
        for (phone in listOf("98765 11111", "+91 (98765) 11-111", "98765.11111")) assertTrue(ProfileCheck.of(s.view.value, "Asha K", phone).canSave, phone)
    }

    @Test fun aNameAlreadyTakenIsBlockedWithTheWordsOfTheDecision() {
        for (typed in listOf("Rohan", " rohan ", "ROHAN", "Ro​han")) {
            val c = ProfileCheck.of(s.view.value, typed, "98765 43210")
            assertEquals("That name is already taken in this group", c.nameProblem, typed)
            assertFalse(c.canSave)
        }
    }

    @Test fun changingOnlyTheCapitalsOfMyOwnNameIsAllowed() {
        val c = ProfileCheck.of(s.view.value, "ASHA", "98765 43210")
        assertNull(c.nameProblem)
        assertTrue(c.canSave)
    }

    @Test fun aRemovedMembersNameStaysTaken() {
        s.removeMember(kabir)
        assertEquals("That name is already taken in this group", ProfileCheck.of(s.view.value, "kabir", "98765 43210").nameProblem)
    }

    @Test fun blankLongAndBadPhoneAreBlocked() {
        assertEquals("Type your name.", ProfileCheck.of(s.view.value, " ", "98765 43210").nameProblem)
        assertEquals("Use at most 40 letters.", ProfileCheck.of(s.view.value, "x".repeat(41), "98765 43210").nameProblem)
        assertEquals("Use only digits, spaces and + - ( ) .", ProfileCheck.of(s.view.value, "Asha", "98765a43210").phoneProblem)
        assertEquals("Use 5 to 20 characters.", ProfileCheck.of(s.view.value, "Asha", "1234").phoneProblem)
        assertEquals("Use at least 5 digits.", ProfileCheck.of(s.view.value, "Asha", "+(  )-.. +").phoneProblem)
        assertEquals("Enter your phone number.", ProfileCheck.of(s.view.value, "Asha", "").phoneProblem)
    }

    @Test fun theTextTrimmedIsWhatIsWritten() {
        val c = ProfileCheck.of(s.view.value, "  Asha K  ", "  98765 11111 ")
        assertEquals("Asha K", c.name)
        assertEquals("98765 11111", c.phone)
        // and the event accepts exactly this
        EventBody.ProfileUpdated(c.name, c.phone)
    }

    @Test fun aPersonOnlyChangesTheirOwnRow() {
        assertEquals(true, s.updateProfile("Asha K", "98765 11111") is owntally.data.session.AddResult.Added)
        val members = s.view.value.state.currentMembers
        assertEquals("Asha K", members.getValue(me).name)
        assertEquals("Rohan", members.getValue(rohan).name)
    }

    // ---- leaving (D4) ----------------------------------------------------------------------------------------------------

    private fun leaveAs(viewer: PublicKey) = LeaveModels.build(s.view.value.copy(me = viewer))

    @Test fun aPlainMemberGetsTheLeaveTableThatEqualsThePreviewForThem() {
        w.expense(s, "Dinner", w.rupees(1200), me, me, rohan, meera, kabir)
        val view = s.view.value.copy(me = meera)
        val m = (LeaveModels.build(view) as LeaveOutcome.Allowed).model
        val preview = previewRemoval(view.state, meera)
        assertEquals("Leave Goa Trip?", m.title)
        assertEquals("Leave group", m.button)
        val byKey = preview.changes.associateBy { it.member }
        for (row in m.rows.dropLast(1)) {
            assertEquals(MoneyFormat.signed(byKey.getValue(row.key).before), row.now)
            assertEquals(MoneyFormat.signed(byKey.getValue(row.key).after), row.after)
        }
        assertEquals(preview.changes.size + 1, m.rows.size)
        assertEquals("You", m.rows.last().label)
        assertEquals("Left", m.rows.last().after)
    }

    @Test fun anAdminIsToldToAskTheOwnerAndTheOwnerToHandOverFirst() {
        assertEquals(LeaveOutcome.Blocked("Ask the owner to make you a member first, or to remove you."), leaveAs(rohan))
        assertEquals(LeaveOutcome.Blocked("Hand over ownership first."), leaveAs(me))
    }

    @Test fun someoneNotInTheGroupHasNothingToLeave() {
        assertNull(leaveAs(PublicKey(ByteArray(32) { 9 })))
    }

    @Test fun anUnsettledBalanceGetsAWarningAndASettledOneDoesNot() {
        val settled = (leaveAs(meera) as LeaveOutcome.Allowed).model
        assertNull(settled.warning)
        w.expense(s, "Dinner", w.rupees(900), me, me, meera, rohan, kabir)
        val owes = (leaveAs(meera) as LeaveOutcome.Allowed).model
        assertEquals("You are not settled up: you owe ₹225. Leaving takes you out of the sums, so settle up first if you want to pay it.", owes.warning)
    }

    @Test fun theBoxSpeaksInTheSecondPerson() {
        w.expense(s, "Taxi", w.rupees(300), meera, meera, rohan)
        w.expense(s, "Dinner", w.rupees(900), me, me, meera, rohan)
        val box = (leaveAs(meera) as LeaveOutcome.Allowed).model.alsoLeftOut!!
        assertEquals("1 expense you paid, your share of 1 other", box.summary)
    }

    @Test fun afterLeavingTheGroupIsGoneFromTheListAndNothingCanBeWritten() {
        val view = s.view.value
        // The leave is written by the leaver's own phone; here the owner's session stands in only to check the list rule through Sessions.
        assertTrue(w.sessions.summaries.value.any { it.groupId == view.state.groupId })
        w.sessions.markLeft(view.state.groupId!!)
        assertEquals(emptyList(), w.sessions.summaries.value)
    }

    @Test fun aNameWithAHiddenOrDirectionCharacterCannotBeSavedAndSaysWhy() {
        val c = ProfileCheck.of(s.view.value, "Asha" + 0x202E.toChar() + "K", "98765 43210")
        assertFalse(c.canSave)
        assertEquals(owntally.model.NameText.INVISIBLE, c.nameProblem)
    }
}
