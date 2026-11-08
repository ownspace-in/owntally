package owntally.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A member's signing seed leaves the crypto module's hands in one place only: it is given to Iroh as its secret key (decision I2, 8 Nov 2026).
 * Any other file that asks for it fails the build.
 */
class KeySecrecyGuardTest {
    private val allowed = listOf("crypto/src/main/kotlin/owntally/crypto/KeyDerivation.kt", "app/src/main/kotlin/owntally/app/IrohPortImpl.kt")
    private val asks = Regex("""irohSecretKey\(|signingSeed\(""")

    fun offenders(files: List<Pair<String, String>>): List<String> = files.filter { (path, _) -> allowed.none { path == it } }
        .flatMap { (path, text) -> text.lines().withIndex().filter { asks.containsMatchIn(it.value) }.map { "$path:${it.index + 1}: ${it.value.trim()}" } }

    @Test
    fun onlyTheIrohPortAsksForTheSigningSeed() {
        val root = File("..")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path.replace('\\', '/') }
            .map { it.relativeTo(root).path.replace('\\', '/') to it.readText() }.toList()
        assertTrue(files.size > 100, "expected to find the sources, found ${files.size}")
        // The crypto module's own files use it too (signing): the rule is about everything outside it.
        val outside = files.filter { !it.first.startsWith("crypto/") || it.first in allowed }
        assertEquals(emptyList(), offenders(outside))
    }

    @Test
    fun theGuardCatchesWhatItIsFor() {
        assertEquals(1, offenders(listOf("ui/src/main/kotlin/X.kt" to "val s = identity.irohSecretKey()")).size)
        assertEquals(1, offenders(listOf("data/src/main/kotlin/Y.kt" to "identity.signingSeed()")).size)
        assertEquals(0, offenders(listOf("app/src/main/kotlin/owntally/app/IrohPortImpl.kt" to "identity.irohSecretKey()")).size)
    }
}
