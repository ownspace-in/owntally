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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
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
import owntally.data.session.NearbyPeer
import owntally.data.session.NearbyStatus
import owntally.model.PublicKey
import owntally.ui.nearby.NearbyGate
import owntally.ui.nearby.NearbyModels
import owntally.ui.nearby.NearbyPermissions
import owntally.ui.nearby.NearbyScreen
import owntally.ui.theme.OwnTallyColors
import owntally.ui.theme.OwnTallyTheme

/** The Nearby screen on a phone: the explanation before any permission is asked, "you said no", and running. */
@RunWith(AndroidJUnit4::class)
class NearbyScreenInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val rohan = PublicKey(ByteArray(32) { 1 })
    private val meera = PublicKey(ByteArray(32) { 2 })
    private val names = mapOf(rohan to "Rohan", meera to "Meera")

    private fun show(gate: NearbyGate, status: NearbyStatus, calls: MutableList<String> = mutableListOf(), unavailable: Boolean = false) {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        rule.setContent {
            OwnTallyTheme {
                Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) {
                    NearbyScreen(
                        NearbyModels.build("Goa Trip", names, status, unavailable), gate, NearbyPermissions.explanation(34),
                        onBack = { calls += "back" }, onContinue = { calls += "continue" }, onOpenSettings = { calls += "settings" },
                        onStart = { calls += "start" }, onStop = { calls += "stop" }, onLookAgain = { calls += "again" },
                    )
                }
            }
        }
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theExplanationComesBeforeAnyPermissionAndContinueIsTheOnlyWayOn() {
        val calls = mutableListOf<String>()
        show(NearbyGate.EXPLAIN, NearbyStatus(), calls)
        rule.onNodeWithText("Nearby").assertIsDisplayed()
        rule.onNodeWithText(NearbyPermissions.explanation(34)).assertIsDisplayed()
        rule.onNodeWithText("Start").assertDoesNotExist()
        save("nearby-explain")
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("continue", "back"), calls)
    }

    @Test
    fun aRefusedPermissionPointsToTheSettings() {
        val calls = mutableListOf<String>()
        show(NearbyGate.DENIED, NearbyStatus(), calls)
        rule.onNodeWithText("Nearby cannot look for friends without the permission it asked for. You can allow it in your phone's settings for OwnTally.").assertIsDisplayed()
        rule.onNodeWithText("Open settings").performClick()
        assertEquals(listOf("settings"), calls)
    }

    @Test
    fun beforeStartOnlyStartIsOfferedThenTheMembersAreFollowedAndStopEndsIt() {
        val calls = mutableListOf<String>()
        show(NearbyGate.READY, NearbyStatus(), calls)
        rule.onNodeWithText("Ready").assertIsDisplayed()
        rule.onAllNodesWithText("Not started").assertCountEquals(2) // one line for each member
        rule.onNodeWithText("Start").performClick()
        assertEquals(listOf("start"), calls)
    }

    @Test
    fun runningShowsEachMemberAndTheButtonsThatFit() {
        val calls = mutableListOf<String>()
        show(NearbyGate.READY, NearbyStatus(true, mapOf(rohan to NearbyPeer.NOT_FOUND, meera to NearbyPeer.SYNCING)), calls)
        rule.onNodeWithText("Syncing with Meera…").assertIsDisplayed()
        rule.onNodeWithText("Rohan was not found nearby. Ask them to open this screen too.").assertIsDisplayed()
        save("nearby-running")
        rule.onNodeWithText("Look again").performClick()
        rule.onNodeWithText("Stop").performClick()
        assertEquals(listOf("again", "stop"), calls)
    }

    @Test
    fun withoutGooglePlayServicesTheScreenSaysSoAndOffersNothingToStart() {
        show(NearbyGate.READY, NearbyStatus(), unavailable = true)
        rule.onNodeWithText("Nearby is not available on this phone").assertIsDisplayed()
        rule.onNodeWithText("Start").assertDoesNotExist()
    }
}
