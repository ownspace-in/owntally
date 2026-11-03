package owntally.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import owntally.crypto.Bip39
import owntally.crypto.RecoveryPhrase
import owntally.ui.GroupsPlaceholder
import owntally.ui.theme.OwnTallyTheme

/**
 * Saves a picture of each first-run screen, drawn edge to edge as in the app, so they can be compared with design/07, 08 and 08b.
 * Reads nothing and checks nothing: the pictures are pulled from the phone (see docs/tasks/M3-storage-app-shell.md).
 */
@RunWith(AndroidJUnit4::class)
class ScreenImagesInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val phrase = Bip39.fromEntropy(ByteArray(16) { it.toByte() })
    private val firstRun = object : FirstRun {
        override fun newPhrase() = phrase
        override fun complete(phrase: RecoveryPhrase, name: String, phone: String) = Unit
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun saveTheFirstRunScreens() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val vm = OnboardingViewModel(firstRun, Random(3), Dispatchers.IO)
        rule.setContent { OwnTallyTheme { OnboardingFlow(vm) { } } }
        save("01-welcome-empty")
        rule.onNodeWithText("Continue").performClick()
        save("02-welcome-errors")
        rule.onNodeWithText("For example, Asha").performTextInput("Asha")
        rule.onNodeWithText("+91 98765 43210").performTextInput("98765 43210")
        save("03-welcome-filled")
        rule.onNodeWithText("Continue").performClick()
        save("04-phrase")
        rule.onNodeWithText("I've written it down").performClick()
        save("05-confirm-empty")
        val pos = vm.state.value.positions
        rule.onNodeWithText("Type word ${pos[0]}").performTextInput(phrase.words()[pos[0] - 1])
        rule.onNodeWithText("Type word ${pos[1]}").performTextInput("qqqq")
        rule.onNodeWithText("Type word ${pos[2]}").performTextInput(phrase.words()[pos[2] - 1])
        rule.onNodeWithText("Finish").performClick()
        save("06-confirm-mismatch")
    }

    @Test
    fun savePhraseAtTheLargestTextSize() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2.0f),
            ) { OwnTallyTheme { PhraseScreen(Bip39.fromEntropy(ByteArray(16) { (it * 7).toByte() })) { } } }
        }
        save("08-phrase-large-text")
    }

    @Test
    fun saveThePlaceholder() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        rule.setContent { OwnTallyTheme { GroupsPlaceholder() } }
        save("07-groups-placeholder")
    }
}
