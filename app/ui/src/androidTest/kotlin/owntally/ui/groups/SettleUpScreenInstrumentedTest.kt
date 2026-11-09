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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import owntally.model.EventId
import owntally.model.Money
import owntally.model.PublicKey
import owntally.ui.theme.OwnTallyColors
import owntally.ui.theme.OwnTallyTheme

/** Settle up on a phone (design/04): every state of a payment, and what each button hands to the route. */
@RunWith(AndroidJUnit4::class)
class SettleUpScreenInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun key(n: Int) = PublicKey(ByteArray(32).also { it[0] = n.toByte() })
    private val me = key(1); private val rohan = key(2); private val meera = key(3)
    private fun id(n: Long) = EventId(key(9), n)

    private fun save(name: String) {
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun money(n: Long) = Money(n * 100)
    private fun fmt(n: Long) = owntally.model.format.MoneyFormat.format(money(n))

    private val owedToMe = PaymentRow(
        rohan, me, money(1_850), fmt(1_850), "Rohan → You", "+91 98765 43210", PaymentRole.TO_ME, "Rohan",
        listOf(PaymentAction.SEND_REMINDER, PaymentAction.MARK_RECEIVED),
    )
    private val iOwe = PaymentRow(
        me, meera, money(1_350), fmt(1_350), "You → Meera", "+91 99887 76655", PaymentRole.FROM_ME, "Meera",
        listOf(PaymentAction.COPY_NUMBER, PaymentAction.CALL, PaymentAction.I_PAID),
    )
    private val waitingForMe = WaitingRow(
        id(1), meera, me, money(1_350), fmt(1_350), "Meera → You", PaymentRole.TO_ME,
        "Meera marked ${fmt(1_350)} as paid. Waiting for you to confirm.", true, "Meera",
    )
    private val disputedMine = DisputedRow(
        id(2), rohan, me, money(500), fmt(500), "Rohan → You", PaymentRole.TO_ME, "You told Rohan nothing was received.", true,
    )

    private fun show(model: SettleUpModel, actions: MutableList<SettleAction> = mutableListOf(), notReceived: Boolean = true) {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        rule.setContent {
            OwnTallyTheme {
                Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) {
                    SettleUpScreen(model, onBack = {}, onAction = { actions += it }, notReceivedAvailable = notReceived)
                }
            }
        }
    }

    @Test
    fun theOwedPersonSeesTheMockupAndEveryButtonHandsTheRightThingToTheRoute() {
        val calls = mutableListOf<SettleAction>()
        show(SettleUpModel("Goa Trip · whole group", listOf(owedToMe), "Kabir and Isha need no payments. 1 payment already confirmed.", "Goa Trip", listOf(waitingForMe)), calls)
        rule.onNodeWithText("Rohan → You").assertIsDisplayed()
        rule.onNodeWithText("Meera marked ${fmt(1_350)} as paid. Waiting for you to confirm.").assertIsDisplayed()
        save("settle-owed")
        rule.onNodeWithText("Send reminder").performClick()
        rule.onNodeWithText("Mark received").performClick()
        rule.onNodeWithText("Not received").performScrollTo().performClick()
        rule.onNodeWithText("Confirm received").performScrollTo().performClick()
        assertEquals(listOf(SettleAction.Reminder(owedToMe), SettleAction.MarkReceived(owedToMe), SettleAction.NotReceived(waitingForMe), SettleAction.Confirm(id(1))), calls)
    }

    @Test
    fun theDebtorSeesCopyCallAndIPaid() {
        val calls = mutableListOf<SettleAction>()
        show(SettleUpModel("Goa Trip · whole group", listOf(iOwe), "", "Goa Trip"), calls)
        rule.onNodeWithText("+91 99887 76655").assertIsDisplayed()
        save("settle-owe")
        rule.onNodeWithText("Copy number").performClick()
        rule.onNodeWithText("Call").performClick()
        rule.onNodeWithText("I paid").performClick()
        assertEquals(listOf(SettleAction.Copy(iOwe), SettleAction.Call(iOwe), SettleAction.IPaid(iOwe)), calls)
    }

    @Test
    fun notReceivedIsLeftOutWhenItsScreenIsNotThereAndADisputeAndADuplicateAreShown() {
        val calls = mutableListOf<SettleAction>()
        val dup = DuplicateRow(id(3), "This looks like the same payment recorded twice: Meera marked ${fmt(1_350)} as paid and so did you.", "Same payment: ignore Meera's")
        show(SettleUpModel("Goa Trip · whole group", listOf(owedToMe), "", "Goa Trip", listOf(waitingForMe), listOf(disputedMine), listOf(dup)), calls, notReceived = false)
        rule.onNodeWithText("Same payment: ignore Meera's").assertIsDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("You told Rohan nothing was received."))
        rule.onNodeWithText("You told Rohan nothing was received.").assertIsDisplayed()
        rule.onNodeWithText("Not received").assertDoesNotExist()
        save("settle-states")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Same payment: ignore Meera's"))
        rule.onNodeWithText("Same payment: ignore Meera's").performClick()
        assertEquals(listOf<SettleAction>(SettleAction.CancelDuplicate(id(3))), calls)
    }

    @Test
    fun theAmountBoxIsFilledWithThePlanAndWarnsWhenMoreIsTyped() {
        val saved = mutableListOf<Money>()
        rule.setContent { OwnTallyTheme { AmountDialog("I paid Meera", money(1_350), onCancel = {}, onConfirm = { saved += it }) } }
        rule.onNodeWithText("I paid Meera").assertIsDisplayed()
        rule.onNodeWithText("1350").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextReplacement("1400")
        rule.onNodeWithText(AmountBoxState.MORE_THAN_PLANNED).assertIsDisplayed()
        rule.waitForIdle()
        // A dialog has its own window, so the screenshot is taken of the whole display.
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "settle-amount-more.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        rule.onNodeWithText("Save").performClick()
        assertEquals(listOf(money(1_400)), saved)
    }

    @Test
    fun theAmountBoxWillNotSaveNothing() {
        rule.setContent { OwnTallyTheme { AmountDialog("Received from Rohan", money(100), onCancel = {}, onConfirm = {}) } }
        rule.onNode(hasSetTextAction()).performTextReplacement("")
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }
}
