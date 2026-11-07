package owntally.data.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import owntally.crypto.Clock
import owntally.data.sim.SimDevice
import owntally.data.sim.SimNetwork
import owntally.data.store.InMemorySyncProblemLog
import owntally.data.store.ProblemKind
import owntally.model.PublicKey
import owntally.sync.ErrorCode
import owntally.sync.GroupRoom
import owntally.sync.Rejection
import owntally.sync.SyncCodec
import owntally.sync.SyncEngine
import owntally.sync.SyncMessage
import owntally.sync.SyncOutcome
import owntally.sync.SyncResult
import owntally.sync.SyncRole

/** What a phone keeps and says about envelopes it dropped (decision S4): a log, counts for the Sync screen, one notice per run. */
class SyncRecorderTest {
    private var now = 1_800_000_000L
    private val log = InMemorySyncProblemLog()
    private val recorder = SyncRecorder(log, Clock { now })
    private val group = owntally.model.GroupId(ByteArray(16) { 3 })
    private val ben = PublicKey(ByteArray(32) { 2 })

    private fun result(outcome: SyncOutcome = SyncOutcome.COMPLETED, error: ErrorCode? = null, rejected: Map<Rejection, Int> = emptyMap()) =
        SyncResult(outcome, 0, 0, 0, rejected, null, null, emptySet(), error, "")

    @Test fun aCleanSyncLeavesNothingInTheLogAndNoNotice() {
        assertNull(recorder.record(group, ben, result()))
        assertEquals(emptyList(), log.recent(group, 50))
    }

    @Test fun eachKindDroppedIsLoggedOnceWithItsCountThePeerAndTheTime() {
        val notice = recorder.record(group, ben, result(rejected = mapOf(Rejection.BAD_SIGNATURE to 3, Rejection.WRONG_GROUP to 1)))!!
        val rows = log.recent(group, 50)
        assertEquals(setOf(ProblemKind.BAD_SIGNATURE to 3, ProblemKind.WRONG_GROUP to 1), rows.map { it.kind to it.count }.toSet())
        assertTrue(rows.all { it.peer == ben && it.atSeconds == now && it.group == group })
        assertEquals(4, notice.total)
        assertEquals(ben, notice.peer)
        assertEquals(mapOf(ProblemKind.BAD_SIGNATURE to 3, ProblemKind.WRONG_GROUP to 1), notice.kinds)
        assertEquals(false, notice.cutOff)
    }

    @Test fun oneNoticePerRunHoweverManyEnvelopesWereDropped() {
        val notice = recorder.record(group, ben, result(rejected = mapOf(Rejection.MALFORMED to 100, Rejection.TOO_LARGE to 50)))
        assertNotNull(notice)
        assertEquals(150, notice.total)
        // a second run makes a second notice, a clean run none
        assertNotNull(recorder.record(group, ben, result(rejected = mapOf(Rejection.MALFORMED to 1))))
        assertNull(recorder.record(group, ben, result()))
    }

    @Test fun aPeerThatWasCutOffForSendingTooMuchBadDataIsLoggedAndNoticedAsSuch() {
        val notice = recorder.record(group, ben, result(SyncOutcome.REFUSED, ErrorCode.TOO_MANY_BAD_ENVELOPES, mapOf(Rejection.BAD_SIGNATURE to 21)))!!
        assertEquals(true, notice.cutOff)
        assertTrue(log.recent(group, 50).any { it.kind == ProblemKind.CUT_OFF })
        assertTrue(log.recent(group, 50).any { it.kind == ProblemKind.BAD_SIGNATURE && it.count == 21 })
    }

    @Test fun otherEndedSyncsAreNotProblemsOfTheEnvelopes() {
        assertNull(recorder.record(group, ben, result(SyncOutcome.INTERRUPTED)))
        assertNull(recorder.record(group, ben, result(SyncOutcome.TIMED_OUT)))
        assertNull(recorder.record(group, ben, result(SyncOutcome.PEER_ERROR, ErrorCode.NOT_ALLOWED)))
        assertEquals(emptyList(), log.recent(group, 50))
    }

    @Test fun theLogKeepsOnlyTheLatestEntriesPerGroupAndTheNewestComeFirst() {
        val small = InMemorySyncProblemLog(maxPerGroup = 5)
        val r = SyncRecorder(small, Clock { now })
        repeat(12) { n -> now += 1; r.record(group, ben, result(rejected = mapOf(Rejection.MALFORMED to n + 1))) }
        val rows = small.recent(group, 50)
        assertEquals(5, rows.size)
        assertEquals(listOf(12, 11, 10, 9, 8), rows.map { it.count }, "the newest first, the oldest dropped")
        val other = owntally.model.GroupId(ByteArray(16) { 9 })
        r.record(other, ben, result(rejected = mapOf(Rejection.MALFORMED to 1)))
        assertEquals(5, small.recent(group, 50).size, "another group has its own allowance")
        small.clear(group)
        assertEquals(emptyList(), small.recent(group, 50))
        assertEquals(1, small.recent(other, 50).size)
    }

    // ---- through the real engine ------------------------------------------------------------------------------------------

    private val marker = "SECRET-MARKER-DO-NOT-LOG"

    @Test fun badEnvelopesSentThroughTheEngineAreDroppedLoggedAndNeverCopiedIntoTheLog() = runTest {
        val a = SimDevice.owner(21, "Asha")
        val b = SimDevice.joined(21, 1, "Ben", a)
        val room = GroupRoom.fromGroupId(a.group.bytes())
        val (la, lb) = SimNetwork(this, Random(1)).link(a.key, b.key)
        val real = a.env.groups.rawEnvelopes(a.group).first()
        val forged = real.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val junk = marker.toByteArray() + ByteArray(80) { 7 }
        val result = coroutineScope {
            val answer = async { SyncEngine(room, b.syncStore()).run(lb, SyncRole.RESPONDER) }
            launch {
                la.send(SyncCodec.encode(SyncMessage.Hello(room, owntally.sync.SyncSummary(emptyMap()))))
                la.send(SyncCodec.encode(SyncMessage.Envelopes(listOf(forged, junk, forged))))
                la.send(SyncCodec.encode(SyncMessage.Done(owntally.sync.SyncSummary(emptyMap()))))
            }
            answer.await()
        }
        assertEquals(SyncOutcome.COMPLETED, result.outcome)
        val notice = recorder.record(b.group, a.key, result)!!
        assertEquals(mapOf(ProblemKind.BAD_SIGNATURE to 2, ProblemKind.MALFORMED to 1), notice.kinds)
        val everything = log.recent(b.group, 50).joinToString("\n") + notice.toString()
        assertTrue(!everything.contains(marker), "no envelope content in the log or the notice")
        assertTrue(!everything.contains("SECRET"), "not even part of it")
    }
}
