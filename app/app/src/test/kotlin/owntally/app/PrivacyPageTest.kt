package owntally.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import owntally.ui.privacy.PrivacyFacts

/**
 * M10, T10.5: the privacy page on the web (`site/privacy/index.html`, which the Play Store asks for) says exactly what the Privacy screen in the app says,
 * because it is built from the same sentences ([PrivacyFacts]). The test builds the page and compares; to rewrite the page after the facts change, run
 * with OWNTALLY_WRITE_PRIVACY_PAGE=1 (`OWNTALLY_WRITE_PRIVACY_PAGE=1 ./gradlew :app:app:testDebugUnitTest --tests '*PrivacyPageTest'`) and commit the result.
 */
class PrivacyPageTest {
    private val pageFile = File("../../site/privacy/index.html")

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    /** The page, from the facts. No script, no outside resources, one style sheet from this site (the site's policy allows nothing else). */
    private fun page(): String = buildString {
        appendLine("<!doctype html>")
        appendLine("<html lang=\"en\">")
        appendLine("<head>")
        appendLine("  <meta charset=\"utf-8\">")
        appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        appendLine("  <title>Privacy - OwnTally</title>")
        appendLine("  <link rel=\"stylesheet\" href=\"/style.css\">")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("  <main>")
        appendLine("    <h1>Privacy</h1>")
        appendLine("    <p>This is what OwnTally does with your information, in plain words. The same text is in the app, under About, Privacy.</p>")
        for (s in PrivacyFacts.sections) {
            appendLine("    <div class=\"card\">")
            appendLine("      <h2>${escape(s.heading)}</h2>")
            for (l in s.lines) appendLine("      <p>${escape(l)}</p>")
            appendLine("    </div>")
        }
        appendLine("    <p class=\"muted\"><a href=\"/\">OwnTally</a></p>")
        appendLine("  </main>")
        appendLine("</body>")
        appendLine("</html>")
    }

    @Test fun thePageIsBuiltFromTheSameSentencesAsTheScreen() {
        if (System.getenv("OWNTALLY_WRITE_PRIVACY_PAGE") == "1") { pageFile.parentFile.mkdirs(); pageFile.writeText(page()) }
        assertEquals(page(), pageFile.readText(), "site/privacy/index.html is out of step with PrivacyFacts (rewrite it with OWNTALLY_WRITE_PRIVACY_PAGE=1)")
    }

    @Test fun everySentenceOfTheScreenIsOnThePage() {
        val html = pageFile.readText()
        for (line in PrivacyFacts.allLines) assertTrue(escape(line) in html, line)
    }

    @Test fun theSiteServesThePageAtItsAddress() {
        val conf = File("../../site/nginx/joinowntally.ownspace.in.conf").readText()
        assertTrue(Regex("""location\s*=\s*/privacy\s*\{[^}]*try_files\s+/privacy/index\.html\s+=404;""").containsMatchIn(conf), "an exact /privacy location, like /j")
        assertEquals("https://joinowntally.ownspace.in/privacy", PrivacyFacts.PAGE)
    }

    @Test fun theFactsNameTheServicesThatAreReallyUsed() {
        val all = PrivacyFacts.allLines.joinToString(" ")
        assertTrue("relay.ownspace.in" in all && "n0" in all && "Iroh" in all && "Nearby Connections" in all, "the relay, n0 (Iroh's discovery) and Google's Nearby are all named")
        assertTrue("public key" in all && "internet address" in all && "when your phone is online" in all, "what n0 learns, as decided in I1")
        assertTrue("cannot read what you send" in all)
        assertFalse(Regex("""\bUPI\b""").containsMatchIn(all), "no payment service is named")
    }

    @Test fun thePageLoadsNothingFromOutsideAndTheOperatorMarksAreTheOnlyPlaceholders() {
        val html = pageFile.readText()
        assertFalse(Regex("""<script|\sstyle\s*=|\son[a-z]+\s*=|<iframe|<form|<img""", RegexOption.IGNORE_CASE).containsMatchIn(html))
        for (m in Regex("""(src|href)\s*=\s*"([^"]*)"""").findAll(html)) assertTrue(m.groupValues[2].startsWith("/"), "from this site only: ${m.groupValues[2]}")
        val marks = Regex("""\[[^\]]*\]""").findAll(html).map { it.value }.toSet()
        assertEquals(setOf(escape(PrivacyFacts.OPERATOR), escape(PrivacyFacts.CONTACT)), marks, "the operator's name and the contact are the only places still to fill in")
    }
}
