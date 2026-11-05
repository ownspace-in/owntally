package owntally.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Library versions that several modules share live in one place, gradle/libs.versions.toml (clarification C9). */
class DependencyRulesTest {
    private val root = File("../..")
    private val buildFiles = listOf("core-model", "crypto", "sync", "data", "ui", "app").map { File(root, "app/$it/build.gradle.kts") }
    private val catalog = File(root, "gradle/libs.versions.toml").readText()

    @Test
    fun theBuildFilesAreFound() {
        assertTrue(buildFiles.all { it.isFile }, "expected every module's build file: ${buildFiles.filterNot { it.isFile }}")
        assertTrue(File(root, "gradle/libs.versions.toml").isFile)
    }

    @Test
    fun noModuleWritesATinkVersionItself() {
        val offenders = buildFiles.flatMap { f -> f.readLines().filter { Regex("""com\.google\.crypto\.tink:tink(-android)?:\d""").containsMatchIn(it) }.map { "${f.name}: ${it.trim()}" } }
        assertEquals(emptyList(), offenders, "use libs.tink and libs.tink.android (gradle/libs.versions.toml)")
    }

    @Test
    fun theCatalogHoldsOneTinkVersionUsedByBothArtifacts() {
        assertEquals(1, Regex("""(?m)^tink\s*=\s*"[^"]+"""").findAll(catalog).count())
        assertTrue(Regex("""tink\s*=\s*\{\s*module\s*=\s*"com\.google\.crypto\.tink:tink",\s*version\.ref\s*=\s*"tink"\s*\}""").containsMatchIn(catalog))
        assertTrue(Regex("""tink-android\s*=\s*\{\s*module\s*=\s*"com\.google\.crypto\.tink:tink-android",\s*version\.ref\s*=\s*"tink"\s*\}""").containsMatchIn(catalog))
    }

    @Test
    fun theVersionIsAnExactNumberNotARange() {
        val version = Regex("""(?m)^tink\s*=\s*"([^"]+)"""").find(catalog)!!.groupValues[1]
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(version), "pinned exactly, got $version")
    }

    /** The build plugins that must move together. A bot that bumps one of them alone breaks the whole build (it did once, on 5 Nov 2026). */
    private val rootBuild = File(root, "build.gradle.kts").readText()

    /** Every plugin line of the root build file as (name, version), whether written id("...") or kotlin("..."). */
    private val plugins: List<Pair<String, String>> =
        Regex("""(?:id\("([\w.\-]+)"\)|kotlin\("([\w.\-]+)"\))\s+version\s+"([^"]+)"""").findAll(rootBuild)
            .map { (it.groupValues[1].ifEmpty { "org.jetbrains.kotlin." + it.groupValues[2] }) to it.groupValues[3] }.toList()

    private fun versionsOf(idPrefix: String) = plugins.filter { it.first.startsWith(idPrefix) }

    @Test
    fun theAndroidPluginsAllHaveTheSameVersion() {
        val plugins = versionsOf("com.android.")
        assertTrue(plugins.size >= 2, "expected the application and library plugins, found $plugins")
        assertEquals(1, plugins.map { it.second }.toSet().size, "the Android plugins must move together: $plugins")
    }

    @Test
    fun theKotlinPluginsAllHaveTheSameVersion() {
        val plugins = versionsOf("org.jetbrains.kotlin")
        assertTrue(plugins.size >= 2, "expected the Kotlin plugins, found $plugins")
        assertEquals(1, plugins.map { it.second }.toSet().size, "the Kotlin plugins must move together: $plugins")
    }
}
