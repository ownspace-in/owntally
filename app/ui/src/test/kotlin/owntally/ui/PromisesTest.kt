package owntally.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M10, T10.5: what the app says it can do is what it can do. Restoring from the recovery phrase is M11 (owner decision X1), there is no scanner in
 * the app (decision L4), and screenshots are not blocked (owner decision, 4 Nov 2026). A sentence in the screens that promises otherwise fails here.
 */
class PromisesTest {
    private val sources = File("src/main/kotlin/owntally/ui").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private val text = sources.joinToString("\n") { it.readText() }

    private val promises = listOf(
        Regex("""restore (your|them|it|everything)[^"\n]{0,40}(on|to|from)[^"\n]{0,30}(new|another) phone""", RegexOption.IGNORE_CASE) to "restoring works on a new phone",
        Regex("""send your groups back""", RegexOption.IGNORE_CASE) to "other members send your groups back (restore)",
        Regex("""only backup of who you are""", RegexOption.IGNORE_CASE) to "the phrase is a backup that can be restored",
        Regex("""screenshots? (are|is) blocked""", RegexOption.IGNORE_CASE) to "screenshots are blocked",
        Regex("""scan this with OwnTally""", RegexOption.IGNORE_CASE) to "the app scans a QR code",
        Regex("""scan(ner)? (it|this|the code) in the app""", RegexOption.IGNORE_CASE) to "the app scans a QR code",
    )

    @Test fun noSentenceOnAScreenPromisesWhatTheAppCannotDo() {
        val offenders = promises.filter { (re, _) -> re.containsMatchIn(text) }.map { it.second }
        assertEquals(emptyList(), offenders)
    }

    @Test fun theRecoveryPhraseScreenSaysWhatThePhraseIsAndThatRestoringIsNotThereYet() {
        val body = Regex("""PHRASE_BODY = "([^"]+)"""").find(text)!!.groupValues[1]
        assertTrue("your identity in every group" in body && "not available yet" in body, body)
        val warning = Regex("""PHRASE_WARNING = "([^"]+)"""").find(text)!!.groupValues[1]
        assertTrue("on paper" in warning && "act as you in every group" in warning && "screenshot" in warning.lowercase() && "blocked" !in warning, warning)
    }

    @Test fun theScreenThatCannotOpenTheIdentityAndTheAboutNoteSayRestoreIsNotAvailable() {
        assertTrue("Restoring from your recovery phrase is not available yet." in text)
        assertTrue("restoring from it on a new phone is not available yet" in text)
    }
}
