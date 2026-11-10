package owntally.app

import java.io.File
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import owntally.crypto.GroupKey
import owntally.crypto.Ticket
import owntally.data.session.Invites
import owntally.model.GroupId
import owntally.model.PublicKey

/**
 * M9, T9.2: the invite page (`site/`) against what the app really writes. The page runs on strangers' phones and in browsers, so it must show
 * the code exactly as the app made it, never run what a hostile link carries, and load nothing from anywhere else (the site's own policy forbids it).
 */
class InviteSiteTest {
    private val site = File("../../site")
    private val page = File(site, "j/index.html").readText()
    private val script = File(site, "j/invite.js")
    private val random = SecureRandom()

    private fun ticket(hint: String = ""): Ticket = Ticket(
        ByteArray(Ticket.ID_SIZE).also { random.nextBytes(it) }, PublicKey(ByteArray(32).also { random.nextBytes(it) }),
        GroupId(ByteArray(16).also { random.nextBytes(it) }), 1, GroupKey(ByteArray(32).also { random.nextBytes(it) }), 1_900_000_000, hint,
    )

    // ---- what the script shows (run under Node, as a browser would) --------------------------------------------------------------

    /** Runs the page script once for each fragment; returns what each left in the code box and whether it showed the Copy button. */
    private fun runPage(hashes: List<String>): List<Pair<String, Boolean>> {
        assumeTrue("Node is needed to run the page script", ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0)
        val harness = """
            const vm = require('vm'), fs = require('fs');
            const src = fs.readFileSync(process.argv[1], 'utf8');
            const out = [];
            for (const hash of JSON.parse(process.argv[2])) {
              const box = { textContent: '', focus() {} };
              Object.defineProperty(box, 'innerHTML', { set() { throw new Error('innerHTML must never be used'); } });
              const copy = { hidden: true, addEventListener() {} };
              const ctx = { location: { hash }, document: { getElementById: id => ({ code: box, copy, copied: { hidden: true } })[id], createRange() { return { selectNodeContents() {} }; } }, navigator: {}, window: { getSelection() { return { removeAllRanges() {}, addRange() {} }; } }, decodeURIComponent };
              vm.runInNewContext(src, ctx);
              out.push((copy.hidden ? '0 ' : '1 ') + Buffer.from(box.textContent, 'utf8').toString('base64'));
            }
            console.log(out.join('\n'));
        """.trimIndent()
        val json = hashes.joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        val p = ProcessBuilder("node", "-e", harness, script.absolutePath, json).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor(), "the page script failed: $text")
        return text.trim().lines().takeLast(hashes.size).map { line ->
            val (flag, b64) = line.split(" ", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8) to (flag == "1")
        }
    }

    @Test
    fun theScriptShowsExactlyTheCodeTheAppPutsInTheLinkAndTheAppReadsItBack() {
        val tickets = List(25) { ticket(if (it % 5 == 0) "relay.example" else "") }
        val shown = runPage(tickets.map { "#" + it.encode() })
        for ((t, s) in tickets.zip(shown)) {
            assertEquals(t.encode(), s.first, "the code on the page is the code in the link")
            assertTrue(s.second, "and it can be copied")
            assertEquals(t, Ticket.fromShared(s.first), "and the app reads the copied code back as the same ticket")
            assertEquals(t, Ticket.fromShared(Invites.LINK_START + t.encode()), "as it reads the whole link")
        }
    }

    @Test
    fun theLinkTheAppBuildsIsOnTheHostAndPathThePageAndTheSiteConfigServe() {
        assertEquals("https://joinowntally.ownspace.in/j#", Invites.LINK_START)
        val conf = File(site, "nginx/joinowntally.ownspace.in.conf").readText()
        assertTrue("server_name joinowntally.ownspace.in;" in conf, "the site config serves the host in the link")
        assertTrue(Regex("""location\s*=\s*/j\s*\{""").containsMatchIn(conf), "and the path /j")
    }

    @Test
    fun whatAHostileLinkCarriesIsShownAsTextAndNeverRun() {
        val hostile = listOf("#<img src=x onerror=alert(1)>", "#%3Cscript%3Ealert(1)%3C%2Fscript%3E", "#\"><svg onload=alert(1)>")
        val shown = runPage(hostile) // the harness throws if the script assigns innerHTML
        assertEquals(hostile.size, shown.size)
        assertEquals("<img src=x onerror=alert(1)>", shown[0].first)
        assertEquals("<script>alert(1)</script>", shown[1].first)
    }

    @Test
    fun aLinkWithNoCodeOrAnUndecodableOneSaysSoInsteadOfLeavingThePageStuck() {
        val (none, broken) = runPage(listOf("", "#%E0%A4%A")).let { it[0] to it[1] }
        assertTrue(none.first.startsWith("No invite code found"), none.first)
        assertFalse(none.second, "nothing to copy")
        assertFalse(broken.first.startsWith("Reading the invite code"), "a broken link must not leave the page saying it is still reading: ${broken.first}")
    }

    // ---- what the page says and loads ----------------------------------------------------------------------------------------

    @Test
    fun thePageNamesTheButtonThatReallyExistsInTheApp() {
        assertTrue("Have an invite? Join a group" in page.replace("&nbsp;", " ").replace("<em>", "").replace("</em>", ""), "the page tells people where to paste the code")
    }

    @Test
    fun thePageSaysTheInviteExpiresWhenTheTicketDoes() {
        assertTrue("expires after ${Ticket.LIFETIME_SECONDS / 3600} hours" in page, "the page and the ticket agree on how long an invite lives")
    }

    @Test
    fun noPageOrStyleLoadsOrRunsAnythingTheSitePolicyForbids() {
        val files = listOf("index.html", "j/index.html", "privacy/index.html").map { File(site, it).readText() }
        for (html in files) {
            assertFalse(Regex("""<script(?![^>]*\bsrc=)""", RegexOption.IGNORE_CASE).containsMatchIn(html), "no inline script")
            assertFalse(Regex("""\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "no inline event handler")
            assertFalse(Regex("""\sstyle\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "no inline style")
            assertFalse(Regex("""<(style|iframe|object|embed|form|frame|img)\b""", RegexOption.IGNORE_CASE).containsMatchIn(html), "nothing the policy would block or that posts")
            for (m in Regex("""<(script|link)\b[^>]*\b(src|href)\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).findAll(html)) {
                assertTrue(m.groupValues[3].startsWith("/") && !m.groupValues[3].startsWith("//"), "a ${m.groupValues[1]} loads from this site only: ${m.groupValues[3]}")
            }
        }
        val css = File(site, "style.css").readText()
        assertFalse(Regex("""@import|url\(\s*['"]?(https?:)?//""").containsMatchIn(css), "the style sheet loads nothing from outside")
        val js = script.readText()
        assertFalse(Regex("""innerHTML|outerHTML|insertAdjacentHTML|document\.write|eval\(|new Function|XMLHttpRequest|fetch\(|sendBeacon|localStorage|sessionStorage""").containsMatchIn(js), "the script writes only text and sends nothing anywhere")
    }

    @Test
    fun theSitePolicyIsTheSameEverywhereItIsSetAndForbidsEverythingByDefault() {
        val conf = File(site, "nginx/joinowntally.ownspace.in.conf").readText()
        val policies = Regex("""add_header Content-Security-Policy "([^"]*)" always;""").findAll(conf).map { it.groupValues[1] }.toList()
        assertTrue(policies.size >= 2, "set at the server and again where a location sets its own headers (nginx drops the server ones there)")
        assertEquals(1, policies.toSet().size, "the same policy each time")
        val csp = policies.first()
        assertTrue("default-src 'none'" in csp && "script-src 'self'" in csp && "style-src 'self'" in csp && "form-action 'none'" in csp && "frame-ancestors 'none'" in csp, csp)
        assertNotNull(Regex("""access_log off;""").find(conf), "the invite link leaves no trace in a log")
    }
}
