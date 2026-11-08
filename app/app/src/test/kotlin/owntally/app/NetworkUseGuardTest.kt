package owntally.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app holds the INTERNET permission only to sync with the other members of a group (owner decision L5, 8 Nov 2026; AGENTS.md: no network
 * call that is not sync, no analytics). This is what keeps it so: only the sync module and the one file that finds phones on the same
 * network may use the network, and no library that reports to anyone may be a dependency.
 */
class NetworkUseGuardTest {
    private val network = Regex(
        """java\.net\.(Socket|ServerSocket|DatagramSocket|MulticastSocket|URL|URLConnection|HttpURLConnection|http)\b|javax\.net\.|java\.nio\.channels|\bSocket\b|HttpsURLConnection|\bURL\(|okhttp3|retrofit|io\.ktor|""" +
            """android\.net\.(nsd|wifi|http)|NsdManager|WifiManager|ConnectivityManager|WebView|android\.webkit|InetSocketAddress|InetAddress\.getBy""",
    )
    private val libraries = Regex("""okhttp|retrofit|ktor|firebase|crashlytics|analytics|mlkit|play-services|sentry|datadog|segment|amplitude|mixpanel|appsflyer|adjust""", RegexOption.IGNORE_CASE)

    /** What may use the network, and why. A path here is relative to the project's `app` folder. */
    private val mayUseNetwork = listOf(
        "sync/src/main/" to "the sync module: the transports",
        "app/src/main/kotlin/owntally/app/NsdLocalDiscovery.kt" to "finds the other phones of a group on the same network (decision L1)",
    )
    private val mayDependOnNetworkLibraries = listOf("sync/build.gradle.kts" to "the WebSocket transport of the relay (OkHttp 4.12, decision W1)")

    /** The one Play services library allowed: Nearby Connections (same-room sync). It brings no reporting library, which the next test checks. */
    private val allowedLibraryLines = listOf("play-services-nearby")

    private fun sources(root: File) = root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") && "/src/main/" in it.path.replace('\\', '/') }.toList()

    /** The files, as (path relative to the app folder, text), that use the network and are not allowed to. */
    fun offenders(files: List<Pair<String, String>>): List<String> = files
        .filter { (path, _) -> mayUseNetwork.none { (allowed, _) -> path.startsWith(allowed) } }
        .flatMap { (path, text) -> text.lines().withIndex().filter { network.containsMatchIn(it.value) }.map { "$path:${it.index + 1}: ${it.value.trim()}" } }

    fun libraryOffenders(files: List<Pair<String, String>>): List<String> = files
        .filter { (path, _) -> mayDependOnNetworkLibraries.none { (allowed, _) -> path == allowed } }
        .flatMap { (path, text) -> text.lines().withIndex().filter { libraries.containsMatchIn(it.value) && !it.value.trim().startsWith("//") && allowedLibraryLines.none { ok -> ok in it.value } }.map { "$path:${it.index + 1}: ${it.value.trim()}" } }

    private val appFolder = File("..")

    @Test
    fun noCodeOutsideSyncOpensAConnection() {
        val files = sources(appFolder).map { it.relativeTo(appFolder).path.replace('\\', '/') to it.readText() }
        assertTrue(files.size > 100, "expected to find the sources, found ${files.size}")
        assertEquals(emptyList(), offenders(files))
    }

    @Test
    fun noLibraryThatReportsToAnyoneIsADependency() {
        val builds = appFolder.walkTopDown().filter { it.name == "build.gradle.kts" && "/build/" !in it.path.replace('\\', '/') }.toList()
            .map { it.relativeTo(appFolder).path.replace('\\', '/') to it.readText() }
        assertTrue(builds.size >= 6, "expected the module build files, found ${builds.size}")
        assertEquals(emptyList(), libraryOffenders(builds))
        assertEquals(emptyList(), libraryOffenders(listOf("../gradle/libs.versions.toml" to File("../../gradle/libs.versions.toml").readText())))
    }

    @Test
    fun theGuardItselfCatchesWhatItIsFor() {
        assertEquals(1, offenders(listOf("ui/src/main/kotlin/X.kt" to "val s = java.net.Socket(host, 80)")).size)
        assertEquals(1, offenders(listOf("ui/src/main/kotlin/X2.kt" to "val c = java.nio.channels.SocketChannel.open()")).size)
        assertEquals(1, offenders(listOf("data/src/main/kotlin/X3.kt" to "val s = Socket()")).size)
        assertEquals(1, offenders(listOf("data/src/main/kotlin/Y.kt" to "val c = URL(\"https://example.com\").openConnection()")).size)
        assertEquals(1, offenders(listOf("ui/src/main/kotlin/Z.kt" to "import okhttp3.OkHttpClient")).size)
        assertEquals(1, offenders(listOf("app/src/main/kotlin/owntally/app/Other.kt" to "val m = getSystemService(NsdManager::class.java)")).size)
        assertEquals(0, offenders(listOf("sync/src/main/kotlin/owntally/sync/T.kt" to "val s = java.net.Socket()")).size, "the sync module may")
        assertEquals(0, offenders(listOf("app/src/main/kotlin/owntally/app/NsdLocalDiscovery.kt" to "NsdManager")).size, "the discovery file may")
        assertEquals(1, libraryOffenders(listOf("ui/build.gradle.kts" to "implementation(\"com.google.firebase:firebase-analytics:1.0\")")).size)
        assertEquals(1, libraryOffenders(listOf("data/build.gradle.kts" to "implementation(\"com.google.mlkit:barcode-scanning:17.0.0\")")).size)
        assertEquals(0, libraryOffenders(listOf("app/build.gradle.kts" to "implementation(\"com.google.android.gms:play-services-nearby:19.3.0\")")).size, "Nearby is allowed")
        assertEquals(1, libraryOffenders(listOf("app/build.gradle.kts" to "implementation(\"com.google.android.gms:play-services-measurement:21.0.0\")")).size, "no other Play services library")
        assertEquals(1, libraryOffenders(listOf("app/build.gradle.kts" to "implementation(\"com.google.android.gms:play-services-mlkit-barcode-scanning:18.0.0\")")).size)
        assertEquals(0, libraryOffenders(listOf("sync/build.gradle.kts" to "compileOnly(\"com.squareup.okhttp3:okhttp:5.5.1\")")).size)
    }
}
