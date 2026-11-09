package owntally.app

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
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
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import owntally.crypto.KeyDerivation
import owntally.crypto.MasterSeed
import owntally.data.db.OwnTallyDatabase
import owntally.data.secrets.KeystoreSecretBox
import owntally.model.EventBody
import owntally.model.Money
import owntally.model.Split

/** M8, T8.4: Settle up through the real app: the owner marks a payment received from a friend, with a different amount, and it persists. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettleUpEndToEndInstrumentedTest {
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

    @Test
    fun theOwnerMarksAPaymentReceivedWithAnotherAmountAndItIsKeptAfterARestart() {
        wipe()
        try {
            val c = container()
            c.syncOnStart = false; c.internetEnabled = false
            c.identity.let { it.completeFirstRun(it.newPhrase(), "Asha", "98765 43210") }
            val group = c.sessions.createGroup("Goa Trip", "INR", 0)
            val session = c.sessions.session(group)
            val ben = KeyDerivation.groupIdentity(MasterSeed(ByteArray(64) { 6 }), 1)
            session.append(EventBody.MemberJoined(ben.publicKey, ben.encryptionKey, "Ben", "98765 00001"))
            session.addExpense("Dinner", Money(200_000), session.view.value.me, 20_000, Split.Equal(setOf(session.view.value.me, ben.publicKey)))

            ActivityScenario.launch(MainActivity::class.java)
            see("Goa Trip"); rule.onNodeWithText("Goa Trip").performClick()
            see("Settle up the group"); rule.onNodeWithText("Settle up the group").performClick()
            see("Ben → You")
            see("₹1,000")
            rule.onNodeWithText("Mark received").performClick()
            see("Received from Ben")
            rule.onNode(hasSetTextAction()).performTextReplacement("400")
            rule.onNodeWithText("Save").performClick()

            // 400 counted at once; 600 of the 1,000 is still to pay; the 400 is confirmed.
            rule.waitUntilExactlyOneExists(hasText("₹600"), 20_000)
            see("1 payment already confirmed.")

            // A new container (as after the app is closed and opened): the payment is still there.
            (context as OwnTallyApplication).resetContainerForTests()
            val again = container().sessions.session(group).view.value
            assertEquals(1, again.overview.confirmed.size)
            assertEquals(Money(40_000), again.overview.confirmed.single().amount)
            assertTrue(again.overview.toMake.any { it.amount == Money(60_000) })
        } finally {
            wipe()
        }
    }

    @Test
    fun aFriendMarksAPaymentTheOwnerSaysNotReceivedThenConfirmsItLater() {
        wipe()
        try {
            val c = container()
            c.syncOnStart = false; c.internetEnabled = false
            c.identity.let { it.completeFirstRun(it.newPhrase(), "Asha", "98765 43210") }
            c.sessions.createGroup("Goa Trip", "INR", 0)

            ActivityScenario.launch(MainActivity::class.java)
            see("Goa Trip"); rule.onNodeWithText("Goa Trip").performClick()
            see("Add pretend friend: Ben (test) (debug)"); rule.onNodeWithText("Add pretend friend: Ben (test) (debug)").performClick()
            see("As Ben (test): mark \u20b9500 as paid to you (debug)")
            rule.onNodeWithText("As Ben (test): mark \u20b9500 as paid to you (debug)").performClick()
            see("Settle up the group"); rule.onNodeWithText("Settle up the group").performClick()
            see("Ben (test) marked \u20b9500 as paid. Waiting for you to confirm.")

            rule.onNodeWithText("Not received").performClick()
            see("Payment not received")
            rule.onNodeWithText("The amount is different").performClick()
            rule.onNodeWithText("Tell Ben (test)").performClick()
            see("Ben (test) has been told")
            rule.onNodeWithText("Back to settle up").performClick()

            // Back on Settle up the payment is listed as not accepted, with the answer, and can still be confirmed.
            see("You told Ben (test) the amount is different.")
            rule.onNodeWithText("Confirm received").performClick()
            see("1 payment already confirmed.")
            assertTrue(container().sessions.session(container().groups.listGroups().single().groupId).view.value.overview.disputed.isEmpty())
        } finally {
            wipe()
        }
    }
}
