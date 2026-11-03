package owntally.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The core must stay plain Kotlin: no Android, no network, no database (AGENTS.md, M1 hand-back checklist). */
class PurityTest {
    private val forbidden = Regex(
        """^\s*import\s+(android\.|androidx\.|java\.net\.|javax\.net\.|java\.sql\.|java\.nio\.channels\.|okhttp3\.|io\.ktor\.|com\.google\.|org\.apache\.http\.|kotlinx\.coroutines\.)""",
    )

    @Test
    fun noMainSourceImportsAndroidNetworkOrDatabaseCode() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size >= 8, "expected to find the module's sources, found ${sources.size}")
        val offenders = sources.flatMap { f -> f.readLines().filter { forbidden.containsMatchIn(it) }.map { "${f.name}: $it" } }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun theModuleDeclaresNoAndroidPluginOrMainDependencies() {
        val build = File("build.gradle.kts").readText()
        assertTrue("com.android" !in build, "no Android plugin")
        val main = build.lineSequence().filter { it.trim().startsWith("implementation(") || it.trim().startsWith("api(") }.toList()
        assertEquals(emptyList(), main, "the main source set needs no libraries")
    }
}
