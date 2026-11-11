package owntally.ui.onboarding

import owntally.crypto.RecoveryPhrase
import owntally.crypto.InvalidPhraseException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The first-run flow: Welcome (name and phone), the recovery phrase, and a check of three of its words. The check cannot be
 * skipped: the only way to [Step.DONE] is to type the three asked words correctly, which then saves the seed and the profile.
 * The view model outlives screen rotation, so the phrase stays put while the person writes it down.
 */
class OnboardingViewModel(
    private val firstRun: FirstRun,
    private val random: Random = Random.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutable = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> get() = mutable

    fun onNameChange(text: String) = mutable.update { it.copy(name = text, nameProblem = null) }
    fun onPhoneChange(text: String) = mutable.update { it.copy(phone = text, phoneProblem = null) }

    /** Checks the details and shows the phrase. The phrase is made once; going back and forward shows the same one. */
    fun continueFromWelcome() {
        val s = mutable.value
        if (s.step != Step.WELCOME) return
        val nameProblem = OnboardingRules.nameProblem(s.name)
        val phoneProblem = OnboardingRules.phoneProblem(s.phone)
        if (nameProblem != null || phoneProblem != null) {
            mutable.value = s.copy(nameProblem = nameProblem, phoneProblem = phoneProblem)
            return
        }
        mutable.value = s.copy(step = Step.PHRASE, phrase = s.phrase ?: firstRun.newPhrase(), nameProblem = null, phoneProblem = null)
    }

    /** "I already have a recovery phrase" (M11): checks the name and number as a new start does, then asks for the words. */
    fun startRestore() {
        val s = mutable.value
        if (s.step != Step.WELCOME) return
        val nameProblem = OnboardingRules.nameProblem(s.name)
        val phoneProblem = OnboardingRules.phoneProblem(s.phone)
        if (nameProblem != null || phoneProblem != null) {
            mutable.value = s.copy(nameProblem = nameProblem, phoneProblem = phoneProblem)
            return
        }
        mutable.value = s.copy(step = Step.RESTORE, nameProblem = null, phoneProblem = null, restoreWords = Answer(""), restoreProblem = null, saveProblem = null)
    }

    fun onRestoreTextChange(text: String) = mutable.update { it.copy(restoreWords = Answer(text), restoreProblem = null, saveProblem = null) }

    /** Checks the typed words (any case, any spacing or line breaks); if they make a valid phrase, saves it as the identity of this phone and finishes. */
    fun restore() {
        val s = mutable.value
        if (!s.canRestore) return
        val phrase = try {
            RecoveryPhrase.fromText(s.restoreWords.text)
        } catch (e: InvalidPhraseException) {
            mutable.value = s.copy(restoreProblem = e.problem)
            return
        }
        mutable.value = s.copy(saving = true, restoreProblem = null, saveProblem = null)
        viewModelScope.launch(io) {
            try {
                firstRun.complete(phrase, s.name.trim(), s.phone.trim())
                mutable.update { it.copy(step = Step.DONE, restoreWords = Answer(""), saving = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(saving = false, saveProblem = SaveProblem.COULD_NOT_SAVE) }
            }
        }
    }

    /** "I've written it down": asks for three of the words. */
    fun phraseWritten() {
        val s = mutable.value
        if (s.step != Step.PHRASE || s.phrase == null) return
        mutable.value = s.copy(
            step = Step.CONFIRM,
            positions = OnboardingRules.confirmPositions(random),
            answers = List(3) { Answer("") },
            mismatch = false,
            saveProblem = null,
        )
    }

    fun showWordsAgain() {
        val s = mutable.value
        if (s.step != Step.CONFIRM) return
        mutable.value = s.copy(step = Step.PHRASE, positions = emptyList(), answers = emptyList(), mismatch = false, saveProblem = null)
    }

    /** Back inside the flow. Returns false on the first step, where the system should handle back. */
    fun back(): Boolean = when (mutable.value.step) {
        Step.WELCOME, Step.DONE -> false
        Step.PHRASE -> true.also { mutable.update { it.copy(step = Step.WELCOME) } }
        Step.CONFIRM -> true.also { showWordsAgain() }
        Step.RESTORE -> true.also { mutable.update { it.copy(step = Step.WELCOME, restoreWords = Answer(""), restoreProblem = null, saveProblem = null) } }
    }

    fun onAnswerChange(index: Int, text: String) {
        mutable.update { s ->
            if (s.step != Step.CONFIRM || index !in s.answers.indices) s
            else s.copy(answers = s.answers.mapIndexed { i, a -> if (i == index) Answer(text) else a }, mismatch = false, saveProblem = null)
        }
    }

    /** Checks the three words; if they are right, saves and finishes. Does nothing unless every answer is filled in. */
    fun finish() {
        val s = mutable.value
        if (!s.canFinish) return
        val phrase = s.phrase ?: return
        val words = phrase.words()
        if (!s.positions.zip(s.answers).all { (position, answer) -> OnboardingRules.sameWord(answer.text, words[position - 1]) }) {
            mutable.value = s.copy(mismatch = true)
            return
        }
        mutable.value = s.copy(saving = true, mismatch = false, saveProblem = null)
        viewModelScope.launch(io) {
            try {
                firstRun.complete(phrase, s.name.trim(), s.phone.trim())
                // Done: forget the phrase and the answers.
                mutable.update { it.copy(step = Step.DONE, phrase = null, positions = emptyList(), answers = emptyList(), saving = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(saving = false, saveProblem = SaveProblem.COULD_NOT_SAVE) }
            }
        }
    }
}
