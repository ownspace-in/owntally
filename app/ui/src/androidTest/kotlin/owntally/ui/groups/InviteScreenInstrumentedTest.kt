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
import androidx.compose.ui.test.onNodeWithContentDescription
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
import owntally.ui.theme.OwnTallyColors
import owntally.ui.theme.OwnTallyTheme

/** The Invite friends screen (design/10) on a phone: what it shows, what its buttons do, and a picture to compare with the mockup. */
@RunWith(AndroidJUnit4::class)
class InviteScreenInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_800_000_000L
    private val link = "https://joinowntally.ownspace.in/j#7Kf2-Qm9x-Lp4r" + "A".repeat(140)

    private fun show(model: InviteModel, calls: MutableList<String> = mutableListOf()) {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        rule.setContent {
            OwnTallyTheme {
                Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) {
                    InviteScreen(model, onBack = { calls += "back" }, onShare = { calls += "share" }, onCopy = { calls += "copy" }, onRevoke = { calls += "revoke" }, onNew = { calls += "new" })
                }
            }
        }
    }

    private fun save(name: String) {
        owntally.ui.A11yChecks.assertAccessible(rule, name)
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun anOpenInviteShowsTheQrTheTimeTheLinkAndEveryButtonDoesItsJob() {
        val calls = mutableListOf<String>()
        show(InviteModels.build("Goa Trip", link, now + 27_720, false, now), calls)
        rule.onNodeWithText("Invite friends").assertIsDisplayed()
        rule.onNodeWithText("Goa Trip").assertIsDisplayed()
        rule.onNodeWithContentDescription("QR code for the invite").assertIsDisplayed()
        rule.onNodeWithText("For friends in the same room").assertIsDisplayed()
        rule.onNodeWithText("Works once. Expires in 7 hrs 42 min.").assertIsDisplayed()
        rule.onNodeWithText("Share invite link").assertIsDisplayed()
        rule.onNodeWithText("Invite code").assertIsDisplayed()
        save("invite-open")
        rule.onNodeWithText("Share invite link").performClick()
        rule.onNodeWithText("Copy").performClick()
        rule.onNodeWithText("Copied").assertIsDisplayed()
        rule.onNodeWithText("Revoke invite").performClick()
        rule.onNodeWithText("New invite").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("share", "copy", "revoke", "new", "back"), calls)
    }

    @Test
    fun aRevokedOrExpiredInviteShowsNoQrAndOnlyOffersANewOne() {
        show(InviteModels.build("Goa Trip", link, now + 100, true, now))
        rule.onNodeWithText("Invite revoked. Nobody can use it any more.").assertIsDisplayed()
        rule.onNodeWithContentDescription("QR code for the invite").assertDoesNotExistCompat()
        rule.onNodeWithText("Share invite link").assertDoesNotExistCompat()
        rule.onNodeWithText("Revoke invite").assertIsNotEnabled()
        rule.onNodeWithText("New invite").assertIsDisplayed()
        save("invite-revoked")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = this.assertDoesNotExist()
}
