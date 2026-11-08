package owntally.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The recovery phrase must not reach saved state or logs (docs/tasks/M3-storage-app-shell.md, T3.7). */
class NoPhraseLeaksTest {
    @Test
    fun nothingInTheUiModuleSavesStateOrLogs() {
        val sources = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Clipboard.kt" }.toList() // Clipboard.kt marks a copied invite sensitive (a bundle that is not saved state)
        assertTrue(sources.size >= 3, "expected to find the sources, found ${sources.size}")
        val forbidden = Regex("""rememberSaveable|SavedStateHandle|onSaveInstanceState|Bundle|SharedPreferences|DataStore|android\.util\.Log|\bLog\.[a-z]\(|println\s*\(|printStackTrace""")
        val offenders = sources.flatMap { f -> f.readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" } }
        assertEquals(emptyList(), offenders)
    }
}
