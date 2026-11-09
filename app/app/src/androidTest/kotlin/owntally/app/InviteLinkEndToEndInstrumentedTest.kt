package owntally.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import owntally.crypto.GroupKey
import owntally.crypto.Ticket
import owntally.data.db.OwnTallyDatabase
import owntally.data.secrets.KeystoreSecretBox
import owntally.data.session.Invites
import owntally.model.GroupId
import owntally.model.PublicKey

/** M9, T9.3: an invite link tapped outside the app (an https view of our link, as a browser or a chat app sends it) reaches the join form. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class InviteLinkEndToEndInstrumentedTest {
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

    private fun see(text: String, ms: Long = 30_000) = rule.waitUntilExactlyOneExists(hasText(text), ms)

    /** An invitation to a group on some other phone (made up: nobody is there to answer, so the form says the host is not reachable). */
    private fun ticket(group: GroupId = GroupId(ByteArray(16) { 3 })) =
        Ticket(ByteArray(16) { 1 }, PublicKey(ByteArray(32) { 2 }), group, 1, GroupKey(ByteArray(32) { 4 }), System.currentTimeMillis() / 1000 + 8 * 3600, "")

    private fun viewIntent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Starts the app by the link the way a browser would, in a fresh task. (Not through `ActivityScenario`: it finds the activity by the intent it
     * started it with, and the app deliberately replaces its intent once it has taken the link, so that the link does not stay in the activity.)
     */
    private fun tap(url: String) = context.startActivity(viewIntent(url).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK))

    private fun openApp() = context.startActivity(Intent(Intent.ACTION_MAIN).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))

    private fun setUp() {
        val c = container()
        c.syncOnStart = false; c.internetEnabled = false
        c.identity.let { it.completeFirstRun(it.newPhrase(), "Rohan", "98765 43210") }
    }

    @Test
    fun tappingTheLinkOnASetUpPhoneOpensTheJoinFormAndTheLinkIsNotKept() {
        wipe()
        try {
            setUp()
            tap(Invites.LINK_START + ticket().encode())
            see("You were invited")
            assertNull(container().pendingInvites.waiting(), "used, so no longer waiting")
            assertEquals(1, container().joinGroup.pending().size, "the invitation is kept by the join flow itself")
        } finally { wipe() }
    }

    @Test
    fun aLinkTappedWhileTheAppIsOpenIsTakenToo() {
        wipe()
        try {
            setUp()
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            see("Have an invite? Join a group")
            scenario.onActivity { it.startActivity(viewIntent(Invites.LINK_START + ticket().encode())) }
            see("You were invited")
        } finally { wipe() }
    }

    @Test
    fun aLinkThatArrivesBeforeSetupWaitsAndOpensAfterTheAppIsSetUpAndStartedAgain() {
        wipe()
        try {
            val c = container()
            c.syncOnStart = false; c.internetEnabled = false
            val link = Invites.LINK_START + ticket().encode()
            tap(link)
            // First-run setup is showing, not the join form.
            see("Split expenses.\nKeep your data.")
            rule.waitUntil(20_000) { c.pendingInvites.waiting() == link }
            // The person finishes setup, the app is closed and opened again: the waiting link is still there and now opens.
            c.identity.let { it.completeFirstRun(it.newPhrase(), "Rohan", "98765 43210") }
            (context as OwnTallyApplication).resetContainerForTests()
            assertEquals(link, container().pendingInvites.waiting(), "kept on the phone across the restart")
            container().syncOnStart = false; container().internetEnabled = false
            openApp()
            see("You were invited")
            assertNull(container().pendingInvites.waiting())
        } finally { wipe() }
    }

    @Test
    fun aLinkWithNoCodeOrFromAnotherHostIsRefusedInWordsAndKeepsNothing() {
        wipe()
        try {
            setUp()
            tap("https://joinowntally.ownspace.in/j")
            see("That link isn't an OwnTally invite. Ask your friend to send it again.")
            rule.onNodeWithText("OK").performClick()
            // An explicit intent from another app naming some other host with a perfectly good code: never accepted.
            val stranger = viewIntent("https://evil.example/j#" + ticket().encode())
            context.startActivity(stranger) // reaches the open app (onNewIntent)
            see("That link isn't an OwnTally invite. Ask your friend to send it again.")
            assertNull(container().pendingInvites.waiting())
            assertEquals(0, container().joinGroup.pending().size)
        } finally { wipe() }
    }

    @Test
    fun aLinkToAGroupThePersonIsAlreadyInSaysSoAndOpensTheGroup() {
        wipe()
        try {
            setUp()
            val group = container().sessions.createGroup("Goa Trip", "INR", 0)
            tap(Invites.LINK_START + ticket(group).encode())
            see("You are already in this group.")
            rule.onNodeWithText("OK").performClick()
            see("You are the owner")
            assertNull(container().pendingInvites.waiting())
        } finally { wipe() }
    }

    @Test
    fun anExpiredLinkSaysSoAndIsNotKept() {
        wipe()
        try {
            setUp()
            val old = Ticket(ByteArray(16) { 1 }, PublicKey(ByteArray(32) { 2 }), GroupId(ByteArray(16) { 3 }), 1, GroupKey(ByteArray(32) { 4 }), System.currentTimeMillis() / 1000 - 86_400, "")
            tap(Invites.LINK_START + old.encode())
            see("This invite has expired. Ask an admin for a new one.")
            assertNull(container().pendingInvites.waiting())
            assertNotNull(container())
        } finally { wipe() }
    }
}
