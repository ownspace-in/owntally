package owntally.ui.onboarding

import kotlin.random.Random
import owntally.crypto.PhraseProblem
import owntally.crypto.RecoveryPhrase

/** [RESTORE]: the person already has a recovery phrase and types it instead of being given a new one (M11). */
enum class Step { WELCOME, PHRASE, CONFIRM, RESTORE, DONE }
enum class NameProblem { EMPTY, TOO_LONG, BAD_CHARACTERS }
enum class PhoneProblem { EMPTY, BAD_CHARACTERS, BAD_LENGTH, TOO_FEW_DIGITS }
enum class SaveProblem { COULD_NOT_SAVE }

/** A word the person typed to confirm their phrase. It prints as redacted, so the state can be logged without leaking words. */
class Answer(val text: String) {
    override fun equals(other: Any?) = other is Answer && text == other.text
    override fun hashCode() = text.hashCode()
    override fun toString() = "Answer([redacted])"
}

/**
 * Everything the first-run screens show. The phrase is only ever held here, in memory (it is [RecoveryPhrase], which prints as
 * redacted); it is never saved with the screen's state, so closing the app mid-way means starting again with a new phrase.
 */
data class OnboardingState(
    val step: Step = Step.WELCOME,
    val name: String = "",
    val phone: String = "",
    val nameProblem: NameProblem? = null,
    val phoneProblem: PhoneProblem? = null,
    val phrase: RecoveryPhrase? = null,
    /** The three positions (1 to 12, ascending) asked for in the confirm step. */
    val positions: List<Int> = emptyList(),
    val answers: List<Answer> = emptyList(),
    val mismatch: Boolean = false,
    val saving: Boolean = false,
    val saveProblem: SaveProblem? = null,
    /** The words typed to restore (M11): redacted like the answers; forgotten once used or left. */
    val restoreWords: Answer = Answer(""),
    /** What is wrong with the typed phrase; it names a position or a count, never a word. */
    val restoreProblem: PhraseProblem? = null,
) {
    val canRestore: Boolean get() = step == Step.RESTORE && !saving && restoreWords.text.isNotBlank()
    val canFinish: Boolean get() = step == Step.CONFIRM && !saving && answers.isNotEmpty() && answers.all { it.text.isNotBlank() }
}

/** What the first-run screens need from the rest of the app. */
interface FirstRun {
    fun newPhrase(): RecoveryPhrase

    /** Saves the seed made from [phrase] and the default profile. May throw if the phone cannot store it. */
    fun complete(phrase: RecoveryPhrase, name: String, phone: String)
}

/** The checks on what is typed, with the same limits as the events the name and phone end up in (docs/PROTOCOL.md section 1). */
object OnboardingRules {
    fun nameProblem(text: String): NameProblem? {
        val t = text.trim()
        return when {
            t.isEmpty() -> NameProblem.EMPTY
            t.length > 40 -> NameProblem.TOO_LONG
            owntally.model.NameText.problem(t) != null -> NameProblem.BAD_CHARACTERS // the core's rule (F4)
            else -> null
        }
    }

    fun phoneProblem(text: String): PhoneProblem? {
        val t = text.trim()
        return when {
            t.isEmpty() -> PhoneProblem.EMPTY
            t.any { it !in '0'..'9' && it !in "+ -()." } -> PhoneProblem.BAD_CHARACTERS
            t.length !in 5..20 -> PhoneProblem.BAD_LENGTH
            t.count { it in '0'..'9' } < 5 -> PhoneProblem.TOO_FEW_DIGITS
            else -> null
        }
    }

    fun sameWord(typed: String, word: String): Boolean = typed.trim().lowercase() == word

    fun confirmPositions(random: Random): List<Int> = (1..12).shuffled(random).take(3).sorted()
}
