package owntally.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Secrets must never reach a log (docs/CRYPTO.md section 8). The Android modules write no logs and print nothing at all. */
class NoLoggingTest {
    @Test
    fun theAppModulesNeverLogOrPrint() {
        val roots = listOf("src/main", "../ui/src/main", "../data/src/main").map(::File)
        for (r in roots) assertTrue(r.walkTopDown().any { it.extension == "kt" }, "no sources found in $r")
        val forbidden = Regex("""\bprintln\s*\(|\bprint\s*\(|System\.(out|err)|printStackTrace|java\.util\.logging|\bLogger\b|android\.util\.Log|\bTimber\b|\bLog\.[a-z]\(|slf4j""")
        val offenders = roots.flatMap { r ->
            r.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.flatMap { f -> f.readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" } }
        assertEquals(emptyList(), offenders)
    }
}
