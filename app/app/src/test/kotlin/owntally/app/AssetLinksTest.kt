package owntally.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M9, T9.4: the pieces that make "tap the link, the app opens" line up: the app's id, the site's template and script, the web server's answer for the file, the manifest. */
class AssetLinksTest {
    private val site = File("../../site")

    @Test fun theTemplateAndTheScriptNameTheApplicationIdOfThisBuild() {
        val template = File(site, "assetlinks.template.json").readText()
        assertEquals(BuildConfig.APPLICATION_ID, Regex(""""package_name"\s*:\s*"([^"]+)"""").find(template)!!.groupValues[1])
        val script = File(site, "assetlinks.py").readText()
        assertEquals(BuildConfig.APPLICATION_ID, Regex("""DEFAULT_PACKAGE\s*=\s*"([^"]+)"""").find(script)!!.groupValues[1])
        assertTrue("handle_all_urls" in template, "the relation that lets the app open links")
    }

    @Test fun aRealFileIfOneIsInTheRepoHasNoPlaceholderAndTheTemplateIsNeverServed() {
        val real = File(site, ".well-known/assetlinks.json")
        if (real.exists()) assertFalse("REPLACE" in real.readText(), "the file Android reads still holds the template's placeholder")
        // The deploy command copies the site folder: the template must not become a second, public copy of the file with a placeholder in it.
        val readme = File(site, "README.md").readText()
        assertTrue("--exclude assetlinks.template.json" in readme && "--exclude assetlinks.py" in readme && "--exclude test_assetlinks.py" in readme, "the deploy command leaves the tools off the server")
    }

    @Test fun theWebServerAnswersForTheFileAsJsonWithItsSecurityHeadersAndNoRedirect() {
        val conf = File(site, "nginx/joinowntally.ownspace.in.conf").readText()
        val block = Regex("""location = /\.well-known/assetlinks\.json \{(.*?)\n    \}""", RegexOption.DOT_MATCHES_ALL).find(conf)!!.groupValues[1]
        assertTrue("default_type application/json;" in block)
        assertTrue("try_files \$uri =404;" in block, "served as it is, 404 if absent, never redirected")
        for (h in listOf("X-Content-Type-Options", "Referrer-Policy", "X-Frame-Options", "Content-Security-Policy")) assertTrue(h in block, "$h is repeated where the location sets its own headers")
        assertFalse(Regex("""return\s+30\d|rewrite\s""").containsMatchIn(block))
    }

    @Test fun theHostInTheManifestIsTheHostTheSiteServesAndTheFileIsAskedFor() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val host = Regex("""android:host="([^"]+)"""").find(manifest)!!.groupValues[1]
        assertEquals(owntally.data.session.InviteLinks.HOST, host)
        assertTrue("server_name $host;" in File(site, "nginx/joinowntally.ownspace.in.conf").readText())
        assertTrue("android:autoVerify=\"true\"" in manifest)
    }
}
