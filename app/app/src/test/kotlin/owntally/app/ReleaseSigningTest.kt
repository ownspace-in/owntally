package owntally.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M10, T10.6 (decision S1, Google Play App Signing): the release build is signed with the owner's upload key, which is read from the owner's own Gradle
 * properties or environment and never from the repository. These tests read the build files and the list of files git holds; they cannot see the
 * owner's machine, so they check that no key, keystore or password can get into the repository and that the build reads them from outside.
 */
class ReleaseSigningTest {
    private val build = File("build.gradle.kts").readText()
    private val repoRoot = File("../..")

    private fun trackedFiles(): List<String>? = try {
        val p = ProcessBuilder("git", "ls-files", "-z").directory(repoRoot).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        if (p.waitFor() == 0) out.split('\u0000').filter { it.isNotEmpty() } else null
    } catch (_: Exception) {
        null // no git here (a source archive): the file-content checks below still run
    }

    @Test fun theRepositoryHoldsNoKeystoreOrKeyFile() {
        val files = trackedFiles() ?: return
        val bad = files.filter { f -> Regex("""\.(jks|keystore|p12|pfx|pem|key|der)$""", RegexOption.IGNORE_CASE).containsMatchIn(f) || File(f).name in setOf("keystore.properties", "signing.properties", "upload-key") }
        assertEquals(emptyList(), bad)
    }

    @Test fun noTrackedTextFileHoldsAPrivateKeyOrASigningPassword() {
        val files = trackedFiles() ?: return
        val privateKey = Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----""")
        val passwordValue = Regex("""(storePassword|keyPassword|STORE_PASSWORD|KEY_PASSWORD)\s*[=:]\s*["']?[^\s"'$()]{4,}(?![\w(])""")
        val offenders = files.filter { it.endsWith(".kt") || it.endsWith(".kts") || it.endsWith(".properties") || it.endsWith(".md") || it.endsWith(".yml") || it.endsWith(".yaml") || it.endsWith(".py") || it.endsWith(".toml") || it.endsWith(".sh") }
            .filter { !it.endsWith("ReleaseSigningTest.kt") }
            .flatMap { f ->
                val file = File(repoRoot, f)
                if (!file.isFile) emptyList() else file.readLines().withIndex()
                    .filter { privateKey.containsMatchIn(it.value) || passwordValue.containsMatchIn(it.value) }
                    .map { "$f:${it.index + 1}" }
            }
        assertEquals(emptyList(), offenders)
    }

    @Test fun gitIgnoreKeepsKeysAndSigningPropertiesOut() {
        val ignore = File(repoRoot, ".gitignore").readText().lines().map { it.trim() }
        for (pattern in listOf("*.jks", "*.keystore", "keystore.properties", "*.pem", "*.key")) assertTrue(pattern in ignore, "$pattern is in .gitignore")
    }

    @Test fun theReleaseBuildReadsTheUploadKeyFromGradlePropertiesOrTheEnvironment() {
        for (name in listOf("OWNTALLY_UPLOAD_STORE_FILE", "OWNTALLY_UPLOAD_STORE_PASSWORD", "OWNTALLY_UPLOAD_KEY_ALIAS", "OWNTALLY_UPLOAD_KEY_PASSWORD")) {
            assertTrue("\"$name\"" in build, "$name is read by the build")
        }
        assertTrue("System.getenv" in build && "findProperty" in build, "each setting comes from a Gradle property or the environment")
        val literal = Regex("""(storePassword|keyPassword|storeFile|keyAlias)\s*=\s*"[^"]*"""")
        assertEquals(emptyList(), literal.findAll(build).map { it.value }.toList(), "no signing value is written in the build file")
    }

    @Test fun theReleaseIsSignedWithTheUploadKeyAndNeverWithTheDebugKey() {
        val release = build.substringAfter("buildTypes {").substringBefore("create(\"releaseTest\")")
        assertTrue("signingConfigs.findByName(\"upload\")" in release, "the release build type uses the upload signing configuration")
        assertFalse("getByName(\"debug\")" in release, "the shipped build is never signed with the debug key")
    }

    @Test fun withoutTheKeyTheReleaseStillBuildsUnsignedSoThatCiAndFriendsCanBuildIt() {
        assertTrue("if (uploadKey != null" in build, "the signing configuration exists only when the owner has given a key")
    }

    @Test fun theVersionIsKeptInOnePlace() {
        val props = File("version.properties").readText().lines().filter { it.isNotBlank() && !it.trim().startsWith("#") }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        assertEquals(setOf("VERSION_NAME", "VERSION_CODE"), props.keys)
        assertTrue(Regex("""[1-9]\d*\.\d+\.\d+""").matches(props.getValue("VERSION_NAME")), "the first release is 1.0.0 or later: ${props["VERSION_NAME"]}")
        assertTrue((props.getValue("VERSION_CODE").toIntOrNull() ?: 0) >= 1, "VERSION_CODE is a whole number; Play needs it to rise with every upload")
        assertFalse(Regex("""versionCode\s*=\s*\d""").containsMatchIn(build) || Regex("""versionName\s*=\s*"""").containsMatchIn(build), "build.gradle.kts reads the version from version.properties")
        assertEquals(props.getValue("VERSION_NAME"), BuildConfig.VERSION_NAME)
        assertEquals(props.getValue("VERSION_CODE").toInt(), BuildConfig.VERSION_CODE)
    }

    @Test fun theRunbookForTheOwnerExistsAndNamesEveryStep() {
        val runbook = File(repoRoot, "docs/RELEASE.md").readText()
        for (needle in listOf("keytool -genkeypair", "OWNTALLY_UPLOAD_STORE_FILE", "bundleRelease", "App signing", "assetlinks.py make", "two fingerprints", "VERSION_CODE", "OWNTALLY_RELEASE=1", "back up")) {
            assertTrue(needle in runbook, "docs/RELEASE.md mentions: $needle")
        }
    }
}
