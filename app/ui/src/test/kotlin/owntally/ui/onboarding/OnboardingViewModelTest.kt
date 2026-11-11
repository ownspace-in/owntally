package owntally.ui.onboarding

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import owntally.crypto.Bip39
import owntally.crypto.PhraseProblem
import owntally.crypto.RecoveryPhrase

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private class FakeFirstRun : FirstRun {
        var phrases = 0
        var completed = mutableListOf<Triple<RecoveryPhrase, String, String>>()
        var failWith: Exception? = null
        lateinit var last: RecoveryPhrase

        override fun newPhrase(): RecoveryPhrase = Bip39.generate().also { phrases++; last = it }
        override fun complete(phrase: RecoveryPhrase, name: String, phone: String) {
            failWith?.let { throw it }
            completed += Triple(phrase, name, phone)
        }
    }

    private val dispatcher = UnconfinedTestDispatcher()
    private val firstRun = FakeFirstRun()

    @BeforeTest
    fun setMain() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    private fun vm(seed: Int = 1) = OnboardingViewModel(firstRun, Random(seed), dispatcher)
    private val OnboardingViewModel.s get() = state.value

    private fun OnboardingViewModel.toPhrase(name: String = "Asha", phone: String = "98765 43210") {
        onNameChange(name); onPhoneChange(phone); continueFromWelcome()
    }

    private fun OnboardingViewModel.toConfirm() {
        toPhrase(); phraseWritten()
    }

    /** Types the right word for each asked position. */
    private fun OnboardingViewModel.answerRight() {
        val words = firstRun.last.words()
        s.positions.forEachIndexed { i, pos -> onAnswerChange(i, words[pos - 1]) }
    }

    // --- welcome -----------------------------------------------------------------------------

    @Test
    fun itStartsOnTheWelcomeStepWithNoPhrase() {
        val v = vm()
        assertEquals(Step.WELCOME, v.s.step)
        assertNull(v.s.phrase)
        assertEquals(0, firstRun.phrases)
    }

    @Test
    fun badNameOrPhoneStaysOnWelcomeAndShowsWhat() {
        val v = vm()
        v.continueFromWelcome()
        assertEquals(Step.WELCOME, v.s.step)
        assertEquals(NameProblem.EMPTY, v.s.nameProblem)
        assertEquals(PhoneProblem.EMPTY, v.s.phoneProblem)
        v.onNameChange("Asha")
        assertNull(v.s.nameProblem, "typing clears the problem")
        v.onPhoneChange("12")
        v.continueFromWelcome()
        assertEquals(PhoneProblem.BAD_LENGTH, v.s.phoneProblem)
        assertNull(v.s.phrase)
        assertEquals(0, firstRun.phrases, "no phrase is made until the details are fine")
    }

    @Test
    fun goodDetailsGoToThePhraseStepWithANewValidPhrase() {
        val v = vm()
        v.toPhrase()
        assertEquals(Step.PHRASE, v.s.step)
        assertNull(Bip39.check(v.s.phrase!!.words()))
        assertEquals(1, firstRun.phrases)
    }

    // --- phrase ------------------------------------------------------------------------------

    @Test
    fun goingBackAndForwardShowsTheSamePhraseAndKeepsWhatWasTyped() {
        val v = vm()
        v.toPhrase("Asha", "98765 43210")
        val phrase = v.s.phrase
        assertTrue(v.back())
        assertEquals(Step.WELCOME, v.s.step)
        assertEquals("Asha", v.s.name)
        assertEquals("98765 43210", v.s.phone)
        v.continueFromWelcome()
        assertEquals(phrase, v.s.phrase)
        assertEquals(1, firstRun.phrases, "the same phrase, not a new one")
    }

    @Test
    fun backFromTheFirstStepIsLeftToTheSystem() {
        assertFalse(vm().back())
    }

    // --- confirm -----------------------------------------------------------------------------

    @Test
    fun writtenItDownMovesToConfirmWithThreeWordsToAskFor() {
        val v = vm()
        v.toConfirm()
        assertEquals(Step.CONFIRM, v.s.step)
        assertEquals(3, v.s.positions.size)
        assertEquals(listOf("", "", ""), v.s.answers.map { it.text })
        assertFalse(v.s.canFinish)
    }

    @Test
    fun cannotFinishWithMissingAnswers() {
        val v = vm()
        v.toConfirm()
        v.finish()
        assertEquals(Step.CONFIRM, v.s.step)
        assertEquals(emptyList(), firstRun.completed)
        v.onAnswerChange(0, "lemon")
        v.onAnswerChange(1, "  ")
        assertFalse(v.s.canFinish)
        v.finish()
        assertEquals(emptyList(), firstRun.completed)
    }

    @Test
    fun aWrongWordSavesNothingAndSaysSo() {
        val v = vm()
        v.toConfirm()
        val words = firstRun.last.words()
        v.onAnswerChange(0, words[v.s.positions[0] - 1])
        v.onAnswerChange(1, words[v.s.positions[1] - 1])
        v.onAnswerChange(2, "qqqq") // not a word of the list at all
        v.finish()
        assertEquals(Step.CONFIRM, v.s.step)
        assertTrue(v.s.mismatch)
        assertEquals(emptyList(), firstRun.completed)
    }

    @Test
    fun aWordFromTheWrongPositionIsWrong() {
        val v = vm()
        v.toConfirm()
        val words = firstRun.last.words()
        val asked = v.s.positions
        val other = (1..12).first { it !in asked && words[it - 1] !in asked.map { p -> words[p - 1] } }
        asked.forEachIndexed { i, p -> v.onAnswerChange(i, words[p - 1]) }
        v.onAnswerChange(0, words[other - 1])
        v.finish()
        assertTrue(v.s.mismatch)
        assertEquals(emptyList(), firstRun.completed)
    }

    @Test
    fun editingAnAnswerAfterAMismatchClearsTheMessage() {
        val v = vm()
        v.toConfirm()
        v.onAnswerChange(0, "x"); v.onAnswerChange(1, "y"); v.onAnswerChange(2, "z")
        v.finish()
        assertTrue(v.s.mismatch)
        v.onAnswerChange(0, "w")
        assertFalse(v.s.mismatch)
    }

    @Test
    fun theRightWordsSaveOnceWithTheTrimmedDetailsAndFinish() {
        val v = vm()
        v.toPhrase("  Asha  ", " 98765 43210 ")
        v.phraseWritten()
        val phrase = v.s.phrase!!
        val words = phrase.words()
        v.s.positions.forEachIndexed { i, p -> v.onAnswerChange(i, "  " + words[p - 1].uppercase() + " ") }
        v.finish()
        assertEquals(Step.DONE, v.s.step)
        assertEquals(listOf(Triple(phrase, "Asha", "98765 43210")), firstRun.completed)
    }

    @Test
    fun afterwardsThePhraseAndTheAnswersAreGoneFromTheState() {
        val v = vm()
        v.toConfirm()
        v.answerRight()
        v.finish()
        assertNull(v.s.phrase)
        assertEquals(emptyList(), v.s.answers)
        assertEquals(emptyList(), v.s.positions)
    }

    @Test
    fun aSecondTapOnFinishDoesNotSaveTwice() {
        val v = vm()
        v.toConfirm()
        v.answerRight()
        v.finish()
        v.finish()
        assertEquals(1, firstRun.completed.size)
    }

    @Test
    fun ifSavingFailsItStaysOnConfirmWithTheWordsAndCanBeRetried() {
        val v = vm()
        v.toConfirm()
        v.answerRight()
        firstRun.failWith = IllegalStateException("keystore unavailable")
        v.finish()
        assertEquals(Step.CONFIRM, v.s.step)
        assertEquals(SaveProblem.COULD_NOT_SAVE, v.s.saveProblem)
        assertFalse(v.s.saving)
        assertNotNull(v.s.phrase)
        firstRun.failWith = null
        v.finish()
        assertEquals(Step.DONE, v.s.step)
        assertEquals(1, firstRun.completed.size)
    }

    @Test
    fun showTheWordsAgainGoesBackToThePhraseAndForgetsTheAnswers() {
        val v = vm()
        v.toConfirm()
        v.onAnswerChange(0, "abc")
        v.showWordsAgain()
        assertEquals(Step.PHRASE, v.s.step)
        v.phraseWritten()
        assertEquals(listOf("", "", ""), v.s.answers.map { it.text })
    }

    @Test
    fun backFromConfirmGoesToThePhrase() {
        val v = vm()
        v.toConfirm()
        assertTrue(v.back())
        assertEquals(Step.PHRASE, v.s.step)
    }

    // --- the confirm step cannot be skipped -----------------------------------------------------

    @Test
    fun theStepsCannotBeJumpedToFinishWithoutConfirming() {
        val v = vm()
        v.phraseWritten()
        assertEquals(Step.WELCOME, v.s.step)
        v.finish()
        assertEquals(Step.WELCOME, v.s.step)
        v.showWordsAgain()
        assertEquals(Step.WELCOME, v.s.step)
        v.toPhrase()
        v.finish()
        assertEquals(Step.PHRASE, v.s.step)
        v.onAnswerChange(0, "lemon")
        assertEquals(Step.PHRASE, v.s.step)
        assertEquals(emptyList(), firstRun.completed)
    }

    // --- the phrase does not leak ----------------------------------------------------------------

    @Test
    fun theStatePrintsNoWord() {
        val v = vm()
        v.toConfirm()
        v.answerRight()
        val text = v.s.toString()
        // A leak of the phrase is words side by side, written the ways a phrase could be printed. One word alone proves nothing: the
        // word list has ordinary words (such as "phone" or "match") that are also names of fields in the state.
        val words = firstRun.last.words()
        fun leaks(t: String) = words.zipWithNext().flatMap { (x, y) -> listOf("$x $y", "$x,$y", "$x, $y", "\"$x\",\"$y\"", "$x\n$y", "$x$y") }.filter { it in t }
        assertEquals(emptyList(), leaks(text), "two phrase words side by side were printed")
        assertTrue(leaks("State(words=" + words.joinToString(", ") + ")").isNotEmpty(), "the check must find a planted phrase")
    }

    // ---- M11, T11.3: restoring from a recovery phrase ---------------------------------------------------------------------------

    private val phrase = Bip39.fromEntropy(ByteArray(16) { 3 })
    private val phraseText = phrase.words().joinToString(" ")

    private fun OnboardingViewModel.toRestore(name: String = "Asha", phone: String = "98765 43210") {
        onNameChange(name); onPhoneChange(phone); startRestore()
    }

    @Test fun startingARestoreChecksTheNameAndNumberFirstLikeANewStart() {
        val vm = vm()
        vm.startRestore()
        assertEquals(Step.WELCOME, vm.s.step)
        assertEquals(NameProblem.EMPTY, vm.s.nameProblem)
        assertEquals(PhoneProblem.EMPTY, vm.s.phoneProblem)
        vm.toRestore()
        assertEquals(Step.RESTORE, vm.s.step)
        assertNull(vm.s.nameProblem)
        assertEquals(0, firstRun.phrases, "a restore never makes a new phrase")
    }

    @Test fun aGoodPhraseTypedInAnyCaseAndSpacingRestoresAndFinishes() {
        val vm = vm()
        vm.toRestore("  Asha  ", " 98765 43210 ")
        vm.onRestoreTextChange("  " + phraseText.uppercase().replace(" ", "\n ") + " \n")
        vm.restore()
        assertEquals(Step.DONE, vm.s.step)
        assertEquals(listOf(Triple(phrase, "Asha", "98765 43210")), firstRun.completed)
        assertEquals("", vm.s.restoreWords.text, "the words are forgotten once they are used")
        assertFalse(vm.s.saving)
    }

    @Test fun aPhraseWithTheWrongNumberOfWordsIsRefusedAndNothingIsSaved() {
        val vm = vm()
        vm.toRestore()
        vm.onRestoreTextChange(phrase.words().take(11).joinToString(" "))
        vm.restore()
        assertEquals(PhraseProblem.WrongWordCount(11), vm.s.restoreProblem)
        assertEquals(Step.RESTORE, vm.s.step)
        assertEquals(emptyList(), firstRun.completed)
    }

    @Test fun aWordThatIsNotInTheListIsNamedByItsPositionNotRepeated() {
        val vm = vm()
        vm.toRestore()
        vm.onRestoreTextChange(phrase.words().toMutableList().also { it[4] = "zzzzzz" }.joinToString(" "))
        vm.restore()
        assertEquals(PhraseProblem.UnknownWord(5), vm.s.restoreProblem)
        assertEquals(emptyList(), firstRun.completed)
    }

    @Test fun twelveRealWordsInTheWrongOrderFailTheChecksum() {
        val vm = vm()
        vm.toRestore()
        val words = phrase.words()
        // swapping two different words changes the check bits for all but a freak chance; find a swap that does
        val swapped = (0 until 11).map { i -> words.toMutableList().also { w -> w[i] = w[i + 1].also { w[i + 1] = w[i] } } }.first { Bip39.check(it) != null }
        vm.onRestoreTextChange(swapped.joinToString(" "))
        vm.restore()
        assertEquals(PhraseProblem.BadChecksum, vm.s.restoreProblem)
        assertEquals(emptyList(), firstRun.completed)
    }

    @Test fun editingTheWordsClearsTheMessage() {
        val vm = vm()
        vm.toRestore()
        vm.onRestoreTextChange("one two")
        vm.restore()
        assertNotNull(vm.s.restoreProblem)
        vm.onRestoreTextChange("one two three")
        assertNull(vm.s.restoreProblem)
    }

    @Test fun ifSavingFailsThePersonStaysOnTheWordsWithTheirWordsAndCanTryAgain() {
        val vm = vm()
        vm.toRestore()
        vm.onRestoreTextChange(phraseText)
        firstRun.failWith = java.io.IOException("keystore")
        vm.restore()
        assertEquals(Step.RESTORE, vm.s.step)
        assertEquals(SaveProblem.COULD_NOT_SAVE, vm.s.saveProblem)
        assertFalse(vm.s.saving)
        assertEquals(phraseText, vm.s.restoreWords.text)
        firstRun.failWith = null
        vm.restore()
        assertEquals(Step.DONE, vm.s.step)
    }

    @Test fun backFromTheWordsGoesToTheStartAndForgetsThem() {
        val vm = vm()
        vm.toRestore()
        vm.onRestoreTextChange(phraseText)
        assertTrue(vm.back())
        assertEquals(Step.WELCOME, vm.s.step)
        assertEquals("", vm.s.restoreWords.text)
        assertNull(vm.s.restoreProblem)
    }

    @Test fun theWordsTypedNeverShowInTheStatesText() {
        val vm = vm()
        vm.toRestore()
        vm.onRestoreTextChange(phraseText)
        val shown = vm.s.toString()
        for (w in phrase.words().distinct().filter { it.length > 4 }) assertFalse(w in shown, "the state's text shows a word of the phrase: $w")
    }

    @Test fun restoreDoesNothingOutsideTheWordsStep() {
        val vm = vm()
        vm.onRestoreTextChange(phraseText)
        vm.restore()
        assertEquals(Step.WELCOME, vm.s.step)
        assertEquals(emptyList(), firstRun.completed)
    }
}
