package owntally.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Document
import org.w3c.dom.Element

/** The Android manifest must carry no permission and no backup that M3 does not need (docs/tasks/M3-storage-app-shell.md). */
class ManifestRulesTest {
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun parse(path: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(path))

    private val manifest = parse("src/main/AndroidManifest.xml")
    private val application = manifest.getElementsByTagName("application").item(0) as Element

    @Test
    fun theApplicationIdIsThePermanentOne() {
        assertEquals("in.ownspace.owntally", BuildConfig.APPLICATION_ID)
    }

    /**
     * Owner decision L5 (8 Nov 2026): INTERNET, because a phone cannot open a socket to another phone on the same Wi-Fi without it, and
     * nothing else. It is for syncing with the other members only: `NetworkUseGuardTest` fails the build if any code outside the sync
     * module and its discovery opens a connection. Later milestones add what they need (Nearby's Bluetooth permissions), each with its own test.
     */
    @Test
    fun theOnlyPermissionIsInternetForSyncingWithFriends() {
        val names = (0 until manifest.getElementsByTagName("uses-permission").length)
            .map { (manifest.getElementsByTagName("uses-permission").item(it) as Element).getAttributeNS(ns, "name") }
        assertEquals(listOf("android.permission.INTERNET"), names)
        assertEquals(0, manifest.getElementsByTagName("uses-permission-sdk-23").length)
    }

    @Test
    fun theAppHasAnIconAndAName() {
        assertTrue(application.getAttributeNS(ns, "icon").startsWith("@"), "a launcher icon")
        assertEquals("OwnTally", application.getAttributeNS(ns, "label"))
    }

    /**
     * Owner decision (3 Nov 2026): Android may back up the database and nothing else. It holds only encrypted events, group ids,
     * the key slot numbers and a few settings; the keys are in the Keystore and the no-backup folder, which are never backed up.
     */
    @Test
    fun onlyTheDatabaseIsBackedUp() {
        assertEquals("true", application.getAttributeNS(ns, "allowBackup"))
        assertEquals("@xml/backup_rules", application.getAttributeNS(ns, "fullBackupContent"), "Android 11 and older read this one")
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(ns, "dataExtractionRules"))
        for ((file, sections) in mapOf(
            "src/main/res/xml/data_extraction_rules.xml" to listOf("cloud-backup", "device-transfer"),
            "src/main/res/xml/backup_rules.xml" to listOf("full-backup-content"),
        )) {
            val rules = parse(file)
            for (section in sections) {
                val element = rules.getElementsByTagName(section).item(0) as Element
                val includes = (0 until element.getElementsByTagName("include").length)
                    .map { element.getElementsByTagName("include").item(it) as Element }
                assertEquals(listOf("database"), includes.map { it.getAttribute("domain") }, "$file $section: only the database domain is included")
                assertEquals(listOf("."), includes.map { it.getAttribute("path") }, "$file $section")
                // Nothing else, by name, even if someone adds a broad include later.
                val excluded = (0 until element.getElementsByTagName("exclude").length)
                    .map { (element.getElementsByTagName("exclude").item(it) as Element).getAttribute("domain") }
                assertEquals(setOf("root", "file", "sharedpref", "external"), excluded.toSet(), "$file $section")
            }
        }
    }

    @Test
    fun theSecretsLiveWhereBackupNeverReaches() {
        // noBackupFilesDir is never backed up, and the container puts the secret files there.
        val container = File("src/main/kotlin/owntally/app/AppContainer.kt").readText()
        assertTrue("noBackupFilesDir" in container)
        assertEquals(0, Regex("""\bfilesDir\b|getDir\(|cacheDir|getExternal""").findAll(container).count())
    }

    @Test
    fun onlyTheLauncherActivityIsExported() {
        val activities = manifest.getElementsByTagName("activity")
        assertEquals(1, activities.length)
        val activity = activities.item(0) as Element
        assertEquals("true", activity.getAttributeNS(ns, "exported"))
        assertEquals(1, activity.getElementsByTagName("intent-filter").length)
        assertEquals(0, manifest.getElementsByTagName("service").length + manifest.getElementsByTagName("receiver").length + manifest.getElementsByTagName("provider").length)
    }
}
