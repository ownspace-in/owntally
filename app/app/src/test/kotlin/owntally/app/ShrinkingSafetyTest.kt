package owntally.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M10, T10.2: the shipped build shrinks and renames our own code too, which is only safe if nothing in it looks up a class, method or field by name.
 * The build type the emulator tests run against keeps our code (docs/tasks/M10-hardening-release.md), so this test is what makes that sound: our
 * sources use no reflection, no class loading by name and no serialization that depends on names. If a rule below has to be relaxed, add a keep rule
 * in app/app/proguard-rules.pro for exactly that class and say why.
 */
class ShrinkingSafetyTest {
    private val roots = listOf("src/main", "../ui/src/main", "../data/src/main", "../sync/src/main", "../crypto/src/main", "../core-model/src/main").map(::File)

    private val byName = Regex(
        """Class\.forName|\.loadClass\(|\.getDeclared(Field|Method|Constructor|Class)|\.getMethod\(|\.getField\(|\.getConstructor\(|java\.lang\.reflect|kotlin\.reflect|::class\.(members|memberProperties|declaredMemberProperties)|\.javaClass\.(getDeclared|getMethod|getField)|ServiceLoader|Proxy\.newProxyInstance|setAccessible|@SerializedName|kotlinx\.serialization|Gson|Moshi|Jackson""",
    )

    @Test fun ourSourcesLookNothingUpByName() {
        for (r in roots) assertTrue(r.walkTopDown().any { it.extension == "kt" }, "no sources found in $r")
        val offenders = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { f -> f.readLines().withIndex().filter { byName.containsMatchIn(it.value) && !it.value.trim().startsWith("//") && !it.value.trim().startsWith("*") }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" } }
        assertEquals(emptyList(), offenders)
    }

    @Test fun theOnlyClassesTheSystemOrAGeneratedFileNamesAreTheManifestsAndTheyAreKeptByTheBuildTools() {
        // Components named in the manifest are kept by AGP itself; nothing else may be named in an XML file or a string for the system to load.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val named = Regex("""android:name="(\.?[A-Za-z0-9_.$]+)"""").findAll(manifest).map { it.groupValues[1] }.filter { !it.startsWith("android.") && !it.startsWith("androidx.") }.toSet()
        assertEquals(setOf(".OwnTallyApplication", ".MainActivity"), named, "components of this app named in the manifest (the build tools keep these)")
    }

    @Test fun theKeepRulesOfTheShippedBuildNameOnlyWhatNeedsThem() {
        val rules = File("proguard-rules.pro").readText().lines().filter { it.isNotBlank() && !it.trim().startsWith("#") }
        val keeps = rules.filter { it.startsWith("-keep") }
        assertEquals(listOf("-keep class com.sun.jna.** { *; }", "-keep class computer.iroh.** { *; }"), keeps, "JNA and the Iroh bindings are the only things kept: they are reached by name from native code")
        assertTrue(rules.none { "owntally" in it }, "none of our own code is kept in the shipped build")
        val testRules = File("proguard-releasetest-rules.pro").readText()
        assertTrue("-keep class owntally.** { *; }" in testRules, "the test build type keeps our code (so the tests' hooks stay); the shipped build does not")
    }
}
