package owntally.app

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
            see("No one is waiting to join. Invites arrive in a later update.")
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
}
