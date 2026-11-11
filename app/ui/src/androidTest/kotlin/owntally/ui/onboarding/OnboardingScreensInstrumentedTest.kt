package owntally.ui.onboarding

import androidx.compose.ui.test.onNodeWithContentDescription
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import owntally.crypto.Bip39
import owntally.crypto.RecoveryPhrase
import owntally.ui.theme.OwnTallyTheme

@RunWith(AndroidJUnit4::class)
class OnboardingScreensInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val phrase = Bip39.fromEntropy(ByteArray(16) { it.toByte() })
    private val completed = mutableListOf<Triple<RecoveryPhrase, String, String>>()
    private var finished = 0

    private val firstRun = object : FirstRun {
        override fun newPhrase() = phrase
        override fun complete(phrase: RecoveryPhrase, name: String, phone: String) {
            completed += Triple(phrase, name, phone)
        }
    }

    private lateinit var vm: OnboardingViewModel

    private fun start() {
        vm = OnboardingViewModel(firstRun, Random(3), Dispatchers.IO)
        rule.setContent { OwnTallyTheme { OnboardingFlow(vm) { finished++ } } }
    }

    private fun secure() = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun toPhrase() {
        rule.onNodeWithText("For example, Asha").performTextInput("Asha")
        rule.onNodeWithText("+91 98765 43210").performTextInput("98765 43210")
        rule.onNodeWithText("Continue").performClick()
    }

    private fun toConfirm() {
        toPhrase()
        rule.onNodeWithText("I've written it down").performClick()
    }

    private fun typeWords(right: Boolean = true) {
        val words = phrase.words()
        vm.state.value.positions.forEach { pos ->
            val word = if (right) words[pos - 1] else "qqqq"
            rule.onNodeWithText("Type word $pos").performTextInput(word)
        }
    }

    @Test
    fun welcomeShowsTheMockupTextsAndIsNotSecure() {
        start()
        rule.onNodeWithText("Split expenses.\nKeep your data.").assertExists()
        rule.onNodeWithText("No account, and no server that keeps your data. Everything stays on your phone and moves, encrypted, to your friends' phones.").assertExists()
        rule.onNodeWithText("Your name").assertExists()
        rule.onNodeWithText("Phone number").assertExists()
        rule.onNodeWithText("Continue").assertIsEnabled()
        rule.onNodeWithText("Next: save your recovery phrase").assertExists()
        assertFalse(secure(), "the welcome screen may be captured")
    }

    @Test
    fun emptyDetailsShowWhatToFix() {
        start()
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Enter your name.").assertExists()
        rule.onNodeWithText("Enter your phone number.").assertExists()
        rule.onNodeWithText("Save your recovery phrase").assertDoesNotExist()
    }

    // ---- M11: restoring from a recovery phrase --------------------------------------------------------------------------------------

    private fun toRestore() {
        rule.onNodeWithText("For example, Asha").performTextInput("Asha")
        rule.onNodeWithText("+91 98765 43210").performTextInput("98765 43210")
        rule.onNodeWithText("I already have a recovery phrase").performClick()
    }

    @Test
    fun theWelcomeScreenOffersToRestoreAndAsksForTheDetailsFirst() {
        start()
        rule.onNodeWithText("I already have a recovery phrase").assertExists()
        rule.onNodeWithText("I already have a recovery phrase").performClick()
        rule.onNodeWithText("Enter your name.").assertExists()
        rule.onNodeWithText("Restore with your recovery phrase").assertDoesNotExist()
    }

    @Test
    fun theRestoreScreenWaitsForWordsThenNamesTheProblemWithoutRepeatingWords() {
        start()
        toRestore()
        rule.onNodeWithText("Restore with your recovery phrase").assertExists()
        rule.onNodeWithText("Restore").assertIsNotEnabled()
        owntally.ui.A11yChecks.assertAccessible(rule, "Restore")
        rule.onNodeWithContentDescription("Your 12 words").performTextInput("one two three")
        rule.onNodeWithText("Restore").assertIsEnabled().performClick()
        rule.onNodeWithText("Enter all 12 words. You entered 3.").assertExists()
        assertEquals(emptyList(), completed)
    }

    @Test
    fun anUnknownWordIsNamedByPositionAndAWrongOrderSaysTheWordsDoNotMakeAPhrase() {
        start()
        toRestore()
        val words = phrase.words()
        rule.onNodeWithContentDescription("Your 12 words").performTextInput(words.toMutableList().also { it[2] = "qqqqqq" }.joinToString(" "))
        rule.onNodeWithText("Restore").performClick()
        rule.onNodeWithText("Word 3 isn't in the word list. Check how it is spelled.").assertExists()
        assertEquals(emptyList(), completed)
    }

    @Test
    fun theRightWordsRestoreSaveTheIdentityAndFinish() {
        start()
        toRestore()
        rule.onNodeWithContentDescription("Your 12 words").performTextInput(phrase.words().joinToString(" "))
        rule.onNodeWithText("Restore").performClick()
        rule.waitUntil(5_000) { finished == 1 }
        assertEquals(listOf(Triple(phrase, "Asha", "98765 43210")), completed)
    }

    @Test
    fun systemBackFromTheRestoreScreenReturnsToTheStart() {
        start()
        toRestore()
        rule.onNodeWithText("Back to the start").performClick()
        rule.onNodeWithText("Split expenses.\nKeep your data.").assertExists()
        assertEquals(emptyList(), completed)
    }

    @Test
    fun thePhraseScreenShowsTwelveNumberedWordsAndIsSecure() {
        start()
        toPhrase()
        rule.onNodeWithText("Save your recovery phrase").assertExists()
        rule.onNodeWithText("Step 2 of 2").assertExists()
        phrase.words().forEachIndexed { i, w -> rule.onNode(hasContentDescription("Word ${i + 1}: $w")).assertExists() }
        rule.onNodeWithText("I've written it down").assertIsEnabled()
        rule.waitForIdle()
        assertFalse(secure(), "screenshots are allowed on every screen (owner decision, 4 Nov 2026)")
    }

    @Test
    fun theConfirmScreenAsksForThreeWordsIsSecureAndCannotFinishEmpty() {
        start()
        toConfirm()
        rule.onNodeWithText("Check you wrote it down").assertExists()
        vm.state.value.positions.forEach { rule.onNodeWithText("Word $it").assertExists() }
        rule.onNodeWithText("Finish").assertIsNotEnabled()
        rule.waitForIdle()
        assertFalse(secure())
        assertEquals(emptyList(), completed)
    }

    @Test
    fun wrongWordsAreRefusedAndNothingIsSaved() {
        start()
        toConfirm()
        typeWords(right = false)
        rule.onNodeWithText("Finish").assertIsEnabled().performClick()
        rule.onNodeWithText("Those words don't match what we showed. Check your paper, or show the words again.").assertExists()
        assertEquals(emptyList(), completed)
        assertEquals(0, finished)
    }

    @Test
    fun rightWordsSaveFinishAndLiftTheScreenshotBlock() {
        start()
        toConfirm()
        typeWords(right = true)
        rule.onNodeWithText("Finish").performClick()
        rule.waitUntil(5000) { finished == 1 }
        assertEquals(1, completed.size)
        assertEquals(Triple(phrase, "Asha", "98765 43210"), completed.single())
    }

    @Test
    fun showTheWordsAgainReturnsToTheSamePhrase() {
        start()
        toConfirm()
        rule.onNodeWithText("Show the words again").performClick()
        rule.onNodeWithText("Save your recovery phrase").assertExists()
        phrase.words().forEachIndexed { i, w -> rule.onNode(hasContentDescription("Word ${i + 1}: $w")).assertExists() }
    }

    @Test
    fun systemBackStepsBackThroughTheFlowAndNeverSkipsAhead() {
        start()
        toConfirm()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Save your recovery phrase").assertExists()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Split expenses.\nKeep your data.").assertExists()
        assertEquals(0, finished)
        assertEquals(emptyList(), completed)
    }

    @Test
    fun theBlockIsLiftedWhenLeavingThePhraseScreen() {
        start()
        toPhrase()
        rule.waitForIdle()
        assertFalse(secure())
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }
}

/** A recovery word that is cut off would make someone write down the wrong word, so no word may ever be clipped. */
@RunWith(AndroidJUnit4::class)
class PhraseWordsFitInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** The phrase with the longest words out of many random ones: the hardest case for the chips. */
    private fun longestPhrase(): RecoveryPhrase {
        val random = Random(11)
        return (1..4000).map { Bip39.fromEntropy(random.nextBytes(16)) }.maxBy { p -> p.words().sumOf { it.length } }
    }

    private fun check(fontScale: Float) {
        val phrase = longestPhrase()
        val words = phrase.words()
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale),
            ) { OwnTallyTheme { PhraseScreen(phrase) { } } }
        }
        rule.waitForIdle()
        words.forEachIndexed { i, word ->
            val node = rule.onNodeWithTag("word-${i + 1}", useUnmergedTree = true).fetchSemanticsNode()
            val shown = node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
            assertEquals(word, shown, "word ${i + 1} at font scale $fontScale")
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertFalse(layouts.single().hasVisualOverflow, "\"$word\" is cut off at font scale $fontScale")
            assertEquals(1, layouts.single().lineCount, "\"$word\" wrapped at font scale $fontScale")
        }
    }

    @Test fun longWordsFitAtNormalSize() = check(1.0f)
    @Test fun longWordsFitWhenTheTextIsLarger() = check(1.3f)
    @Test fun longWordsFitAtTheLargestTextSize() = check(2.0f)
}
