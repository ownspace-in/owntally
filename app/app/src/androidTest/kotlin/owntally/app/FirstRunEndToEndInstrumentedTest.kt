package owntally.app

import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import owntally.data.db.OwnTallyDatabase
import owntally.data.repo.IdentityState
import owntally.data.secrets.KeystoreSecretBox

/**
 * The real app on a real (emulated) phone: the real activity, the Keystore, the secret files and the database. Runs the whole
 * first run through the screens, then looks in every file the app owns for the recovery phrase.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunEndToEndInstrumentedTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun wipe() {
        context.deleteDatabase(OwnTallyDatabase.FILE_NAME)
        File(context.noBackupFilesDir, "secrets").deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KeystoreSecretBox.DEFAULT_ALIAS)
    }

    private fun appFiles(): List<File> = listOfNotNull(context.filesDir, context.noBackupFilesDir, context.cacheDir, context.codeCacheDir, File(context.applicationInfo.dataDir))
        .flatMap { it.walkTopDown().filter { f -> f.isFile && !f.path.contains("/code_cache/") || f.path.endsWith(".db") } }.distinct()

    /** Adjacent word pairs written the ways a phrase could be serialised: with a space, comma, quotes or a newline. */
    private fun leaks(words: List<String>, bytes: ByteArray): List<String> {
        val text = String(bytes, Charsets.ISO_8859_1)
        return words.zipWithNext().flatMap { (a, b) -> listOf("$a $b", "$a,$b", "$a, $b", "$a\",\"$b", "$a\n$b", "$a$b") }.filter { it in text }
    }

    @Test
    fun theWholeFirstRunWorksAndTheRecoveryPhraseIsStoredNowhere() {
        wipe()
        try {
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            fun secure() = run {
                var flags = 0
                scenario.onActivity { flags = it.window.attributes.flags }
                flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            }

            // Welcome: not secure.
            rule.waitUntil(10_000) { rule.onAllNodesWithTextCount("Split expenses.\nKeep your data.") == 1 }
            assertTrue(!secure(), "the welcome screen may be captured")
            rule.onNodeWithText("For example, Asha").performTextInput("Asha")
            rule.onNodeWithText("+91 98765 43210").performTextInput("98765 43210")
            rule.onNodeWithText("Continue").performClick()

            // Phrase: secure. Read the twelve words from the screen like a person would.
            rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Save your recovery phrase") == 1 }
            val words = (1..12).map { n ->
                val d = rule.onNode(hasContentDescription("Word $n: ", substring = true)).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
                d.removePrefix("Word $n: ")
            }
            assertEquals(12, words.size)
            rule.waitForIdle()
            assertTrue(secure(), "the real window is secure while the words are shown")
            rule.onNodeWithText("I've written it down").performClick()

            // Confirm: secure; wrong first, then right.
            rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Check you wrote it down") == 1 }
            assertTrue(secure())
            val positions = (1..12).filter { rule.onAllNodesWithTextCount("Word $it") == 1 }
            assertEquals(3, positions.size)
            positions.forEach { rule.onNodeWithText("Type word $it").performTextInput("qqqq") }
            rule.onNodeWithText("Finish").performClick()
            rule.onNodeWithText("Those words don't match what we showed. Check your paper, or show the words again.").assertExists()
            assertEquals(IdentityState.NOT_CREATED, container().identity.state(), "nothing saved after wrong words")
            positions.forEach { pos -> rule.onAllNodesWithText("qqqq")[0].performTextReplacement(words[pos - 1]) }
            rule.onNodeWithText("Finish").performClick()

            // Done: on the Groups screen, no longer secure, everything saved.
            rule.waitUntil(15_000) { rule.onAllNodesWithTextCount("New group") == 1 }
            rule.waitUntil(3_000) { !secure() }
            assertEquals(IdentityState.READY, container().identity.state())
            assertEquals("Asha", container().identity.defaultProfile()?.name)
            assertEquals("98765 43210", container().identity.defaultProfile()?.phone)

            // The four tabs are there; Sync says it is not built yet and has nothing to press.
            for (label in listOf("Groups", "Settle", "Sync", "About")) rule.onNode(hasContentDescription(label)).assertExists()
            rule.onNode(hasContentDescription("Sync")).performClick()
            rule.onNodeWithText("Syncing with friends arrives in a later update. For now your data is stored only on this phone.").assertExists()
            rule.onNode(hasContentDescription("Groups")).performClick()

            // Make a group, see it listed, open it.
            rule.onNodeWithText("New group").performClick()
            rule.onNodeWithText("Create group").assertIsNotEnabled()
            rule.onNodeWithText("Group name").assertExists()
            rule.onNode(hasSetTextAction()).performTextInput("Goa Trip")
            rule.onNodeWithText("Create group").assertIsEnabled().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTextCount("1 member · 0 expenses") == 1 }
            rule.onNodeWithText("Goa Trip").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTextCount("You are the owner") == 1 }
            rule.onNodeWithText("No expenses yet.").assertExists()
            rule.onNode(hasContentDescription("Back")).performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("1 member · 0 expenses") == 1 }

            // The phrase is in no file: not the secret files, the database, caches or preferences.
            val files = appFiles()
            assertTrue(files.size >= 3, "expected the secret files and the database, found ${files.map { it.name }}")
            for (f in files) assertEquals(emptyList(), leaks(words, f.readBytes()), "the phrase was found in ${f.path}")

            // The scan itself works: a file that does contain the phrase is caught.
            val planted = File(context.cacheDir, "planted.txt").apply { writeText(words.joinToString(" ")) }
            assertTrue(leaks(words, planted.readBytes()).isNotEmpty(), "the scan must find a planted phrase")
            planted.delete()
            scenario.close()
        } finally {
            wipe()
        }
    }

    private fun container() = (context as OwnTallyApplication).container

    private fun ComposeTestRule.onAllNodesWithTextCount(text: String): Int = onAllNodesWithText(text).fetchSemanticsNodes().size
}
