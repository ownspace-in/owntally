package owntally.sync

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import owntally.crypto.EnvelopeSigning
import owntally.crypto.KeyDerivation
import owntally.crypto.MasterSeed
import owntally.model.Envelope
import owntally.model.GroupId
import owntally.model.PublicKey

/** The sync of two stores over a link (T5.3). The envelopes are real and really signed; their content is random bytes, because the engine never reads it. */
class SyncEngineTest {
    private val seed = MasterSeed(ByteArray(64) { 7 })
    private val group = GroupId(ByteArray(16) { 3 })
    private val otherGroup = GroupId(ByteArray(16) { 4 })
    private val room = GroupRoom.fromGroupId(group.bytes())
    private fun who(n: Int) = KeyDerivation.groupIdentity(seed, n.toLong())
    private val keyA = PublicKey(ByteArray(32) { 0x0A })
    private val keyB = PublicKey(ByteArray(32) { 0x0B })

    private fun envelope(n: Int, counter: Long, tag: Int = 0, inGroup: GroupId = group, size: Int = 80): ByteArray =
        EnvelopeSigning.sign(who(n), 1, inGroup, 1, counter, ByteArray(24) { tag.toByte() }, ByteArray(size) { (counter + tag + it).toByte() }).encode()

    private fun store(vararg events: Pair<Int, LongRange>): InMemorySyncStore = InMemorySyncStore(group).also { s ->
        for ((n, range) in events) for (c in range) assertTrue(s.offer(envelope(n, c)) is Offered.Stored)
    }

    private fun all(s: InMemorySyncStore) = s.summary().entries.keys.flatMap { s.versionsOf(it) }.map { it.toList() }.toSet()

    private class Pair2(val a: Link, val b: Link)

    private suspend fun connect(): Pair2 {
        val net = InMemoryNetwork()
        val ta = net.transportFor(keyA)
        val tb = net.transportFor(keyB)
        val incoming = tb.incoming(room)
        val a = ta.connect(room, keyB)
        return Pair2(a, incoming.first())
    }

    private suspend fun sync(a: SyncStore, b: SyncStore, engineA: SyncEngine = SyncEngine(room, a), engineB: SyncEngine = SyncEngine(room, b), wrap: (Link, Link) -> Pair<Link, Link> = { x, y -> x to y }): Pair<SyncResult, SyncResult> {
        val raw = connect()
        val (la, lb) = wrap(raw.a, raw.b)
        return coroutineScope {
            val ra = async { engineA.run(la, SyncRole.INITIATOR) }
            val rb = async { engineB.run(lb, SyncRole.RESPONDER) }
            ra.await() to rb.await()
        }
    }

    // ---- convergence --------------------------------------------------------------------------------------------------

    @Test fun twoStoresWithDifferentHistoriesEndWithTheUnion() = runTest {
        val a = store(1 to 1L..5L, 2 to 1L..2L)
        val b = store(2 to 1L..4L, 3 to 1L..3L)
        val (ra, rb) = sync(a, b)
        assertEquals(SyncOutcome.COMPLETED, ra.outcome)
        assertEquals(SyncOutcome.COMPLETED, rb.outcome)
        assertEquals(a.summary(), b.summary())
        assertEquals(all(a), all(b))
        assertEquals(5 + 4 + 3, all(a).size)
        assertEquals(2 + 3, ra.stored, "A got what it lacked: 2 events of author 2 and 3 of author 3")
        assertEquals(3 + 2, rb.stored, "B got what it lacked: 3 events of author 1 and 2 of author 2")
        assertEquals(rb.stored, ra.sent)
        assertEquals(ra.stored, rb.sent, "each side sends exactly what the other lacked, and nothing it just received")
        assertEquals(0, ra.duplicates + rb.duplicates)
        assertNotNull(ra.peerFinal)
        assertEquals(a.summary(), ra.peerFinal, "the answer's last summary shows what it stored")
    }

    @Test fun aSecondSyncStoresAndSendsNothing() = runTest {
        val a = store(1 to 1L..5L)
        val b = store(2 to 1L..3L)
        sync(a, b)
        val (ra, rb) = sync(a, b)
        assertEquals(0, ra.sent + ra.stored + rb.sent + rb.stored)
        assertEquals(SyncOutcome.COMPLETED, ra.outcome)
    }

    @Test fun anEmptyStoreReceivesEverything() = runTest {
        val a = store(1 to 1L..10L)
        val b = InMemorySyncStore(group)
        sync(a, b)
        assertEquals(all(a), all(b))
    }

    @Test fun aLargeHistoryGoesInSeveralBatchesAndArrivesWhole() = runTest {
        val a = store(1 to 1L..500L)
        val b = InMemorySyncStore(group)
        val (ra, _) = sync(a, b)
        assertEquals(500, ra.sent)
        assertEquals(500L, b.summary().entries.getValue(who(1).publicKey).counter)
    }

    @Test fun aGapIsFilledFromTheOtherSide() = runTest {
        val a = store(1 to 1L..6L)
        val b = InMemorySyncStore(group).also { s -> listOf(1L, 2L, 5L, 6L).forEach { s.offer(envelope(1, it)) } } // 3 and 4 are missing
        assertEquals(2L, b.summary().entries.getValue(who(1).publicKey).counter, "the summary counts only up to the gap")
        sync(a, b)
        assertEquals(6L, b.summary().entries.getValue(who(1).publicKey).counter)
        assertEquals(all(a), all(b))
    }

    // ---- interruptions ------------------------------------------------------------------------------------------------

    /** Cuts the link for both sides after [limit] messages in all (counting the ones this wrapper's partner sends). */
    private class Cutter(val limit: Int) {
        var sent = 0
        fun wrap(inner: Link, other: () -> Link) = object : Link {
            override val peer get() = inner.peer
            override val kind get() = inner.kind
            override val received get() = inner.received
            override suspend fun send(bytes: ByteArray) {
                if (sent >= limit) { inner.close(); other().close(); throw IOException("link cut") }
                sent++
                inner.send(bytes)
            }
            override suspend fun close() = inner.close()
        }
    }

    @Test fun aLinkCutAfterAnyNumberOfMessagesLeavesNothingBrokenAndTheNextSyncFinishes() = runTest {
        // How many messages a whole sync takes:
        val probeA = store(1 to 1L..450L, 2 to 1L..3L)
        val probeB = store(2 to 1L..6L, 3 to 1L..250L)
        val counter = Cutter(Int.MAX_VALUE)
        sync(probeA, probeB, wrap = { x, y -> lateinit var wb: Link; val wa = counter.wrap(x) { wb }; wb = counter.wrap(y) { wa }; wa to wb })
        val total = counter.sent
        assertTrue(total in 6..40, "a sync of this size is $total messages")
        for (limit in 0..total) {
            val a = store(1 to 1L..450L, 2 to 1L..3L)
            val b = store(2 to 1L..6L, 3 to 1L..250L)
            val cutter = Cutter(limit)
            val (ra, rb) = sync(a, b, wrap = { x, y -> lateinit var wb: Link; val wa = cutter.wrap(x) { wb }; wb = cutter.wrap(y) { wa }; wa to wb })
            if (limit < total) assertTrue(ra.outcome != SyncOutcome.COMPLETED || rb.outcome != SyncOutcome.COMPLETED, "limit $limit")
            // Everything stored so far is valid and whole; a second, uninterrupted sync finishes the job.
            val (ra2, rb2) = sync(a, b)
            assertEquals(SyncOutcome.COMPLETED, ra2.outcome, "limit $limit")
            assertEquals(SyncOutcome.COMPLETED, rb2.outcome, "limit $limit")
            assertEquals(a.summary(), b.summary(), "limit $limit")
            assertEquals(all(a), all(b), "limit $limit")
            assertEquals(450 + 6 + 250, all(a).size, "limit $limit")
        }
    }

    @Test fun aPeerThatGoesSilentTimesOutOnVirtualTime() = runTest {
        val a = store(1 to 1L..3L)
        val raw = connect()
        // B's side never answers: it only opened the link.
        val r = SyncEngine(room, a, stallTimeoutMs = 30_000).run(raw.a, SyncRole.INITIATOR)
        assertEquals(SyncOutcome.TIMED_OUT, r.outcome)
        assertEquals(0, r.stored)
    }

    // ---- what a peer may send -----------------------------------------------------------------------------------------

    private suspend fun scriptedInitiator(b: InMemorySyncStore, script: suspend (Link) -> Unit): SyncResult {
        val raw = connect()
        return coroutineScope {
            val rb = async { SyncEngine(room, b).run(raw.b, SyncRole.RESPONDER) }
            launch {
                val link = raw.a
                link.send(SyncCodec.encode(SyncMessage.Hello(room, InMemorySyncStore(group).summary())))
                script(link)
            }
            rb.await()
        }
    }

    private fun done() = SyncCodec.encode(SyncMessage.Done(InMemorySyncStore(group).summary()))
    private fun batch(vararg e: ByteArray) = SyncCodec.encode(SyncMessage.Envelopes(e.toList()))

    @Test fun aBatchSentTwiceStoresOnceAndCountsTheRestAsDuplicates() = runTest {
        val b = InMemorySyncStore(group)
        val one = batch(envelope(1, 1), envelope(1, 2))
        val r = scriptedInitiator(b) { it.send(one); it.send(one); it.send(done()) }
        assertEquals(SyncOutcome.COMPLETED, r.outcome)
        assertEquals(2, r.stored)
        assertEquals(2, r.duplicates)
    }

    @Test fun eventsInAnyOrderAreAllStored() = runTest {
        val b = InMemorySyncStore(group)
        val r = scriptedInitiator(b) { it.send(batch(envelope(1, 3), envelope(1, 1), envelope(1, 2))); it.send(done()) }
        assertEquals(3, r.stored)
        assertEquals(3L, b.summary().entries.getValue(who(1).publicKey).counter)
    }

    @Test fun anEnvelopeForAnotherGroupIsDroppedAndCountedAndTheRestIsStored() = runTest {
        val b = InMemorySyncStore(group)
        val r = scriptedInitiator(b) { it.send(batch(envelope(1, 1), envelope(2, 1, inGroup = otherGroup), envelope(1, 2))); it.send(done()) }
        assertEquals(SyncOutcome.COMPLETED, r.outcome)
        assertEquals(2, r.stored)
        assertEquals(mapOf(Rejection.WRONG_GROUP to 1), r.rejected)
        assertEquals(null, b.summary().entries[who(2).publicKey])
    }

    @Test fun aBadSignatureIsDroppedAndCountedAndTheRestOfTheBatchIsStored() = runTest {
        val b = InMemorySyncStore(group)
        val forged = envelope(1, 2).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val r = scriptedInitiator(b) { it.send(batch(envelope(1, 1), forged, envelope(1, 3))); it.send(done()) }
        assertEquals(SyncOutcome.COMPLETED, r.outcome)
        assertEquals(2, r.stored)
        assertEquals(mapOf(Rejection.BAD_SIGNATURE to 1), r.rejected)
        assertEquals(1L, b.summary().entries.getValue(who(1).publicKey).counter, "2 is missing, so the prefix stops at 1")
    }

    @Test fun anEnvelopeThatIsNotAnEnvelopeIsDroppedAsMalformed() = runTest {
        val b = InMemorySyncStore(group)
        val r = scriptedInitiator(b) { it.send(batch(ByteArray(100) { 9 }, envelope(1, 1))); it.send(done()) }
        assertEquals(1, r.stored)
        assertEquals(mapOf(Rejection.MALFORMED to 1), r.rejected)
    }

    @Test fun aPeerThatSendsOnlyBadDataIsCutOffAfterALimit() = runTest {
        val b = InMemorySyncStore(group)
        val bad = envelope(1, 1).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        var errorSeen: SyncMessage? = null
        val raw = connect()
        val r = coroutineScope {
            val rb = async { SyncEngine(room, b, maxBadEnvelopes = 5).run(raw.b, SyncRole.RESPONDER) }
            launch {
                raw.a.send(SyncCodec.encode(SyncMessage.Hello(room, InMemorySyncStore(group).summary())))
                repeat(3) { raw.a.send(batch(bad, bad, bad)) }
            }
            launch { errorSeen = raw.a.received.first { (SyncCodec.decode(it) as? Decoded.Ok)?.message is SyncMessage.Error }.let { (SyncCodec.decode(it) as Decoded.Ok).message } }
            rb.await()
        }
        assertEquals(SyncOutcome.REFUSED, r.outcome)
        assertEquals(ErrorCode.TOO_MANY_BAD_ENVELOPES, r.error)
        assertEquals(ErrorCode.TOO_MANY_BAD_ENVELOPES, (errorSeen as SyncMessage.Error).code)
    }

    @Test fun anEnvelopeOverTheSizeLimitEndsTheSyncWithAnErrorTheOtherSideGets() = runTest {
        val b = InMemorySyncStore(group)
        val raw = connect()
        var errorSeen: SyncMessage.Error? = null
        val tooBig = byteArrayOf(0x4F, 0x53, 1, 2, 0, 1, 0, 1, 0, 1) + ByteArray(65_537) // length 65,537
        val r = coroutineScope {
            val rb = async { SyncEngine(room, b).run(raw.b, SyncRole.RESPONDER) }
            launch {
                raw.a.send(SyncCodec.encode(SyncMessage.Hello(room, InMemorySyncStore(group).summary())))
                raw.a.send(tooBig)
            }
            launch { errorSeen = (SyncCodec.decode(raw.a.received.first { (SyncCodec.decode(it) as? Decoded.Ok)?.message is SyncMessage.Error }) as Decoded.Ok).message as SyncMessage.Error }
            rb.await()
        }
        assertEquals(SyncOutcome.REFUSED, r.outcome)
        assertEquals(ErrorCode.TOO_LARGE, r.error)
        assertEquals(ErrorCode.TOO_LARGE, errorSeen!!.code)
    }

    @Test fun theStoreItselfRefusesAnOversizeEnvelope() {
        val s = InMemorySyncStore(group)
        assertEquals(Offered.Rejected(Rejection.TOO_LARGE), s.offer(ByteArray(65_537)))
    }

    @Test fun aDifferentRoomEndsBothSidesWithWrongGroup() = runTest {
        val a = store(1 to 1L..2L)
        val b = InMemorySyncStore(GroupId(ByteArray(16) { 9 }))
        val other = GroupRoom.fromGroupId(ByteArray(16) { 9 })
        val (ra, rb) = sync(a, b, engineB = SyncEngine(other, b))
        assertTrue(ra.outcome != SyncOutcome.COMPLETED && rb.outcome != SyncOutcome.COMPLETED)
        assertTrue(ra.error == ErrorCode.WRONG_GROUP || rb.error == ErrorCode.WRONG_GROUP)
        assertEquals(0, b.summary().entries.size)
    }

    @Test fun aMessageOutOfTurnEndsTheSyncWithInvalid() = runTest {
        val b = InMemorySyncStore(group)
        val r = scriptedInitiator(b) { it.send(SyncCodec.encode(SyncMessage.Hello(room, InMemorySyncStore(group).summary()))) }
        assertEquals(SyncOutcome.REFUSED, r.outcome)
        assertEquals(ErrorCode.INVALID, r.error)
    }

    @Test fun garbageEndsTheSyncWithInvalidAndStoresNothing() = runTest {
        val b = store(1 to 1L..2L)
        val before = all(b)
        val r = scriptedInitiator(b) { it.send(ByteArray(40) { 5 }) }
        assertEquals(SyncOutcome.REFUSED, r.outcome)
        assertEquals(before, all(b))
    }

    @Test fun aPeerErrorIsReported() = runTest {
        val b = store(1 to 1L..2L)
        val r = scriptedInitiator(b) { it.send(SyncCodec.encode(SyncMessage.Error(ErrorCode.NOT_ALLOWED, "no"))) }
        assertEquals(SyncOutcome.PEER_ERROR, r.outcome)
        assertEquals(ErrorCode.NOT_ALLOWED, r.error)
    }

    // ---- the store ----------------------------------------------------------------------------------------------------

    @Test fun theStoreKeepsOneVersionTwoAtMostAndOnlyTheSmallestTwo() {
        val s = InMemorySyncStore(group)
        val versions = (1..3).map { envelope(1, 1, tag = it) }
        assertTrue(s.offer(versions[0]) is Offered.Stored)
        assertEquals(Offered.Duplicate, s.offer(versions[0]))
        assertTrue(s.offer(versions[1]) is Offered.Stored, "a second, different version is kept")
        s.offer(versions[2])
        val kept = s.versionsOf(who(1).publicKey)
        assertEquals(2, kept.size)
        assertEquals(Fingerprint.smallestTwo(versions).map { it.toList() }.toSet(), kept.map { it.toList() }.toSet())
    }

    @Test fun theSummaryFingerprintFollowsTheEventsAndAnyVersion() {
        val s = InMemorySyncStore(group)
        val e1 = envelope(1, 1)
        val e2 = envelope(1, 2)
        s.offer(e1); s.offer(e2)
        val expected = Fingerprint.next(Fingerprint.next(Fingerprint.ZERO, 1, listOf(e1)), 2, listOf(e2))
        assertContentEquals(expected, s.summary().entries.getValue(who(1).publicKey).fingerprint)
        assertContentEquals(Fingerprint.next(Fingerprint.ZERO, 1, listOf(e1)), s.fingerprintAt(who(1).publicKey, 1))
        // a second version of event 1 changes the fingerprints at 1 and 2
        val e1b = envelope(1, 1, tag = 5)
        s.offer(e1b)
        val after = Fingerprint.next(Fingerprint.next(Fingerprint.ZERO, 1, listOf(e1, e1b)), 2, listOf(e2))
        assertContentEquals(after, s.summary().entries.getValue(who(1).publicKey).fingerprint)
        assertEquals(null, s.fingerprintAt(who(1).publicKey, 3), "not held that far")
    }

    // ---- two different versions of one event (S1, T5.5) -----------------------------------------------------------------

    private fun versions(s: InMemorySyncStore, n: Int, counter: Long) = s.versionsAbove(who(n).publicKey, counter - 1).filter { Envelope.decode(it).counter == counter }.map { it.toList() }.toSet()

    @Test fun twoPhonesWithTheSameCountersButDifferentVersionsFindItAndEndWithBoth() = runTest {
        val a = store(1 to 1L..1L).also { it.offer(envelope(1, 2, tag = 0)) }
        val b = store(1 to 1L..1L).also { it.offer(envelope(1, 2, tag = 5)) }
        assertEquals(a.summary().entries.getValue(who(1).publicKey).counter, b.summary().entries.getValue(who(1).publicKey).counter, "both believe they are up to date")
        assertTrue(!a.summary().entries.getValue(who(1).publicKey).fingerprint.contentEquals(b.summary().entries.getValue(who(1).publicKey).fingerprint))
        val (ra, rb) = sync(a, b)
        assertEquals(SyncOutcome.COMPLETED, ra.outcome)
        assertEquals(SyncOutcome.COMPLETED, rb.outcome)
        assertEquals(setOf(who(1).publicKey), ra.reconciled + rb.reconciled)
        assertEquals(2, versions(a, 1, 2).size)
        assertEquals(versions(a, 1, 2), versions(b, 1, 2))
        assertEquals(a.summary(), b.summary(), "the same fingerprints now")
        // and it is over: a second sync reconciles nothing and stores nothing
        val (ra2, rb2) = sync(a, b)
        assertEquals(emptySet(), ra2.reconciled + rb2.reconciled)
        assertEquals(0, ra2.stored + rb2.stored + ra2.sent + rb2.sent)
    }

    @Test fun theLongerPhoneNoticesAndTheShorterOneGetsBothTheRestAndTheOtherVersion() = runTest {
        for (longerInitiates in listOf(true, false)) {
            val long = store(1 to 1L..2L).also { it.offer(envelope(1, 3, tag = 0)); it.offer(envelope(1, 4)); it.offer(envelope(1, 5)) }
            val short = store(1 to 1L..2L).also { it.offer(envelope(1, 3, tag = 5)) }
            val (ra, rb) = if (longerInitiates) sync(long, short) else sync(short, long).let { it.second to it.first }
            assertEquals(SyncOutcome.COMPLETED, ra.outcome, "longer initiates: $longerInitiates")
            assertEquals(SyncOutcome.COMPLETED, rb.outcome, "longer initiates: $longerInitiates")
            assertEquals(2, versions(long, 1, 3).size)
            assertEquals(versions(long, 1, 3), versions(short, 1, 3))
            assertEquals(long.summary(), short.summary())
            assertEquals(5L, short.summary().entries.getValue(who(1).publicKey).counter, "the later events arrived too")
            assertEquals(all(long), all(short))
        }
    }

    @Test fun aDifferenceBeyondTheShorterPhonesEventsIsNotAForkJustMissingEvents() = runTest {
        val long = store(1 to 1L..5L)
        val short = store(1 to 1L..3L)
        val (ra, rb) = sync(long, short)
        assertEquals(emptySet(), ra.reconciled + rb.reconciled, "the same first three events: nothing to reconcile")
        assertEquals(all(long), all(short))
    }

    @Test fun onlyTheAuthorWhoseVersionsDifferIsReconciled() = runTest {
        val a = store(1 to 1L..3L, 2 to 1L..3L).also { it.offer(envelope(2, 4, tag = 0)) }
        val b = store(1 to 1L..3L, 2 to 1L..3L).also { it.offer(envelope(2, 4, tag = 7)) }
        val (ra, rb) = sync(a, b)
        assertEquals(setOf(who(2).publicKey), ra.reconciled + rb.reconciled)
        assertEquals(all(a), all(b))
    }

    @Test fun threeVersionsAcrossTwoPhonesEndWithTheTwoSmallestOnBoth() = runTest {
        val v = (1..3).map { envelope(1, 1, tag = it) }
        val two = Fingerprint.smallestTwo(v)
        val smallest = two.map { it.toList() }.toSet()
        val third = v.first { x -> two.none { it.contentEquals(x) } }
        // A holds the second smallest and the largest, B the two smallest.
        val a = InMemorySyncStore(group).also { it.offer(two[1]); it.offer(third) }
        val b = InMemorySyncStore(group).also { it.offer(two[0]); it.offer(two[1]) }
        sync(a, b)
        assertEquals(smallest, versions(a, 1, 1))
        assertEquals(smallest, versions(b, 1, 1))
        assertEquals(a.summary(), b.summary())
    }

    @Test fun aLinkCutAnywhereDuringAReconcileLeavesNothingBrokenAndALaterSyncFinishes() = runTest {
        fun phones(): Pair<InMemorySyncStore, InMemorySyncStore> {
            val a = store(1 to 1L..3L, 2 to 1L..2L).also { it.offer(envelope(1, 4, tag = 0)); it.offer(envelope(3, 1)) }
            val b = store(1 to 1L..3L, 2 to 1L..2L).also { it.offer(envelope(1, 4, tag = 9)); it.offer(envelope(1, 5)) }
            return a to b
        }
        val probe = Cutter(Int.MAX_VALUE)
        phones().let { (a, b) -> sync(a, b, wrap = { x, y -> lateinit var wb: Link; val wa = probe.wrap(x) { wb }; wb = probe.wrap(y) { wa }; wa to wb }) }
        val total = probe.sent
        assertTrue(total >= 6, "a reconcile sync is $total messages")
        for (limit in 0..total) {
            val (a, b) = phones()
            val cutter = Cutter(limit)
            sync(a, b, wrap = { x, y -> lateinit var wb: Link; val wa = cutter.wrap(x) { wb }; wb = cutter.wrap(y) { wa }; wa to wb })
            val (r1, r2) = sync(a, b)
            assertEquals(SyncOutcome.COMPLETED, r1.outcome, "limit $limit")
            assertEquals(SyncOutcome.COMPLETED, r2.outcome, "limit $limit")
            assertEquals(a.summary(), b.summary(), "limit $limit")
            assertEquals(all(a), all(b), "limit $limit")
            assertEquals(2, versions(a, 1, 4).size, "limit $limit")
        }
    }

    // ---- who may connect, and what a limited peer gets (S2, T5.7) --------------------------------------------------------------

    @Test fun aPeerThatIsRefusedGetsOnlyAnErrorAndLearnsNothingAboutTheGroup() = runTest {
        val b = store(1 to 1L..5L)
        val before = all(b)
        val raw = connect()
        val seen = ArrayList<SyncMessage>()
        val r = coroutineScope {
            val answer = async { SyncEngine(room, b, access = { Admission.Refuse }).run(raw.b, SyncRole.RESPONDER) }
            launch {
                raw.a.send(SyncCodec.encode(SyncMessage.Hello(room, InMemorySyncStore(group).summary())))
                raw.a.send(batch(envelope(9, 1)))
            }
            launch { raw.a.received.collect { seen += (SyncCodec.decode(it) as Decoded.Ok).message } }
            answer.await().also { raw.b.close() }
        }
        assertEquals(SyncOutcome.REFUSED, r.outcome)
        assertEquals(ErrorCode.NOT_ALLOWED, r.error)
        assertEquals(listOf<SyncMessage>(SyncMessage.Error(ErrorCode.NOT_ALLOWED, "not allowed")), seen.map { if (it is SyncMessage.Error) SyncMessage.Error(it.code, "not allowed") else it }, "one Error, no Hello, no summary, no envelopes")
        assertEquals(before, all(b), "nothing the refused peer sent was stored")
    }

    @Test fun aPhoneThatIsAskedToSyncWithSomeoneItRefusesSendsNothingAtAll() = runTest {
        val a = store(1 to 1L..5L)
        val raw = connect()
        val received = ArrayList<ByteArray>()
        val r = coroutineScope {
            launch { raw.b.received.collect { received += it } }
            val x = SyncEngine(room, a, access = { Admission.Refuse }).run(raw.a, SyncRole.INITIATOR)
            raw.a.close()
            x
        }
        assertEquals(SyncOutcome.REFUSED, r.outcome)
        assertEquals(ErrorCode.NOT_ALLOWED, r.error)
        assertEquals(0, received.size)
    }

    @Test fun aLimitedPeerIsSentOnlyWhatItMayHaveSeesOnlyTheSummaryItMayAndTheEventsItHandsOverAreStored() = runTest {
        val given = envelope(2, 3) // stands in for the removal event: the one envelope the limited peer may be given
        val full = store(1 to 1L..5L, 2 to 1L..3L)
        val limited = store(2 to 1L..2L, 3 to 1L..2L) // the limited peer also has two events by an author the full phone lacks
        val limitedBefore = all(limited)
        val visible = { InMemorySyncStore(group).also { it.offer(envelope(2, 1)) }.summary() } // all the limited peer may be told
        val access = PeerAccess { Admission.Limited(visible, listOf(given)) }
        val (rLimited, rFull) = sync(limited, full, engineB = SyncEngine(room, full, access = access))
        assertEquals(SyncOutcome.COMPLETED, rLimited.outcome)
        assertEquals(SyncOutcome.COMPLETED, rFull.outcome)
        assertEquals(1, rLimited.stored, "the limited peer received one envelope")
        assertEquals(setOf(given.toList()), all(limited) - limitedBefore, "and it is the one it may have")
        assertEquals(visible(), rLimited.peerSummary, "it was shown only the summary it may see")
        assertEquals(2, rFull.stored, "what the limited peer handed over (author 3) is stored")
        assertEquals(3L, full.summary().entries.getValue(who(2).publicKey).counter)
        assertEquals(2L, full.summary().entries.getValue(who(3).publicKey).counter)
        assertEquals(emptySet(), rLimited.reconciled + rFull.reconciled)
    }

    @Test fun aLimitedPeerWhoMayReceiveNothingGetsNothingButItsEventsAreStoredAndTheAnswerShowsWhatWasStored() = runTest {
        val full = store(1 to 1L..5L)
        val leaver = store(2 to 1L..4L)
        val ownEntry = { full.summary().entries.filterKeys { it == who(2).publicKey }.let { SyncSummary(it) } }
        val access = PeerAccess { Admission.Limited(ownEntry, emptyList()) }
        val (rLeaver, rFull) = sync(leaver, full, engineB = SyncEngine(room, full, access = access))
        assertEquals(0, rLeaver.stored, "nothing sent to it")
        assertEquals(4, rFull.stored)
        assertEquals(setOf(who(2).publicKey), rLeaver.peerFinal!!.entries.keys, "the answer names only the leaver's own author")
        assertEquals(4L, rLeaver.peerFinal!!.entries.getValue(who(2).publicKey).counter, "and proves the four events arrived")
        assertEquals(emptySet(), rLeaver.peerSummary!!.entries.keys, "the opening summary showed nothing of the group: the leaver had written nothing the phone held yet")
    }

    @Test fun aLimitedPhoneThatConnectsToAFullOneWhichFindsADifferenceInVersionsDoesNotLeaveItWaiting() = runTest {
        // The limited phone holds a different version of X#1 than the full one: the full phone sends a Reconcile and waits for an answer.
        val limited = InMemorySyncStore(group).also { it.offer(envelope(1, 1, tag = 0)) }
        val full = InMemorySyncStore(group).also { it.offer(envelope(1, 1, tag = 5)) }
        val access = PeerAccess { Admission.Limited({ limited.summary() }, emptyList()) }
        val (rLimited, rFull) = sync(limited, full, engineA = SyncEngine(room, limited, access = access))
        assertEquals(SyncOutcome.COMPLETED, rLimited.outcome, "the limited initiator answers the request")
        assertEquals(SyncOutcome.COMPLETED, rFull.outcome, "and the full phone is not left to time out")
        assertEquals(1, versions(full, 1, 1).size, "the limited phone sent no versions of its own: the full phone still has only its own")
    }
}
