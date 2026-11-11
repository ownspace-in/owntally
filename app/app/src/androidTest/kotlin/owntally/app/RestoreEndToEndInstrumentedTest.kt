package owntally.app

import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.waitUntilExactlyOneExists
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
import owntally.crypto.Bip39
import owntally.crypto.KeyDerivation
import owntally.crypto.KeySlots
import owntally.data.db.OwnTallyDatabase
import owntally.data.repo.IdentityState
import owntally.data.secrets.KeystoreSecretBox
import owntally.model.GroupId
import owntally.model.GroupIds

/**
 * M11: the real app restored from a recovery phrase through the real screens (the Keystore, the secret files, the database). A person who has
 * a phrase types it instead of being given one; the identity that results is the one the phrase gives; the phrase is stored nowhere; and a group
 * made afterwards has its key derived from its own id.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RestoreEndToEndInstrumentedTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val phrase = Bip39.fromEntropy(ByteArray(16) { (it * 5 + 1).toByte() })

    private fun wipe() {
        context.deleteDatabase(OwnTallyDatabase.FILE_NAME)
        File(context.noBackupFilesDir, "secrets").deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KeystoreSecretBox.DEFAULT_ALIAS)
        (context as OwnTallyApplication).resetContainerForTests()
    }

    private fun container() = (context as OwnTallyApplication).container

    private fun appFiles(): List<File> = listOfNotNull(context.filesDir, context.noBackupFilesDir, context.cacheDir, File(context.applicationInfo.dataDir))
        .flatMap { it.walkTopDown().filter { f -> f.isFile && !f.path.contains("/code_cache/") || f.path.endsWith(".db") } }.distinct()

    @Test
    fun aPhoneRestoredFromThePhraseHasThePhrasesIdentityAndStoresTheWordsNowhere() {
        wipe()
        try {
            ActivityScenario.launch(MainActivity::class.java)
            rule.waitUntilExactlyOneExists(hasText("Split expenses.\nKeep your data."), 25_000)
            rule.onNodeWithText("For example, Asha").performTextInput("Rohan")
            rule.onNodeWithText("+91 98765 43210").performTextInput("98765 43210")
            rule.onNodeWithText("I already have a recovery phrase").performClick()

            rule.waitUntilExactlyOneExists(hasText("Restore with your recovery phrase"), 5_000)
            // Eleven words first: told how many, nothing saved.
            rule.onNode(hasContentDescription("Your 12 words") and hasSetTextAction()).performTextInput(phrase.words().take(11).joinToString(" "))
            rule.onNodeWithText("Restore").performClick()
            rule.onNodeWithText("Enter all 12 words. You entered 11.").assertExists()
            assertEquals(IdentityState.NOT_CREATED, container().identity.state(), "nothing saved after a wrong phrase")
            // Then the whole phrase, in capitals and over two lines, the way a person might paste it.
            rule.onNode(hasContentDescription("Your 12 words") and hasSetTextAction()).performTextReplacement(phrase.words().take(6).joinToString(" ").uppercase() + "\n" + phrase.words().drop(6).joinToString(" "))
            rule.onNodeWithText("Restore").performClick()

            rule.waitUntilExactlyOneExists(hasText("New group"), 15_000)
            assertEquals(IdentityState.READY, container().identity.state())
            assertEquals("Rohan", container().identity.defaultProfile()?.name)

            // The identity is the phrase's: the same keys as an independent derivation from the same phrase, for any group id.
            val seed = Bip39.seed(phrase)
            val someGroup = GroupId(ByteArray(16) { (it + 3).toByte() })
            assertEquals(
                KeyDerivation.groupIdentity(seed, KeySlots.member(someGroup, 0)).publicKey,
                container().identity.groupIdentity(KeySlots.member(someGroup, 0))!!.publicKey,
            )

            // A group made on the restored phone takes its key from its own id.
            rule.onNodeWithText("New group").performClick()
            rule.onNode(hasSetTextAction()).performTextInput("Goa Trip")
            rule.onNodeWithText("Create group").assertIsEnabled().performClick()
            rule.waitUntilExactlyOneExists(hasText("1 member · 0 expenses"), 25_000)
            val row = container().groups.listGroups().single()
            assertEquals(KeySlots.creator(GroupIds.randomPart(row.groupId)), row.keySlot)
            assertTrue(GroupIds.isCreatedBy(row.groupId, container().identity.groupIdentity(row.keySlot)!!.publicKey))

            // The words are stored nowhere: not in a file the app owns, not in the database.
            val words = phrase.words()
            for (f in appFiles()) {
                val text = String(f.readBytes(), Charsets.ISO_8859_1)
                val leaked = words.zipWithNext().filter { (a, b) -> listOf("$a $b", "$a,$b", "$a\n$b", "$a$b").any { it in text } }
                assertTrue(leaked.isEmpty(), "${f.path} holds words of the phrase: $leaked")
            }
        } finally {
            wipe()
        }
    }
}
