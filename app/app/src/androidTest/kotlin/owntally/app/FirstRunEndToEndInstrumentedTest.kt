package owntally.app

import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.test.performSemanticsAction
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
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FirstRunEndToEndInstrumentedTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun wipe() {
        context.deleteDatabase(OwnTallyDatabase.FILE_NAME)
        File(context.noBackupFilesDir, "secrets").deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KeystoreSecretBox.DEFAULT_ALIAS)
        (context as OwnTallyApplication).resetContainerForTests()
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
            rule.waitUntilExactlyOneExists(hasText("Split expenses.\nKeep your data."), 25_000)
            assertTrue(!secure(), "the welcome screen may be captured")
            rule.onNodeWithText("For example, Asha").performTextInput("Asha")
            rule.onNodeWithText("+91 98765 43210").performTextInput("98765 43210")
            // Pressed through its accessibility action, not by screen coordinates: the buttons at the bottom of the screen move while the keyboard
            // area settles, and a coordinate tap that lands during that move can hit the link under the button instead.
            rule.waitForIdle()
            rule.onNodeWithText("Continue").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)

            // Phrase: not secure either. Read the twelve words from the screen like a person would.
            rule.waitUntilExactlyOneExists(hasText("Save your recovery phrase"), 5_000)
            val words = (1..12).map { n ->
                val d = rule.onNode(hasContentDescription("Word $n: ", substring = true)).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
                d.removePrefix("Word $n: ")
            }
            assertEquals(12, words.size)
            rule.waitForIdle()
            assertTrue(!secure(), "screenshots are allowed on every screen (owner decision, 4 Nov 2026)")
            rule.onNodeWithText("I've written it down").performClick()

            // Confirm: wrong first, then right.
            rule.waitUntilExactlyOneExists(hasText("Check you wrote it down"), 5_000)
            assertTrue(!secure())
            val positions = (1..12).filter { rule.onAllNodesWithTextCount("Word $it") == 1 }
            assertEquals(3, positions.size)
            positions.forEach { rule.onNodeWithText("Type word $it").performTextInput("qqqq") }
            rule.onNodeWithText("Finish").performClick()
            rule.onNodeWithText("Those words don't match what we showed. Check your paper, or show the words again.").assertExists()
            assertEquals(IdentityState.NOT_CREATED, container().identity.state(), "nothing saved after wrong words")
            positions.forEach { pos -> rule.onAllNodesWithText("qqqq")[0].performTextReplacement(words[pos - 1]) }
            rule.onNodeWithText("Finish").performClick()

            // Done: on the Groups screen, everything saved.
            rule.waitUntilExactlyOneExists(hasText("New group"), 15_000)
            assertEquals(IdentityState.READY, container().identity.state())
            assertEquals("Asha", container().identity.defaultProfile()?.name)
            assertEquals("98765 43210", container().identity.defaultProfile()?.phone)

            // The four tabs are there; Sync says it is not built yet and has nothing to press.
            for (label in listOf("Groups", "Settle", "Sync", "About")) rule.onNode(hasContentDescription(label)).assertExists()
            rule.onNode(hasContentDescription("Sync")).performClick()
            rule.onNodeWithText("Choose a group to see who it has reached.").assertExists()
            rule.onNodeWithText("No groups yet.").assertExists()
            rule.onNode(hasContentDescription("Groups")).performClick()

            // Make a group, see it listed, open it.
            rule.onNodeWithText("New group").performClick()
            rule.onNodeWithText("Create group").assertIsNotEnabled()
            rule.onNodeWithText("Group name").assertExists()
            rule.onNode(hasSetTextAction()).performTextInput("Goa Trip")
            rule.onNodeWithText("Create group").assertIsEnabled().performClick()
            rule.waitUntilExactlyOneExists(hasText("1 member · 0 expenses"), 25_000)
            rule.onNodeWithText("Goa Trip").performClick()
            rule.waitUntilExactlyOneExists(hasText("You are the owner"), 25_000)
            rule.onNodeWithText("No expenses yet.").assertExists()

            // Add an expense: blocked until valid, then saved and listed, then deleted with a confirmation.
            rule.onNode(hasContentDescription("Add expense")).performClick()
            rule.waitUntilExactlyOneExists(hasText("Save expense"), 25_000)
            rule.onNodeWithText("Save expense").assertIsNotEnabled()
            rule.onNode(hasContentDescription("Amount") and hasSetTextAction()).performTextInput("2400")
            rule.onNode(hasContentDescription("What was it for?") and hasSetTextAction()).performTextInput("Seafood lunch")
            rule.waitForIdle()
            // Pressed through its accessibility action, not by screen coordinates: the bottom bar moves while the keyboard area settles, and a
            // coordinate tap that lands during that move can miss the button (seen once in about ten runs).
            rule.onNodeWithText("Save expense").assertIsEnabled().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            try {
                rule.waitUntilExactlyOneExists(hasText("You paid ₹2,400 · split 1 way"), 25_000)
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("screen at the timeout: " + rule.onRoot().printToString(10000), e)
            }
            rule.onNodeWithText("Seafood lunch").performClick()
            rule.onNodeWithText("Delete").performClick()
            rule.onNodeWithText("It stays in the list, struck through, and is no longer counted in the balances.").assertExists()
            rule.onNodeWithText("Delete expense").performClick()
            rule.waitUntilExactlyOneExists(hasText("Deleted by Asha · not counted"), 25_000)

            // Test friends (debug build only), then Settle up shows the plan, with the buttons of M8 (I paid, Copy number, Call for what I owe).
            rule.onNodeWithText("Add test friends and expenses (debug build)").performClick()
            rule.waitUntilExactlyOneExists(hasText("Meera (test) is owed"), 15_000)
            rule.onNodeWithText("Settle up the group").performClick()
            rule.waitUntilExactlyOneExists(hasText("You → Meera (test)"), 25_000)
            rule.onNodeWithText("₹2,850").assertExists()
            rule.onNodeWithText("Ben (test) → Meera (test)").assertExists()
            rule.onNodeWithText("₹9,550").assertExists()
            rule.onNodeWithText("I paid").assertExists()
            rule.onNodeWithText("Copy number").assertExists()
            rule.onNodeWithText("Call").assertExists()
            rule.onNodeWithText(SETTLE_NOTE).assertExists()
            rule.onNode(hasContentDescription("Back")).performClick()
            rule.waitUntilExactlyOneExists(hasText("Settle up the group"), 5_000)
            rule.onNode(hasContentDescription("Back")).performClick()
            rule.waitUntilExactlyOneExists(hasText("3 members · 3 expenses"), 5_000)
            rule.onNode(hasContentDescription("About")).performClick()
            rule.onNodeWithText("How OwnTally works").performClick()
            rule.onNodeWithText("Everything lives on your phone").assertExists()
            rule.onNodeWithText("Settling is a handoff").assertExists()
            rule.onNode(hasContentDescription("Back")).performClick()
            // Privacy: who runs the relay and what n0 learns, in the app (the same text as the web page).
            rule.onNodeWithText("Privacy").performClick()
            rule.waitUntilExactlyOneExists(hasText("Where your data lives"), 5_000)
            rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText("you rejoin each group as the same member, with no approval", substring = true))
            rule.onNodeWithText("you rejoin each group as the same member, with no approval", substring = true).assertExists()
            rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasContentDescription("Back"))
            rule.onNode(hasContentDescription("Back")).performClick()
            rule.onNode(hasContentDescription("Groups")).performClick()

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

    private companion object {
        const val SETTLE_NOTE = "If you owe someone, you'll see I paid here, with their number to copy or call. The plan updates if an expense is added or deleted."
    }

    private fun container() = (context as OwnTallyApplication).container

    private fun ComposeTestRule.onAllNodesWithTextCount(text: String): Int = onAllNodesWithText(text).fetchSemanticsNodes().size
}
