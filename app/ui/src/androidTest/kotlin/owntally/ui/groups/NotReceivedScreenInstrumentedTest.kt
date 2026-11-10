package owntally.ui.groups

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import owntally.model.DisputeReason
import owntally.ui.theme.OwnTallyColors
import owntally.ui.theme.OwnTallyTheme

/** "Payment not received" on a phone (design/15 and 15b). */
@RunWith(AndroidJUnit4::class)
class NotReceivedScreenInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        owntally.ui.A11yChecks.assertAccessible(rule, name)
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val form = NotReceivedModel.Form(
        "Meera → You", "₹1,350", "Marked as paid 2 hrs ago", "Meera",
        listOf(
            NotReceivedReason(DisputeReason.NOTHING_RECEIVED, "I haven't received anything"),
            NotReceivedReason(DisputeReason.DIFFERENT_AMOUNT, "The amount is different"),
            NotReceivedReason(DisputeReason.NOT_CHECKED, "I haven't checked my account yet"),
        ),
        "Meera will see your answer and the payment goes back to \"Payments to make\". Nothing is deleted, and either of you can change it later.", "Tell Meera",
    )
    private val sent = NotReceivedModel.Sent(
        "Meera → You", "₹1,350", "Marked as paid 2 hrs ago", "Meera has been told",
        "Meera will see this the next time Meera's phone syncs with yours. The payment shows as \"Not received\" until you both agree.",
        "Tip: send Meera a reminder message so Meera knows to check, or mark it received once the money arrives.",
        "If the money arrives later, tap Confirm received on Settle up.", "Back to settle up",
    )

    private fun show(model: NotReceivedModel, onBack: () -> Unit = {}, onTell: (DisputeReason) -> Unit = {}, message: String? = null) {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        rule.setContent { OwnTallyTheme { Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) { NotReceivedScreen(model, onBack, onTell, message) } } }
    }

    @Test
    fun theFormStartsOnTheFirstReasonAndTellSendsTheChosenOne() {
        val told = mutableListOf<DisputeReason>()
        show(form, onTell = { told += it })
        rule.onNodeWithText("Tell Meera").assertIsDisplayed()
        rule.onNodeWithText("I haven't received anything").assertIsSelected()
        save("not-received")
        rule.onNodeWithText("The amount is different").performClick()
        rule.onNodeWithText("The amount is different").assertIsSelected()
        rule.onNodeWithText("Tell Meera").performClick()
        assertEquals(listOf(DisputeReason.DIFFERENT_AMOUNT), told)
    }

    @Test
    fun cancelGoesBackAndWritesNothing() {
        var back = 0; val told = mutableListOf<DisputeReason>()
        show(form, onBack = { back++ }, onTell = { told += it })
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, back); assertEquals(emptyList(), told)
    }

    @Test
    fun afterTheAnswerTheScreenSaysWhoHasBeenToldAndHasNoUndo() {
        var back = 0
        show(sent, onBack = { back++ })
        rule.onNodeWithText("Meera has been told").assertIsDisplayed()
        rule.onNodeWithText("Undo").assertDoesNotExist()
        save("not-received-sent")
        rule.onNodeWithText("Back to settle up").performClick()
        assertEquals(1, back)
    }

    @Test
    fun aPaymentThatIsNotThereSaysSo() {
        show(NotReceivedModel.Unavailable("This payment was already answered."))
        rule.onNodeWithText("This payment was already answered.").assertIsDisplayed()
    }
}
