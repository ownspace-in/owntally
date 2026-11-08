package owntally.app

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import owntally.data.db.OwnTallyDatabase
import owntally.data.secrets.KeystoreSecretBox

/**
 * Every membership screen of M4, used through the real app with the debug-build pretend people (the only way to try them before
 * joining by link exists): Members, Join requests and Approve (with a name clash), Remove member, Make owner (waiting, then accepted
 * as the pretend person), the profile screen and Leave. The JVM tests check the rules; this checks the screens are wired.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MembershipEndToEndInstrumentedTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun container() = (context as OwnTallyApplication).container

    private fun wipe() {
        context.deleteDatabase(OwnTallyDatabase.FILE_NAME)
        File(context.noBackupFilesDir, "secrets").deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KeystoreSecretBox.DEFAULT_ALIAS)
        (context as OwnTallyApplication).resetContainerForTests()
    }

    private fun see(text: String, ms: Long = 20_000) = rule.waitUntilExactlyOneExists(hasText(text), ms)
    private fun tap(text: String) = rule.onNodeWithText(text).performClick()
    private fun tapDescription(d: String) = rule.onNode(hasContentDescription(d)).performClick()

    @Test
    fun everyMembershipScreenWorksThroughThePretendPeople() {
        wipe()
        try {
            container().identity.let { it.completeFirstRun(it.newPhrase(), "Asha", "98765 43210") }
            container().sessions.createGroup("Goa Trip", "INR", 0)
            ActivityScenario.launch(MainActivity::class.java)
            see("Goa Trip")
            tap("Goa Trip")
            see("You are the owner")

            // Two pretend friends, then the Members screen.
            tap("Add pretend friend: Ben (test) (debug)")
            see("Add pretend friend: Meera (test) (debug)")
            tap("Add pretend friend: Meera (test) (debug)")
            see("Add pretend friend: Kabir (test) (debug)")
            tapDescription("Members")
            see("Goa Trip · 3 people")
            see("Join requests")
            tapDescription("Back")

            // A pretend join request: approve it, first with a name that clashes.
            tap("Add pretend join request (debug)")
            see("Add pretend join request (debug)")
            tapDescription("Members")
            see("Join requests")
            tap("Join requests")
            see("Aarav (test)")
            tap("Approve")
            see("Approve Aarav (test)?")
            rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("ben (TEST)")
            see("Someone in this group already has this name. Try adding an initial, like Rohan V.")
            rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("Aarav V")
            rule.onNode(hasText("Approve") and hasAnyAncestor(isDialog())).performClick()
            see("No one is waiting to join.")
            tapDescription("Back")
            see("Goa Trip · 4 people")

            // Remove Meera: the table, then the result in the "no longer in the group" list.
            tapDescription("Actions for Meera (test)")
            see("Remove from group")
            tap("Remove from group")
            see("Remove Meera (test)?")
            see("WHAT CHANGES FOR EVERYONE")
            tap("Remove Meera (test)")
            see("Goa Trip · 3 people")
            see("NO LONGER IN THE GROUP")

            // Make Ben the owner: waiting screen, then Ben accepts (as the pretend person) and this phone's person is an admin.
            tapDescription("Actions for Ben (test)")
            see("Make owner")
            tap("Make owner")
            see("Make Ben (test) the owner?")
            tap("Send request")
            see("Waiting for Ben (test) to accept")
            tapDescription("Close")
            see("Make owner")
            tapDescription("Close")
            tapDescription("Back")
            see("You are the owner")
            tap("As Ben (test): accept ownership (debug)")
            see("You are an admin")

            // The profile screen opens from the role line; an admin cannot leave and is told what to do.
            tap("You are an admin")
            see("Your name and phone in Goa Trip")
            tap("Leave group")
            see("You can't leave yet")
            see("Ask the owner to make you a member first, or to remove you.")
            assertEquals(1, 1)
        } finally {
            wipe()
        }
    }

    @Test
    fun theOwnerOpensInviteFriendsFromMembersGetsAnInviteThatSurvivesARestartAndCanBeRevokedAndRenewed() {
        wipe()
        try {
            container().identity.let { it.completeFirstRun(it.newPhrase(), "Asha", "98765 43210") }
            val group = container().sessions.createGroup("Goa Trip", "INR", 0)
            ActivityScenario.launch(MainActivity::class.java)
            see("Goa Trip")
            tap("Goa Trip")
            see("You are the owner")
            tapDescription("Members")
            see("Goa Trip · 1 person")
            tapDescription("Invite")
            see("Invite friends")
            rule.waitUntil(20_000) { rule.onAllNodes(hasText("Works once. Expires in", substring = true)).fetchSemanticsNodes().size == 1 }
            see("Share invite link")
            see("For friends in the same room")
            rule.onNode(hasContentDescription("QR code for the invite")).assertExists()
            val first = container().invites.current(group)!!.link
            assertEquals(true, first.startsWith("https://joinowntally.ownspace.in/j#"))

            // The invite is in the database: a new container (as after the app is closed) finds the same one.
            (context as OwnTallyApplication).resetContainerForTests()
            assertEquals(first, container().invites.current(group)!!.link)

            // The Join requests screen lists it under OPEN INVITES.
            tapDescription("Back")
            see("Goa Trip · 1 person")
            tap("Join requests")
            see("OPEN INVITES")
            see("Invite sent by you")
            tapDescription("Back")
            tapDescription("Invite")
            see("Invite friends")
            tap("Revoke invite")
            see("Invite revoked. Nobody can use it any more.")
            assertEquals(null, container().invites.current(group))
            tap("New invite")
            rule.waitUntil(20_000) { rule.onAllNodes(hasText("Works once. Expires in", substring = true)).fetchSemanticsNodes().size == 1 }
            assertEquals(true, container().invites.current(group)!!.link != first)
            tapDescription("Back")
            see("Goa Trip · 1 person")
        } finally {
            wipe()
        }
    }

    @Test
    fun aPersonPastesAnInviteSeesTheFormAsksToJoinAndTheInvitationSurvivesARestartUntilDiscarded() {
        wipe()
        try {
            container().identity.let { it.completeFirstRun(it.newPhrase(), "Rohan", "98765 43210") }
            // An invite for a group on some other phone (made up here: there is no other phone to reach until M6's real connections).
            val ticket = owntally.crypto.Ticket(
                ByteArray(16) { 1 }, owntally.model.PublicKey(ByteArray(32) { 2 }), owntally.model.GroupId(ByteArray(16) { 3 }), 1,
                owntally.crypto.GroupKey(ByteArray(32) { 4 }), System.currentTimeMillis() / 1000 + 8 * 3600, "",
            )
            val link = "https://joinowntally.ownspace.in/j#" + ticket.encode()
            ActivityScenario.launch(MainActivity::class.java)
            see("Have an invite? Join a group")
            tap("Have an invite? Join a group")
            see("Paste the invite link or code a friend sent you.")
            rule.onNode(hasContentDescription("Invite link or code")).performTextInput("not an invite")
            tap("Continue")
            see("That doesn't look like an OwnTally invite. Paste the whole link or code.")
            rule.onNode(hasContentDescription("Invite link or code")).performTextReplacement(link)
            tap("Continue")

            // Nobody can be reached: the form says so, with the name and number from first run already filled in.
            see("You were invited")
            see("A group")
            see("The person who invited you isn't reachable right now, so we can't show the group yet. You can still ask to join.")
            tap("Request to join")
            see("The phone of the person who invited you isn't reachable. Ask them to open OwnTally, then tap Retry.")
            tapDescription("Back")

            // The invitation is on the Groups list and in the database: a new container finds it.
            see("Invitation")
            see("Not sent yet")
            (context as OwnTallyApplication).resetContainerForTests()
            assertEquals(1, container().joinGroup.pending().size)
            tap("Invitation")
            see("You were invited")
            tap("Not for me")
            see("Have an invite? Join a group")
            rule.waitUntil(20_000) { rule.onAllNodes(hasText("Not sent yet")).fetchSemanticsNodes().isEmpty() }
            assertEquals(0, container().joinGroup.pending().size)
        } finally {
            wipe()
        }
    }

    @Test
    fun theSyncTabAndScreenSayEveryoneIsNotReachableYetBecauseNoWayToReachThemIsBuiltInYet() {
        wipe()
        try {
            container().identity.let { it.completeFirstRun(it.newPhrase(), "Asha", "98765 43210") }
            container().sessions.createGroup("Goa Trip", "INR", 0)
            ActivityScenario.launch(MainActivity::class.java)
            see("Goa Trip")
            tap("Goa Trip")
            see("You are the owner")
            tap("Add pretend friend: Ben (test) (debug)")
            see("Add pretend friend: Meera (test) (debug)")
            tap("Add pretend friend: Meera (test) (debug)")
            see("Add pretend friend: Kabir (test) (debug)")
            tapDescription("Back")
            // The Sync tab lists the group; opening it shows the screen of design/05 without Nearby and the relay card.
            tapDescription("Sync")
            see("Choose a group to see who it has reached.")
            see("Not syncing yet")
            tap("Goa Trip")
            see("Not syncing yet. Your entries are saved on this phone.")
            see("MEMBERS")
            assertEquals(2, rule.onAllNodesWithText("Not reachable yet").fetchSemanticsNodes().size, "one line per friend")
            tapDescription("Sync now")
            rule.waitForIdle()
            see("Not syncing yet. Your entries are saved on this phone.")
            tapDescription("Back")
            see("Choose a group to see who it has reached.")
        } finally {
            wipe()
        }
    }
}

