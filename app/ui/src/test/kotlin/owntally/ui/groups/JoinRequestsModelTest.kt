package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.crypto.KeyDerivation
import owntally.data.secrets.SecretLoad
import owntally.data.store.JoinRequest
import owntally.data.store.JoinState
import owntally.model.MAX_MEMBERS
import owntally.model.PublicKey

class JoinRequestsModelTest {
    private val w = TestWorld()
    private val s = w.sessions.session(w.newGroup("Goa Trip"))
    private val me get() = s.view.value.me
    private val rohan = w.addFriend(s, "Rohan", 1).also { s.makeAdmin(it) }
    private val meera = w.addFriend(s, "Meera", 2)
    private val now = 1_800_000_000L

    private fun person(n: Long) = KeyDerivation.groupIdentity((w.secrets.loadMasterSeed() as SecretLoad.Found).value, 5_000_000 + n)
    private fun request(n: Long, name: String, phone: String, ago: Long, state: JoinState = JoinState.WAITING): JoinRequest {
        val p = person(n)
        return JoinRequest(s.view.value.state.groupId!!, p.publicKey, p.encryptionKey, name, phone, now - ago, state)
    }

    private fun model(viewer: PublicKey = me, vararg r: JoinRequest) = JoinRequestsModels.build(s.view.value.copy(me = viewer), r.toList(), now)

    // ---- the list ---------------------------------------------------------------------------------------------------------

    @Test fun waitingRequestsAreListedOldestFirstWithPhoneAndHowLongAgo() {
        val m = model(me, request(2, "Naina", "+91 90000 11122", 3_600), request(1, "Aarav", "+91 91234 56780", 720))!!
        assertEquals("Waiting for you · 2", m.heading)
        assertEquals(listOf("Naina", "Aarav"), m.rows.map { it.name }, "oldest first")
        assertEquals(listOf("1 hr ago", "12 min ago"), m.rows.map { it.ago })
        assertEquals("+91 90000 11122", m.rows.first().phone)
        assertEquals("N", m.rows.first().initial)
        assertEquals("Goa Trip", m.groupName)
    }

    @Test fun answeredRequestsAreNotListed() {
        val m = model(me, request(1, "Aarav", "98765 11111", 60, JoinState.APPROVED), request(2, "Naina", "98765 22222", 60, JoinState.DECLINED))!!
        assertEquals(emptyList(), m.rows)
        assertEquals("No one is waiting to join.", m.empty)
        assertEquals("Waiting for you · 0", m.heading)
    }

    @Test fun ownerAndAdminSeeItAndAMemberDoesNot() {
        assertTrue(model(me) != null)
        assertTrue(model(rohan) != null)
        assertNull(model(meera))
    }

    @Test fun theFooterNoteSaysWhatApprovingMeans() {
        assertEquals("Approving lets the person read every expense in this group. Decline anyone you don't recognise.", model()!!.note)
    }

    @Test fun howLongAgoIsPlainWords() {
        fun ago(sec: Long) = RelativeTime.ago(now, now - sec)
        assertEquals("just now", ago(20))
        assertEquals("1 min ago", ago(60))
        assertEquals("59 min ago", ago(59 * 60 + 59))
        assertEquals("1 hr ago", ago(3_600))
        assertEquals("5 hrs ago", ago(5 * 3_600 + 10))
        assertEquals("1 day ago", ago(86_400))
        assertEquals("3 days ago", ago(3 * 86_400))
        assertEquals("just now", RelativeTime.ago(now, now + 500), "a clock a little ahead is not the future")
    }

    // ---- the name, when approving ----------------------------------------------------------------------------------------

    @Test fun theNameFieldStartsWithWhatWasRequestedAndAcceptsAFreeName() {
        val c = ApproveCheck.of(s.view.value, "Aarav")
        assertNull(c.problem)
        assertTrue(c.canApprove)
        assertEquals("Aarav", c.name)
    }

    @Test fun aNameAlreadyInTheGroupIsBlockedLiveWithAHintHowToFixIt() {
        for (typed in listOf("Rohan", "rohan", "  ROHAN ", "Rohan\u00A0", "Ro\u200Bhan")) {
            val c = ApproveCheck.of(s.view.value, typed)
            assertFalse(c.canApprove, typed)
            assertEquals("Someone in this group already has this name. Try adding an initial, like Rohan V.", c.problem, typed)
        }
        assertTrue(ApproveCheck.of(s.view.value, "Rohan V").canApprove)
    }

    @Test fun aRemovedMembersNameStaysTaken() {
        s.removeMember(meera)
        assertFalse(ApproveCheck.of(s.view.value, "Meera").canApprove)
    }

    @Test fun aBlankOrTooLongNameIsBlocked() {
        assertEquals("Type a name.", ApproveCheck.of(s.view.value, "   ").problem)
        assertEquals("Use at most 40 letters.", ApproveCheck.of(s.view.value, "x".repeat(41)).problem)
        assertTrue(ApproveCheck.of(s.view.value, "x".repeat(40)).canApprove)
    }

    @Test fun theNameIsTrimmedBeforeItIsUsed() {
        assertEquals("Aarav", ApproveCheck.of(s.view.value, "  Aarav  ").name)
    }

    @Test fun aFullGroupBlocksApprovalWithAClearMessage() {
        for (n in 1L..(MAX_MEMBERS.toLong() - 3)) w.addFriend(s, "P$n", 6_000_000 + n)
        assertEquals(MAX_MEMBERS, s.view.value.state.currentMembers.size)
        val c = ApproveCheck.of(s.view.value, "Late")
        assertFalse(c.canApprove)
        assertEquals("This group already has $MAX_MEMBERS people, the most it can have. Remove someone first.", c.problem)
        assertTrue(model(me)!!.full)
    }

    // ---- open invites (T6.6) -----------------------------------------------------------------------------------------------------

    @Test fun openInvitesAreListedSoonestToExpireFirstWithTheTimeLeftAndOverOnesAreLeftOut() {
        val m = JoinRequestsModels.build(s.view.value, emptyList(), now, listOf(OpenInviteInfo("b", now + 5 * 3_600 + 600), OpenInviteInfo("a", now + 1_800), OpenInviteInfo("gone", now - 1), OpenInviteInfo("now", now)))!!
        assertEquals("OPEN INVITES", m.openInvitesHeading)
        assertEquals(listOf("a", "b"), m.openInvites.map { it.id })
        assertEquals(listOf("Unused · expires in 30 min", "Unused · expires in 5 hrs 10 min"), m.openInvites.map { it.detail })
        assertEquals("Invite sent by you", m.openInvites.first().title)
        assertNull(m.noOpenInvites)
        assertEquals("New invite", m.newInviteLabel)
    }

    @Test fun withNoOpenInviteTheScreenSaysSoAndTheEmptyRequestsLineNoLongerPromisesALaterUpdate() {
        val m = JoinRequestsModels.build(s.view.value, emptyList(), now)!!
        assertEquals(emptyList(), m.openInvites)
        assertEquals("No open invites.", m.noOpenInvites)
        assertEquals("No one is waiting to join.", m.empty)
        assertEquals("No open invites.", JoinRequestsModels.build(s.view.value, emptyList(), now, listOf(OpenInviteInfo("old", now - 10)))!!.noOpenInvites)
    }

    @Test fun aNameWithAHiddenOrDirectionCharacterIsBlockedLiveWithItsOwnMessage() {
        val c = ApproveCheck.of(s.view.value, "Aarav" + 0x202E.toChar())
        assertFalse(c.canApprove)
        assertEquals(owntally.model.NameText.INVISIBLE, c.problem)
    }
}
