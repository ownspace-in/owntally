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
import owntally.data.session.PeerState
import owntally.data.session.PeerStatus
import owntally.sync.LinkKind
import owntally.sync.RelayResult
import owntally.ui.theme.OwnTallyColors
import owntally.ui.theme.OwnTallyTheme

/** "Can't reach <name>" and the Sync rows that open it, on a phone (design/16, 16b). */
@RunWith(AndroidJUnit4::class)
class CantReachScreenInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_000L
    private fun model(st: PeerStatus) = CantReachModels.build("Meera", st, now)

    private fun save(name: String) {
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(model: CantReachModel, calls: MutableList<String>, nearby: Boolean = true) {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        rule.setContent {
            OwnTallyTheme {
                Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) {
                    CantReachScreen(model, { calls += "back" }, { calls += "try" }, if (nearby) ({ calls += "nearby" }) else null)
                }
            }
        }
    }

    @Test
    fun theScreenShowsTheReasonAndTheTwoWaysAndItsButtonsDoWhatTheySay() {
        val calls = mutableListOf<String>()
        show(model(PeerStatus(PeerState.UNREACHABLE, attempts = 2, failures = 2, lastAttemptSeconds = 100, nextTrySeconds = 1_900, notShared = 3, relay = RelayResult.PEER_NOT_THERE)), calls)
        rule.onNodeWithText("Can't reach Meera").assertIsDisplayed()
        rule.onNodeWithText("The relay is used automatically").assertIsDisplayed()
        save("cant-reach")
        rule.onNodeWithText("Try now").performClick()
        rule.onNodeWithText("Meet and sync nearby").performClick()
        assertEquals(listOf("try", "nearby"), calls)
    }

    @Test
    fun whenTheRelayCannotBeReachedFromThisPhoneTheScreenSaysSo() {
        show(model(PeerStatus(PeerState.FAILED, attempts = 2, failures = 2, lastAttemptSeconds = 100, nextTrySeconds = 1_900, notShared = 1, relay = RelayResult.RELAY_DOWN)), mutableListOf())
        rule.onNodeWithText("No way to Meera from here").assertIsDisplayed()
        save("cant-reach-relay-down")
    }

    @Test
    fun afterASyncThroughTheRelayTheScreenSaysItWorkedAndNoNearbyButtonShowsWithoutNearby() {
        show(model(PeerStatus(PeerState.OK, attempts = 1, lastSuccessSeconds = 100, kind = LinkKind.RELAYED)), mutableListOf(), nearby = false)
        rule.onNodeWithText("Synced through the relay.").assertIsDisplayed()
        rule.onNodeWithText("Meet and sync nearby").assertDoesNotExist()
        save("cant-reach-relay-worked")
    }
}
