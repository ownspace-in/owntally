package owntally.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import owntally.crypto.EnvelopeSigning
import owntally.crypto.KeyDerivation
import owntally.crypto.MasterSeed
import owntally.model.GroupId
import owntally.model.PublicKey

/** T7.3: authenticated links through a carrier that forwards frames to a key and proves nothing (the relay), over TLS end to end. */
class RelayLinkTest {
    private val seed = MasterSeed(ByteArray(64) { 8 })
    private val a = KeyDerivation.groupIdentity(seed, 0)
    private val b = KeyDerivation.groupIdentity(seed, 1)
    private val m = KeyDerivation.groupIdentity(seed, 2)
    private val d = KeyDerivation.groupIdentity(seed, 3)
    private val group = GroupId(ByteArray(16) { 3 })
    private val room = GroupRoom.fromGroupId(group.bytes())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val net = InMemoryNetwork()
    private fun run(block: suspend CoroutineScope.() -> Unit): Unit = try { runBlocking { withTimeout(40_000) { block() } } } finally { scope.cancel() }

    private fun party(id: owntally.crypto.GroupIdentity, carrierKey: PublicKey = id.publicKey, maxFrame: Int = 1_000, wrap: (Transport) -> Transport = { it }) =
        SecureOverCarrier(wrap(net.transportFor(carrierKey, LinkKind.RELAYED)), id, scope, maxFrame = maxFrame)

    private suspend fun connected(): Pair<Link, Link> {
        val tb = party(b); val incoming = tb.incoming(room)
        val link = party(a).connect(room, b.publicKey)
        return link to withTimeout(10_000) { incoming.first() }
    }

    @Test fun twoPhonesGetALinkThatSaysWhoTheOtherIsAndCarriesMessagesBothWays() = run {
        val (ab, ba) = connected()
        assertEquals(b.publicKey, ab.peer); assertEquals(a.publicKey, ba.peer)
        assertEquals(LinkKind.RELAYED, ab.kind); assertEquals(LinkSecurity.TLS, ab.security); assertEquals(LinkSecurity.TLS, ba.security)
        ab.send(byteArrayOf(1, 2)); ba.send(byteArrayOf(9))
        assertContentEquals(byteArrayOf(1, 2), withTimeout(5_000) { ba.received.first() })
        assertContentEquals(byteArrayOf(9), withTimeout(5_000) { ab.received.first() })
    }

    @Test fun aLargeMessageCrossesInFramesTheCarrierAccepts() = run {
        val frames = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val tb = party(b, maxFrame = 16_000); val incoming = tb.incoming(room)
        val link = party(a, maxFrame = 16_000, wrap = { inner -> object : Transport {
            override suspend fun connect(room: GroupRoom, peer: PublicKey): Link = inner.connect(room, peer).let { raw -> object : Link by raw {
                override suspend fun send(bytes: ByteArray) { frames += bytes.size; raw.send(bytes) }
            } }
            override fun incoming(room: GroupRoom) = inner.incoming(room)
        } }).connect(room, b.publicKey)
        val other = withTimeout(10_000) { incoming.first() }
        val big = ByteArray(Frames.MAX) { (it * 7).toByte() }
        link.send(big)
        assertContentEquals(big, withTimeout(30_000) { other.received.first { it.size > 100 } })
        assertTrue(frames.all { it <= 16_000 }, "every frame fits the carrier: ${frames.max()}")
        assertTrue(frames.size > 60, "in many frames: ${frames.size}")
    }

    @Test fun twoLinksBetweenTheSamePairAtOnceStayApart() = run {
        val tb = party(b); val incoming = tb.incoming(room)
        val ta = party(a)
        val l1 = ta.connect(room, b.publicKey); val o1 = withTimeout(10_000) { incoming.first() }
        val l2 = ta.connect(room, b.publicKey); val o2 = withTimeout(10_000) { incoming.first { it !== o1 } }
        l1.send(byteArrayOf(1)); l2.send(byteArrayOf(2))
        assertEquals(1, withTimeout(5_000) { o1.received.first() }[0].toInt())
        assertEquals(2, withTimeout(5_000) { o2.received.first() }[0].toInt())
        l1.close()
        assertEquals(emptyList(), withTimeout(5_000) { o1.received.toList() }, "closing one ends only that one")
        l2.send(byteArrayOf(3)); assertEquals(3, withTimeout(5_000) { o2.received.first() }[0].toInt())
    }

    @Test fun aWholeSyncRunsOverTheLink() = run {
        val (ab, ba) = connected()
        fun env(n: Int, c: Long): ByteArray = EnvelopeSigning.sign(KeyDerivation.groupIdentity(seed, 100L + n), 1, group, 1, c, ByteArray(24), ByteArray(60) { (c + it).toByte() }).encode()
        val sa = InMemorySyncStore(group).also { s -> (1L..40L).forEach { s.offer(env(1, it)) } }
        val sb = InMemorySyncStore(group).also { s -> (1L..25L).forEach { s.offer(env(2, it)) } }
        coroutineScope {
            val x = async { SyncEngine(room, sa).run(ab, SyncRole.INITIATOR) }
            val y = async { SyncEngine(room, sb).run(ba, SyncRole.RESPONDER) }
            val rx = x.await()
            assertEquals(SyncOutcome.COMPLETED, rx.outcome); assertEquals(SyncOutcome.COMPLETED, y.await().outcome)
            assertEquals(LinkKind.RELAYED, rx.via); assertEquals(LinkSecurity.TLS, rx.security)
        }
        assertEquals(sa.summary(), sb.summary())
    }

    // ---- who it will talk to ------------------------------------------------------------------------------------------------------

    @Test fun aPhoneAnsweringUnderAnotherKeyWithItsOwnCertificateIsRefused() = run {
        // Mallory connects to the carrier as Ben's key but can only show a certificate bound to her own member key.
        party(m, carrierKey = b.publicKey).incoming(room)
        assertFailsWith<LinkAuthFailed> { party(a).connect(room, b.publicKey) }
    }

    @Test fun aLinkWhoseCertificateNamesAnotherMemberThanTheCarrierSaysIsNotHandedOn() = run {
        val tb = party(b); val incoming = tb.incoming(room)
        // The carrier says the sender is Mallory's key, but the certificate is Dave's: the two must agree.
        runCatching { party(d, carrierKey = m.publicKey).connect(room, b.publicKey) }
        assertNull(withTimeoutOrNull(1_500) { incoming.first() })
        // The honest one still gets in.
        party(a).connect(room, b.publicKey)
        assertEquals(a.publicKey, withTimeout(10_000) { incoming.first() }.peer)
    }

    @Test fun aCarrierThatChangesAFrameCannotChangeWhatIsDelivered() = run {
        var tamper = false
        val tb = party(b); val incoming = tb.incoming(room)
        val link = party(a, wrap = { inner -> object : Transport {
            override suspend fun connect(room: GroupRoom, peer: PublicKey): Link = inner.connect(room, peer).let { raw -> object : Link by raw {
                override suspend fun send(bytes: ByteArray) { raw.send(if (tamper) bytes.copyOf().also { it[bytes.size - 3] = (it[bytes.size - 3].toInt() xor 1).toByte() } else bytes) }
            } }
            override fun incoming(room: GroupRoom) = inner.incoming(room)
        } }).connect(room, b.publicKey)
        val other = withTimeout(10_000) { incoming.first() }
        link.send(byteArrayOf(1)); assertEquals(1, withTimeout(5_000) { other.received.first() }[0].toInt())
        tamper = true
        // The other end may already be closing the stream when the send finishes, so the send itself can fail ("the stream is closed"): what the test is
        // about is what is delivered, not whether this call throws (found when it failed once in CI under load).
        runCatching { link.send(byteArrayOf(2, 2, 2, 2)) }
        // The changed record is refused by TLS: nothing arrives, and the link ends rather than carry on with something altered.
        assertEquals(emptyList(), withTimeout(5_000) { other.received.toList() })
    }

    @Test fun aCarrierThatChangesTheHandshakeGivesNoLink() = run {
        val tb = party(b); val incoming = tb.incoming(room)
        assertFailsWith<Exception> {
            party(a, wrap = { inner -> object : Transport {
                override suspend fun connect(room: GroupRoom, peer: PublicKey): Link = inner.connect(room, peer).let { raw -> object : Link by raw {
                    override suspend fun send(bytes: ByteArray) { raw.send(bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }) }
                } }
                override fun incoming(room: GroupRoom) = inner.incoming(room)
            } }).connect(room, b.publicKey)
        }
        assertNull(withTimeoutOrNull(1_500) { incoming.first() })
    }

    @Test fun frameInjectedByAThirdPartyDoesNotReachAnExistingLink() = run {
        val (ab, ba) = connected()
        // Mallory has her own path to Ben and sends garbage on every stream id she can guess: it is a different path, so a different set of streams.
        val mallory = net.transportFor(m.publicKey, LinkKind.RELAYED).connect(room, b.publicKey)
        repeat(20) { mallory.send(byteArrayOf(0, 0, 0, it.toByte(), 0, 1, 2, 3)) }
        ab.send(byteArrayOf(5))
        assertEquals(5, withTimeout(5_000) { ba.received.first() }[0].toInt())
    }

    @Test fun aPeerThatIsNotThereIsUnreachable() = run {
        assertFailsWith<PeerUnreachable> { party(a).connect(room, b.publicKey) }
    }

    @Test fun aSilentCarrierPeerTimesTheCallerOut() = run {
        // Ben's side takes the carrier connection but never answers the TLS handshake.
        net.transportFor(b.publicKey, LinkKind.RELAYED).incoming(room)
        val started = System.currentTimeMillis()
        assertFailsWith<Exception> { SecureOverCarrier(net.transportFor(a.publicKey, LinkKind.RELAYED), a, scope, maxFrame = 1_000, handshakeTimeoutMs = 800).connect(room, b.publicKey) }
        assertTrue(System.currentTimeMillis() - started < 8_000)
    }

    // ---- the relay's rate -----------------------------------------------------------------------------------------------------------

    @Test fun aLinkSendsToTheCarrierNoFasterThanItsRate() = run {
        val sendTimes = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val tb = SecureOverCarrier(net.transportFor(b.publicKey, LinkKind.RELAYED), b, scope, maxFrame = 1_000, maxFramesPerSecond = 100)
        val incoming = tb.incoming(room)
        val slow = SecureOverCarrier(object : Transport {
            override suspend fun connect(room: GroupRoom, peer: PublicKey): Link = net.transportFor(a.publicKey, LinkKind.RELAYED).connect(room, peer).let { raw -> object : Link by raw {
                override suspend fun send(bytes: ByteArray) { sendTimes += System.nanoTime(); raw.send(bytes) }
            } }
            override fun incoming(room: GroupRoom) = net.transportFor(a.publicKey, LinkKind.RELAYED).incoming(room)
        }, a, scope, maxFrame = 1_000, maxFramesPerSecond = 100)
        val link = slow.connect(room, b.publicKey)
        val other = withTimeout(10_000) { incoming.first() }
        sendTimes.clear()
        link.send(ByteArray(250_000) { it.toByte() })
        withTimeout(30_000) { other.received.first { it.size > 100 } }
        val seconds = (sendTimes.last() - sendTimes.first()) / 1e9
        val rate = (sendTimes.size - 1) / seconds
        assertTrue(sendTimes.size > 250, "many frames: ${sendTimes.size}")
        assertTrue(rate < 130, "no faster than 100 a second after the burst: $rate")
    }

    @Test fun thePacerAllowsABurstThenOnlyTheRateAndNeverMoreThanTheRelayTakes() {
        var now = 0L; var slept = 0L
        val pacer = FramePacer(maxPerSecond = 40, burst = 20, nanos = { now }, sleep = { ms -> slept += ms; now += ms * 1_000_000 })
        runBlocking { repeat(20) { pacer.acquire() } }
        assertEquals(0L, slept, "the burst goes at once")
        runBlocking { repeat(200) { pacer.acquire() } }
        // 200 more at 40 a second take about 5 seconds.
        assertTrue(slept in 4_800..5_400, "slept $slept ms")
        assertTrue(200 * 1000.0 / slept <= 42.0, "never faster than the rate: ${200 * 1000.0 / slept} a second")
    }

    @Test fun theMuxKeepsAnIdlePeriodFromBuildingAHugeBurst() {
        var now = 0L; var slept = 0L
        val pacer = FramePacer(maxPerSecond = 40, burst = 20, nanos = { now }, sleep = { ms -> slept += ms; now += ms * 1_000_000 })
        now += 60_000L * 1_000_000 // a minute of doing nothing
        runBlocking { repeat(100) { pacer.acquire() } }
        assertTrue(slept >= 1_900, "only a burst of 20 is saved up, not a minute's worth: slept $slept ms")
    }
}
