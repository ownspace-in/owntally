package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.data.session.PeerState
import owntally.data.session.PeerStatus
import owntally.sync.LinkKind
import owntally.sync.RelayResult

/** The "Can't reach <name>" screen (design/16, 16b): why, that the relay is automatic, and the two things a person can do. */
class CantReachModelTest {
    private val w = TestWorld()
    private val group = w.newGroup("Goa Trip")
    private val s = w.sessions.session(group)
    private val meera = w.addFriend(s, "Meera", 2)
    private val now = 1_800_100_000L

    private fun build(st: PeerStatus) = CantReachModels.build(s.view.value, meera, st, now)
    private val down = PeerStatus(PeerState.UNREACHABLE, attempts = 2, failures = 2, lastAttemptSeconds = now, nextTrySeconds = now + 1_800, notShared = 3)

    @Test fun theTitleNamesTheMemberAndTheBannerSaysTheEntriesAreSafe() {
        val m = build(down.copy(relay = RelayResult.PEER_NOT_THERE))
        assertEquals("Can't reach Meera", m.title)
        assertEquals("No connection to Meera", m.bannerTitle)
        assertEquals(CantReachTone.WARNING, m.bannerTone)
        assertTrue(m.bannerBody.contains("Your 3 new entries are safe on this phone"))
        assertTrue(m.bannerBody.contains("Meera's phone is offline"))
    }

    @Test fun whenThisPhoneCannotReachTheRelayTheBannerBlamesThisSide() {
        val m = build(down.copy(relay = RelayResult.RELAY_DOWN))
        assertEquals("No way to Meera from here", m.bannerTitle)
        assertTrue(m.bannerBody.contains("This phone can't reach the relay"))
        assertEquals("The relay can't be reached from this phone. Trying again in 30 min.", m.relayStatus)
    }

    @Test fun countsReadWellForOneAndForNone() {
        assertTrue(build(down.copy(notShared = 1)).bannerBody.contains("Your 1 new entry is safe"))
        assertTrue(build(down.copy(notShared = 0)).bannerBody.contains("Nothing is waiting to be sent."))
    }

    @Test fun thereIsNoButtonToTurnTheRelayOnBecauseItIsAutomatic() {
        val m = build(down)
        assertEquals("The relay is used automatically", m.relayTitle)
        assertTrue(m.relayBody.contains("never what you sync"))
        assertEquals("Try now", m.tryNowLabel)
        assertEquals("Meet and sync nearby", m.nearbyTitle)
        assertEquals("Try again later", m.laterTitle)
        assertTrue(m.laterSubtitle.contains("tries again in 30 min"))
        assertEquals("Entries are never lost. Any member who has them can pass them on, not only you.", m.footer)
    }

    @Test fun whileATryIsUnderWayTheButtonSaysSo() {
        val m = build(down.copy(state = PeerState.SYNCING))
        assertTrue(m.trying)
        assertEquals("Trying…", m.tryNowLabel)
        assertEquals("Trying now…", m.relayStatus)
    }

    @Test fun afterASyncThroughTheRelayTheScreenSaysItWorked() {
        val m = build(PeerStatus(PeerState.OK, attempts = 1, lastSuccessSeconds = now, kind = LinkKind.RELAYED))
        assertEquals(CantReachTone.GOOD, m.bannerTone)
        assertEquals("Connected to Meera", m.bannerTitle)
        assertEquals("Synced through the relay.", m.relayStatus)
        assertFalse(m.trying)
    }

    @Test fun nothingIsSaidAboutTheRelayBeforeAnyTry() {
        assertNull(build(PeerStatus()).relayStatus)
    }
}
