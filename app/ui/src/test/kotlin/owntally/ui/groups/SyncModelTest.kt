package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import owntally.data.session.PeerState
import owntally.data.session.PeerStatus
import owntally.data.session.RunNotice
import owntally.data.session.SyncStatus
import owntally.data.store.ProblemKind
import owntally.data.store.SyncProblem
import owntally.model.PublicKey
import owntally.sync.LinkKind

/** What the per-group Sync screen shows (design/05, without Nearby and the relay card, decisions S4 and S5), for every state. */
class SyncModelTest {
    private val w = TestWorld()
    private val group = w.newGroup("Goa Trip")
    private val s = w.sessions.session(group)
    private val rohan = w.addFriend(s, "Rohan", 1)
    private val meera = w.addFriend(s, "Meera", 2)
    private val kabir = w.addFriend(s, "Kabir", 3)
    private val now = 1_800_100_000L

    private fun model(peers: Map<PublicKey, PeerStatus> = emptyMap(), notice: RunNotice? = null, problems: List<SyncProblem> = emptyList()) =
        SyncModels.build(s.view.value, SyncStatus(peers, notice), problems, now)

    private fun ok(ago: Long, kind: LinkKind? = LinkKind.DIRECT, notShared: Int = 0) =
        PeerStatus(PeerState.OK, attempts = 1, lastAttemptSeconds = now - ago, lastSuccessSeconds = now - ago, notShared = notShared, kind = kind)

    // ---- the headline ------------------------------------------------------------------------------------------------------

    @Test fun beforeAnyTryTheScreenSaysNothingIsSyncedYetAndEveryoneIsNotReachableYet() {
        val m = model()
        assertEquals(SyncHeadline.NOT_STARTED, m.headline)
        assertEquals("Not syncing yet. Your entries are saved on this phone.", m.headlineText)
        assertEquals(listOf("Kabir", "Meera", "Rohan"), m.members.map { it.name }, "by name, the person themself not listed")
        assertEquals(listOf("Not reachable yet"), m.members.map { it.detail }.distinct())
        assertEquals(listOf("Not yet"), m.members.map { it.badge }.distinct())
    }

    @Test fun whenEveryoneWasReachedAndNothingIsWaitingItIsUpToDate() {
        val m = model(mapOf(rohan to ok(7_200), meera to ok(7_200, LinkKind.RELAYED), kabir to ok(60, LinkKind.NEARBY)))
        assertEquals(SyncHeadline.UP_TO_DATE, m.headline)
        assertEquals("Up to date with 3 members", m.headlineText)
        assertEquals(listOf("Nearby", "Via relay", "Direct"), m.members.map { it.badge })
        assertEquals(listOf("1 min ago", "2 hrs ago", "2 hrs ago"), m.members.map { it.detail })
    }

    @Test fun aLoneMemberIsOneMemberNotMembers() {
        val solo = TestWorld().let { w2 -> val sess = w2.sessions.session(w2.newGroup("Solo")); val f = w2.addFriend(sess, "Rohan", 9)
            SyncModels.build(sess.view.value, SyncStatus(mapOf(f to ok(5))), emptyList(), now) }
        assertEquals("Up to date with 1 member", solo.headlineText)
    }

    @Test fun someoneBeingSyncedWithShowsSyncing() {
        val m = model(mapOf(rohan to ok(10), meera to PeerStatus(PeerState.SYNCING, attempts = 1, lastAttemptSeconds = now), kabir to ok(10)))
        assertEquals(SyncHeadline.SYNCING, m.headline)
        assertEquals("Syncing…", m.headlineText)
        assertEquals("Syncing", m.members.first { it.name == "Meera" }.badge)
    }

    @Test fun aFriendWhoCannotBeReachedMeansWaitingForThemToComeOnline() {
        val m = model(
            mapOf(
                rohan to ok(600),
                meera to PeerStatus(PeerState.UNREACHABLE, attempts = 3, failures = 3, lastAttemptSeconds = now - 20, lastSuccessSeconds = now - 3 * 86_400, nextTrySeconds = now + 1_800, notShared = 3),
                kabir to ok(600),
            ),
        )
        assertEquals(SyncHeadline.WAITING, m.headline)
        assertEquals("Waiting for a friend to come online. 3 entries are saved on this phone and will be sent.", m.headlineText)
        val line = m.members.first { it.name == "Meera" }
        assertEquals("Offline", line.badge)
        assertEquals("3 entries not shared · 3 days ago · trying again in 30 min", line.detail)
    }

    @Test fun waitingWithNothingToSendDoesNotMentionEntries() {
        val m = model(mapOf(rohan to ok(60), meera to PeerStatus(PeerState.UNREACHABLE, attempts = 1, failures = 1, lastAttemptSeconds = now, nextTrySeconds = now + 60), kabir to ok(60)))
        assertEquals("Waiting for a friend to come online.", m.headlineText)
        assertEquals("Never synced · trying again in 1 min", m.members.first { it.name == "Meera" }.detail)
    }

    @Test fun entriesNotSharedAreShownEvenForSomeoneJustReached() {
        val m = model(mapOf(rohan to ok(60, notShared = 1), meera to ok(60), kabir to ok(60)))
        assertEquals(SyncHeadline.WAITING, m.headline, "up to date only when nothing is left to send")
        assertEquals("1 entry not shared · 1 min ago", m.members.first { it.name == "Rohan" }.detail)
    }

    @Test fun someFriendsReachedAndOneNeverTriedIsWaitingNotNotSyncingYet() {
        val m = model(mapOf(rohan to ok(60), kabir to ok(60)))  // Meera has no status at all: never tried
        assertEquals(SyncHeadline.WAITING, m.headline)
        assertEquals("Waiting for a friend to come online.", m.headlineText)
        assertEquals("Not reachable yet", m.members.first { it.name == "Meera" }.detail)
    }

    @Test fun aFailedSyncIsOfflineToo() {
        val m = model(mapOf(rohan to PeerStatus(PeerState.FAILED, attempts = 2, failures = 2, lastAttemptSeconds = now - 5, nextTrySeconds = now + 300)))
        assertEquals("Offline", m.members.first { it.name == "Rohan" }.badge)
    }

    @Test fun theDetailOfWhenIsNeverInTheFuture() {
        val m = model(mapOf(rohan to ok(-500)))
        assertEquals("just now", m.members.first { it.name == "Rohan" }.detail)
    }

    // ---- the notice and the note (S4) ----------------------------------------------------------------------------------------

    @Test fun oneNoticePerRunSaysWhoSentWhatWasRejected() {
        val n = model(notice = RunNotice(rohan, 150, mapOf(ProblemKind.BAD_SIGNATURE to 100, ProblemKind.MALFORMED to 50), cutOff = false)).notice
        assertEquals("Some entries from Rohan were rejected.", n)
    }

    @Test fun aCutOffPeerGetsTheirOwnWords() {
        val n = model(notice = RunNotice(rohan, 21, mapOf(ProblemKind.BAD_SIGNATURE to 21), cutOff = true)).notice
        assertEquals("Rohan sent too many invalid entries, so syncing with them was stopped.", n)
    }

    @Test fun noRejectedEntriesMeansNoNoticeAndNoNote() {
        val m = model()
        assertNull(m.notice)
        assertEquals(emptyList(), m.rejected)
    }

    @Test fun theNoteListsTheLatestLogLinesWithWhoWhatAndWhen() {
        val problems = listOf(
            SyncProblem(group, rohan, ProblemKind.BAD_SIGNATURE, 2, now - 3 * 3_600),
            SyncProblem(group, kabir, ProblemKind.WRONG_GROUP, 1, now - 86_400),
            SyncProblem(group, kabir, ProblemKind.CUT_OFF, 1, now - 86_400),
        )
        assertEquals(
            listOf(
                "2 entries from Rohan were rejected: bad signature · 3 hrs ago",
                "1 entry from Kabir was rejected: for another group · 1 day ago",
                "Syncing with Kabir was stopped after too many invalid entries · 1 day ago",
            ),
            model(problems = problems).rejected,
        )
    }

    @Test fun theNoteShowsAtMostFiveLinesAndNeverAnyEnvelopeContent() {
        val many = (1..9).map { SyncProblem(group, rohan, ProblemKind.MALFORMED, it, now - it) }
        assertEquals(5, model(problems = many).rejected.size)
        // someone who is not (or no longer) in the group is still named, without guessing
        val stranger = PublicKey(ByteArray(32) { 9 })
        assertEquals("1 entry from Someone was rejected: not a valid entry · just now", model(problems = listOf(SyncProblem(group, stranger, ProblemKind.MALFORMED, 1, now))).rejected.single())
    }

    @Test fun aLocalLinkSaysWhetherItWasEncryptedOnTheNetwork() {
        fun detail(security: owntally.sync.LinkSecurity?) = model(mapOf(rohan to ok(7_200).copy(security = security))).members.first { it.name == "Rohan" }.detail
        assertEquals("2 hrs ago · Encrypted link", detail(owntally.sync.LinkSecurity.TLS))
        assertEquals("2 hrs ago · Unencrypted link", detail(owntally.sync.LinkSecurity.PLAIN))
        assertEquals("2 hrs ago", detail(null), "a link that does not say (a relay, for one) shows nothing extra")
    }
}
