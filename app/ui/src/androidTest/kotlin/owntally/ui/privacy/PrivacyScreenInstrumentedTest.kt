package owntally.ui.privacy

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import owntally.ui.A11yChecks
import owntally.ui.theme.OwnTallyColors
import owntally.ui.theme.OwnTallyTheme

/** The Privacy screen (M10, T10.5): every sentence of the facts is reachable, the back button works, and it passes the accessibility checks at the normal size and at 200%. */
@RunWith(AndroidJUnit4::class)
class PrivacyScreenInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(scale: Float, onBack: () -> Unit = {}) {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = scale)) {
                OwnTallyTheme { Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) { PrivacyScreen(onBack) } }
            }
        }
    }

    private fun save(name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun everySectionAndEverySentenceCanBeReachedAndTheBackButtonWorks() {
        var back = 0
        show(1.0f, onBack = { back++ })
        rule.onNodeWithText("Privacy").assertIsDisplayed()
        for (section in PrivacyFacts.sections) {
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(section.heading))
            for (line in section.lines) { rule.onNode(hasScrollAction()).performScrollToNode(hasText(line, substring = true)); rule.onNode(hasText(line, substring = true)).assertIsDisplayed() }
        }
        A11yChecks.assertAccessible(rule, "privacy")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Privacy"))
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Back")).performClick()
        assertEquals(1, back)
    }

    @Test fun atTwoHundredPercentNothingSticksOutAndItScrolls() {
        show(2.0f)
        A11yChecks.assertAccessible(rule, "privacy at 200% text", reachableByScrolling = true)
        save("large-privacy")
    }
}
