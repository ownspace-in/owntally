package owntally.data.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** M9, T9.3 (choice 1): only an https link on this host and this path, with a code after the `#`, opens the app. */
class InviteLinksTest {
    private val code = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4_QA"
    private fun accept(scheme: String? = "https", host: String? = "joinowntally.ownspace.in", port: Int = -1, userInfo: String? = null, path: String? = "/j", fragment: String? = code) =
        InviteLinks.fromParts(scheme, host, port, userInfo, path, fragment)

    @Test fun theLinkTheAppWritesIsAccepted() {
        assertEquals(Invites.LINK_START + code, accept())
    }

    @Test fun capitalsInTheSchemeAndHostDoNotMatter() {
        assertEquals(Invites.LINK_START + code, accept(scheme = "HTTPS", host = "JoinOwnTally.OwnSpace.in"))
    }

    @Test fun anotherSchemeIsRefused() {
        for (s in listOf("http", "owntally", "intent", "content", "file", "javascript", null)) assertNull(accept(scheme = s), "$s")
    }

    @Test fun anotherHostIsRefusedEvenIfItLooksLikeOurs() {
        for (h in listOf("ownspace.in", "relay.ownspace.in", "evil.com", "joinowntally.ownspace.in.evil.com", "xjoinowntally.ownspace.in", "joinowntally.ownspace.in.", "", null)) assertNull(accept(host = h), "$h")
    }

    @Test fun aPortOrAUserNameInTheLinkIsRefused() {
        assertNull(accept(port = 8443))
        assertNull(accept(port = 443), "even the usual one, written out: the link the app writes has none")
        assertNull(accept(userInfo = "joinowntally.ownspace.in"))
        assertNull(accept(userInfo = "x"))
    }

    @Test fun onlyThePathJOpensTheApp() {
        for (p in listOf("/", "/j/", "/join", "/jx", "/j/extra", "/J", "/.well-known/assetlinks.json", "", null)) assertNull(accept(path = p), "$p")
    }

    @Test fun aLinkWithNoCodeOrAnEmptyOneIsRefused() {
        assertNull(accept(fragment = null))
        assertNull(accept(fragment = ""))
        assertNull(accept(fragment = "   "))
    }

    @Test fun aCodeThatIsTooLongIsRefusedBeforeAnythingReadsIt() {
        assertEquals(Invites.LINK_START + "a".repeat(2_000), accept(fragment = "a".repeat(2_000)))
        assertNull(accept(fragment = "a".repeat(2_001)))
    }

    @Test fun theCodeIsPassedOnAsWrittenNotChanged() {
        assertEquals(Invites.LINK_START + "ab%2Bcd", accept(fragment = "ab%2Bcd"), "decoding is for the ticket reader, not for this check")
    }

    @Test fun whatTheCheckAcceptsIsExactlyWhatTheTicketReaderExpects() {
        // The link text is handed to Ticket.fromShared, which takes the part after the last '#': a code with its own '#' must not smuggle a second one in.
        assertNull(accept(fragment = "abc#def"))
    }
}
