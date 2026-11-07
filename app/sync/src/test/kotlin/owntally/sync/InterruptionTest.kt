package owntally.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import owntally.crypto.EnvelopeSigning
import owntally.crypto.KeyDerivation
import kotlinx.coroutines.flow.first
import owntally.crypto.MasterSeed
import owntally.model.GroupId
import owntally.model.PublicKey

/** T5.11: a phone that is killed in the middle of a sync loses nothing it had stored and finishes on the next try. */
class InterruptionTest {
    private val group = GroupId(ByteArray(16) { 3 })
    private val room = GroupRoom.fromGroupId(group.bytes())
    private val seed = MasterSeed(ByteArray(64) { 9 })
    private val keyA = PublicKey(ByteArray(32) { 0x0A })
    private val keyB = PublicKey(ByteArray(32) { 0x0B })

    private fun envelope(n: Int, counter: Long): ByteArray =
        EnvelopeSigning.sign(KeyDerivation.groupIdentity(seed, n.toLong()), 1, group, 1, counter, ByteArray(24), ByteArray(80) { (counter + it).toByte() }).encode()

    private fun store(vararg events: Pair<Int, LongRange>) = InMemorySyncStore(group).also { s ->
        for ((n, range) in events) for (c in range) s.offer(envelope(n, c))
    }

    private fun contents(s: SyncStore) = s.summary().entries.keys.flatMap { s.versionsOf(it) }.map { it.toList() }.toSet()

    /** The process dying: thrown from inside the store, after the envelope has been written. */
    private class Killed : RuntimeException()

    /** Dies right after the [dieAfter]th envelope is stored; what was stored stays in [disk]. */
    private class Dying(val disk: InMemorySyncStore, var dieAfter: Int) : SyncStore by disk {
        var stored = 0
        override fun offer(bytes: ByteArray): Offered {
            val r = disk.offer(bytes)
            if (r == Offered.Stored && ++stored >= dieAfter) throw Killed()
            return r
        }
    }

    private suspend fun run(a: SyncStore, b: SyncStore): Pair<SyncResult?, SyncResult?> {
        val net = InMemoryNetwork()
        val incoming = net.transportFor(keyB).incoming(room)
        val la = net.transportFor(keyA).connect(room, keyB)
        val lb = incoming.first()
        return coroutineScope {
            val x = async { runCatching { SyncEngine(room, a).run(la, SyncRole.INITIATOR) }.getOrNull() }
            val y = async { runCatching { SyncEngine(room, b).run(lb, SyncRole.RESPONDER) }.getOrNull() }
            x.await() to y.await()
        }
    }

    @Test fun killingTheReceiverAfterEveryStoredEnvelopeThenRestartingEndsWithBothIdentical() = runTest {
        val total = 12
        val source = store(1 to 1L..8L, 2 to 1L..4L)
        for (kill in 1..total) {
            val disk = store(3 to 1L..2L)
            val before = contents(disk)
            val dying = Dying(disk, kill)
            val (_, _) = run(source, dying)
            assertTrue(dying.stored == kill, "killed after $kill")
            // What is on disk is whole: the earlier contents and exactly the envelopes stored so far, and the summary agrees.
            assertEquals(before.size + kill, contents(disk).size)
            assertTrue(contents(disk).containsAll(before))
            // The restart: a new engine on the same disk finishes the job.
            val (x, y) = run(source, disk)
            assertEquals(SyncOutcome.COMPLETED, x?.outcome, "kill $kill")
            assertEquals(SyncOutcome.COMPLETED, y?.outcome, "kill $kill")
            assertEquals(contents(source) + before, contents(disk), "kill $kill")
            // The restart only fetched what was missing.
            assertEquals(total - kill, y?.stored, "kill $kill")
        }
    }

    @Test fun killingTheSenderSideStoreAfterEveryStoredEnvelopeAlsoLeavesBothWholeAndFinishes() = runTest {
        // Both sides have something the other lacks, so the dying phone is also the one that sent first.
        val total = 6 + 5
        for (kill in 1..5) {
            val a = store(1 to 1L..6L)
            val disk = store(2 to 1L..5L)
            val dying = Dying(disk, kill)
            run(a, dying)
            val (x, y) = run(a, disk)
            assertEquals(SyncOutcome.COMPLETED, x?.outcome)
            assertEquals(SyncOutcome.COMPLETED, y?.outcome)
            assertEquals(contents(a), contents(disk))
            assertEquals(total, contents(disk).size)
        }
    }

    @Test fun anInterruptedSyncNeverLeavesAGapInTheSummaryItWouldClaim() = runTest {
        val source = store(1 to 1L..30L)
        val author = source.summary().entries.keys.first()
        val disk = InMemorySyncStore(group)
        run(source, Dying(disk, 7))
        val claimed = disk.summary().entries[author]?.counter ?: 0
        val held = disk.versionsAbove(author, 0).map { owntally.model.Envelope.decode(it).counter }.toSet()
        // Whatever was stored, the summary claims only an unbroken run from 1.
        assertTrue(claimed <= held.size)
        assertTrue((1..claimed).all { it in held })
    }
}
