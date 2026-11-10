package owntally.ui.groups

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import owntally.data.store.PendingJoin
import owntally.data.store.PendingState
import owntally.model.GroupId
import owntally.model.PublicKey
import owntally.ui.theme.OwnTallyColors
import owntally.ui.theme.OwnTallyTheme

/** The join screens (design/09 and 09b) on a phone: what they show, what their buttons do, and pictures to compare with the mockups. */
@RunWith(AndroidJUnit4::class)
class JoinScreensInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_800_000_000L
    private fun join(state: PendingState = PendingState.OPENED, name: String = "Goa Trip") =
        PendingJoin(GroupId(ByteArray(16) { 1 }), PublicKey(ByteArray(32) { 2 }), ByteArray(16) { 3 }, 4, 1, now + 27_720, "", "", state, name, if (name.isEmpty()) "" else "Asha", if (name.isEmpty()) 0 else 5, now - 60)

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        rule.setContent { OwnTallyTheme { Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) { content() } } }
    }

    private fun save(name: String) {
        owntally.ui.A11yChecks.assertAccessible(rule, name)
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theFormShowsTheGroupTheNoteAndSendsWhatWasTyped() {
        val calls = mutableListOf<String>()
        val name = mutableStateOf("Rohan"); val phone = mutableStateOf("+91 98765 43210")
        show {
            JoinFormScreen(
                JoinModels.form(join(), name.value, phone.value, now), name.value, phone.value, { name.value = it }, { phone.value = it }, null, false,
                onRequest = { calls += "request" }, onBack = { calls += "back" }, onTryAgain = null, onDiscard = { calls += "discard" },
            )
        }
        rule.onNodeWithText("You were invited").assertIsDisplayed()
        rule.onNodeWithText("Goa Trip").assertIsDisplayed()
        rule.onNodeWithText("Owner: Asha · 5 members").assertIsDisplayed()
        rule.onNodeWithText("This invite works once and expires in 7 hrs 42 min.").assertIsDisplayed()
        rule.onNodeWithText("Your name in this group").assertIsDisplayed()
        rule.onNodeWithText("Members of Goa Trip will see this name and number. Nothing else is shared.").assertIsDisplayed()
        save("join-form")
        rule.onNodeWithText("Request to join").performClick()
        rule.onNodeWithText("Not for me").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("request", "discard", "back"), calls)
    }

    @Test
    fun whenTheHostWasNotReachedTheFormSaysSoAndOffersAnotherTry() {
        val calls = mutableListOf<String>()
        show {
            JoinFormScreen(
                JoinModels.form(join(name = ""), "", "", now), "", "", {}, {}, "Asha's phone isn't reachable. Ask Asha to open OwnTally, then tap Retry.", false,
                onRequest = {}, onBack = {}, onTryAgain = { calls += "again" }, onDiscard = {},
            )
        }
        rule.onNodeWithText("A group").assertIsDisplayed()
        rule.onNodeWithText("The person who invited you isn't reachable right now, so we can't show the group yet. You can still ask to join.").assertIsDisplayed()
        rule.onNodeWithText("Asha's phone isn't reachable. Ask Asha to open OwnTally, then tap Retry.").assertIsDisplayed()
        rule.onNodeWithText("Try to reach them again").performClick()
        assertEquals(listOf("again"), calls)
        save("join-form-unreachable")
    }

    @Test
    fun waitingApprovedAndDeclinedEachShowTheirWordsAndRetryIsTheOnlyButton() {
        val calls = mutableListOf<String>()
        show { JoinWaitingScreen(JoinModels.waiting(join(PendingState.WAITING), now), null, false, onRetry = { calls += "retry" }, onBack = {}) }
        rule.onNodeWithText("Waiting for approval").assertIsDisplayed()
        rule.onNodeWithText("If it doesn't connect").assertIsDisplayed()
        rule.onNodeWithText("Ask an admin to open the app, then tap Retry.").assertIsDisplayed()
        save("join-waiting")
        rule.onNodeWithText("Retry").performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun pastingTheInviteEnablesContinueAndAProblemIsShown() {
        val calls = mutableListOf<String>()
        val text = mutableStateOf("")
        val problem = mutableStateOf<String?>(null)
        show { PasteInviteScreen(text.value, { text.value = it }, problem.value, false, onPaste = { calls += "paste" }, onContinue = { calls += "continue:" + text.value }, onBack = { calls += "back" }) }
        rule.onNodeWithText("Join a group").assertIsDisplayed()
        rule.onNodeWithContentDescription("Invite link or code").performTextInput("https://joinowntally.ownspace.in/j#abc")
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Paste from clipboard").performClick()
        problem.value = "That doesn't look like an OwnTally invite. Paste the whole link or code."
        rule.onNodeWithText("That doesn't look like an OwnTally invite. Paste the whole link or code.").assertIsDisplayed()
        assertEquals(listOf("continue:https://joinowntally.ownspace.in/j#abc", "paste"), calls)
    }
}
