package owntally.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the app says it can do is what it can do. Since M11 restoring from the recovery phrase works (it was "not available yet" under owner decision X1
 * in M10): a sentence that says otherwise fails here, and the screens must say how to do it. There is still no scanner in the app (decision L4), and
 * screenshots are not blocked (owner decision, 4 Nov 2026): a sentence that promises otherwise fails here.
 */
class PromisesTest {
    private val sources = File("src/main/kotlin/owntally/ui").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private val text = sources.joinToString("\n") { it.readText() }

    private val promises = listOf(
        Regex("""restor\w*[^"\n]{0,80}not available""", RegexOption.IGNORE_CASE) to "restoring is not available (it is, since M11)",
        Regex("""restor\w*[^"\n]{0,60}(later|coming|soon|next) update""", RegexOption.IGNORE_CASE) to "restoring comes later (it is here)",
        Regex("""restores? (your|all) (groups|expenses|data|history)\b""", RegexOption.IGNORE_CASE) to "the phrase restores your data (it restores your identity; the history comes from the other members)",
        Regex("""screenshots? (are|is) blocked""", RegexOption.IGNORE_CASE) to "screenshots are blocked",
        Regex("""scan this with OwnTally""", RegexOption.IGNORE_CASE) to "the app scans a QR code",
        Regex("""scan(ner)? (it|this|the code) in the app""", RegexOption.IGNORE_CASE) to "the app scans a QR code",
    )

    @Test fun noSentenceOnAScreenPromisesWhatTheAppCannotDo() {
        val offenders = promises.filter { (re, _) -> re.containsMatchIn(text) }.map { it.second }
        assertEquals(emptyList(), offenders)
    }

    @Test fun theRecoveryPhraseScreenSaysWhatThePhraseIsAndHowToUseItOnANewPhone() {
        val body = Regex("""PHRASE_BODY = "((?:[^"\\]|\\.)*)"""").find(text)!!.groupValues[1]
        assertTrue("your identity in every group" in body && "I already have a recovery phrase" in body && "not available" !in body, body)
        val warning = Regex("""PHRASE_WARNING = "([^"]+)"""").find(text)!!.groupValues[1]
        assertTrue("on paper" in warning && "act as you in every group" in warning && "screenshot" in warning.lowercase() && "blocked" !in warning, warning)
    }

    @Test fun theWelcomeScreenOffersRestoreAndTheRestoreScreenSaysWhatComesBack() {
        assertTrue("""HAVE_PHRASE = "I already have a recovery phrase"""" in text)
        val body = Regex("""RESTORE_BODY = "([^"]+)"""").find(text)!!.groupValues[1]
        assertTrue("identity" in body && "invitation" in body && "same member" in body && "without anyone approving" in body && "history syncs" in body, body)
        assertTrue("expenses" !in body.lowercase().replace("history", ""), "the phrase brings back an identity, not the data: $body")
    }

    @Test fun theScreenThatCannotOpenTheIdentityTellsHowToGetItBackWithThePhrase() {
        val message = Regex("""can't be opened on this phone",\s*"((?:[^"\\]|\\.)*)"""").find(text)!!.groupValues[1]
        assertTrue("Clear data" in message && "I already have a recovery phrase" in message && "not available" !in message, message)
    }

    @Test fun theAboutNoteSaysWhatTheyCanDoWithAndWithoutThePhrase() {
        val note = Regex("""Worth knowing", "([^"]+)"""").find(text)!!.groupValues[1]
        assertTrue("recovery phrase" in note && "same member" in note && "no approval" in note && "not available" !in note, note)
        assertTrue("new member" in note, "without the phrase they join as a new member: $note")
    }
}
