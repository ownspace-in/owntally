package owntally.sync

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import owntally.crypto.EnvelopeSigning
import owntally.crypto.KeyDerivation
import owntally.crypto.MasterSeed
import owntally.model.GroupId
import owntally.model.PublicKey

/** T7.4: the Iroh link through a pretend Iroh (QUIC streams in memory, delivered a few bytes at a time like a real one). */
class IrohTransportTest {
    /** The pretend internet: ports that can be found by key once they have started and published. */
    private class World {
        val ports = ConcurrentHashMap<PublicKey, FakePort>()
        @Volatile var direct = false
        @Volatile var slice = 7 // bytes handed over at a time
    }

    private class Pipe(private val world: World) {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        private var pending = ByteArray(0)
        fun write(bytes: ByteArray) { var i = 0; while (i < bytes.size) { val n = minOf(world.slice, bytes.size - i); chunks.trySend(bytes.copyOfRange(i, i + n)); i += n } }
        suspend fun readExact(n: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            while (out.size() < n) {
                if (pending.isEmpty()) pending = chunks.receiveCatching().getOrNull() ?: throw IOException("the stream ended")
                val take = minOf(n - out.size(), pending.size)
                out.write(pending, 0, take); pending = pending.copyOfRange(take, pending.size)
            }
            return out.toByteArray()
        }
        fun end() { chunks.close() }
    }

    private class FakeConnection(override val remote: PublicKey, private val world: World, val out: Pipe, val inn: Pipe) : IrohConnection {
        override val isDirect: Boolean get() = world.direct
        override suspend fun write(bytes: ByteArray) = out.write(bytes)
        override suspend fun readExact(n: Int) = inn.readExact(n)
        override suspend fun close() { out.end() }
    }

    private class Ready(private val conn: IrohConnection) : IncomingIroh {
        override suspend fun open() = conn
        override fun abandon() {}
    }

    /** An incoming connection whose other end never opens its stream: [open] waits until it is cancelled. */
    private class Silent : IncomingIroh {
        @Volatile var abandoned = false
        @Volatile var opened = false
        override suspend fun open(): IrohConnection { opened = true; kotlinx.coroutines.awaitCancellation() }
        override fun abandon() { abandoned = true }
    }

    private class FakePort(val key: PublicKey, private val world: World, var publishedAtMs: Long = 0, var claimsRemote: PublicKey? = null, var failStart: Boolean = false, var answersAs: PublicKey? = null) : IrohPort {
        private val accepted = Channel<IncomingIroh>(Channel.UNLIMITED)
        @Volatile var started = false
        @Volatile var stopped = false
        val startedAtNanos = System.nanoTime()
        var connectAttempts = 0

        override suspend fun start(): PublicKey {
            if (failStart) throw IOException("could not bind")
            started = true; world.ports[key] = this
            return key
        }

        override suspend fun connect(peer: PublicKey, relayHint: String?): IrohConnection {
            connectAttempts++
            val target = world.ports[peer]
            if (target == null || !target.started || (System.nanoTime() - target.startedAtNanos) / 1_000_000 < target.publishedAtMs) throw IOException("no address for the peer yet")
            val a = Pipe(world); val b = Pipe(world)
            target.accepted.trySend(Ready(FakeConnection(claimsRemote ?: key, world, out = b, inn = a)))
            return FakeConnection(target.answersAs ?: peer, world, out = a, inn = b)
        }

        override fun incoming(): Flow<IncomingIroh> = accepted.receiveAsFlow()
        /** Someone who connects and never opens the stream (F7). */
        fun silentStranger(): Silent = Silent().also { accepted.trySend(it) }
        override suspend fun stop() { stopped = true; world.ports.remove(key) }
    }

    private val seed = MasterSeed(ByteArray(64) { 8 })
    private val a = KeyDerivation.groupIdentity(seed, 0).publicKey
    private val b = KeyDerivation.groupIdentity(seed, 1).publicKey
    private val m = KeyDerivation.groupIdentity(seed, 2).publicKey
    private val group = GroupId(ByteArray(16) { 3 })
    private val room = GroupRoom.fromGroupId(group.bytes())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val world = World()
    private fun run(block: suspend CoroutineScope.() -> Unit): Unit = try { runBlocking { withTimeout(30_000) { block() } } } finally { scope.cancel() }
    private fun transport(port: FakePort, window: Long = 3_000, retry: Long = 100) = IrohTransport(port, scope, IrohConfig(connectWindowMs = window, retryMs = retry))

    private suspend fun connected(): Pair<Link, Link> {
        val tb = transport(FakePort(b, world)); val incoming = tb.incoming(room)
        delay(100)
        val link = transport(FakePort(a, world)).connect(room, b)
        return link to withTimeout(10_000) { incoming.first() }
    }

    @Test fun twoPhonesGetALinkThatNamesTheAuthenticatedKeyAndSaysDirectOrRelayedAsThePathChanges() = run {
        val (ab, ba) = connected()
        assertEquals(b, ab.peer); assertEquals(a, ba.peer)
        assertEquals(LinkSecurity.TLS, ab.security)
        assertEquals(LinkKind.RELAYED, ab.kind, "at first through the relay")
        world.direct = true
        assertEquals(LinkKind.DIRECT, ab.kind, "then direct once hole punching worked: the link says what it is now")
        assertEquals(LinkKind.DIRECT, ba.kind)
    }

    @Test fun messagesCrossBothWaysEvenWhenTheStreamHandsOverAFewBytesAtATime() = run {
        val (ab, ba) = connected()
        world.slice = 1
        ab.send(byteArrayOf(1, 2, 3)); ba.send(byteArrayOf(9)); ab.send(ByteArray(0)); ab.send(byteArrayOf(4))
        assertContentEquals(byteArrayOf(1, 2, 3), withTimeout(5_000) { ba.received.first() })
        assertContentEquals(byteArrayOf(9), withTimeout(5_000) { ab.received.first() })
    }

    @Test fun anEmptyMessageIsNotDeliveredAndTheOpeningOneIsInvisible() = run {
        val (ab, ba) = connected()
        ab.send(byteArrayOf(5))
        assertContentEquals(byteArrayOf(5), withTimeout(5_000) { ba.received.first() }, "the first thing the other side sees is the first real message")
    }

    @Test fun aLargeMessageCrossesAndOneOverTheLimitIsRefused() = run {
        val (ab, ba) = connected()
        world.slice = 4_096
        val big = ByteArray(Frames.MAX) { (it * 3).toByte() }
        ab.send(big)
        assertContentEquals(big, withTimeout(20_000) { ba.received.first { it.size > 100 } })
        assertFailsWith<Frames.Bad> { ab.send(ByteArray(Frames.MAX + 1)) }
    }

    @Test fun messagesSentByTwoCoroutinesAtOnceNeverInterleave() = run {
        val (ab, ba) = connected()
        coroutineScope {
            launch { repeat(50) { ab.send(ByteArray(300) { 1 }) } }
            launch { repeat(50) { ab.send(ByteArray(500) { 2 }) } }
        }
        val got = ArrayList<ByteArray>()
        withTimeout(10_000) { ba.received.first { got += it; got.size == 100 } }
        assertEquals(50, got.count { it.size == 300 && it.all { b -> b.toInt() == 1 } })
        assertEquals(50, got.count { it.size == 500 && it.all { b -> b.toInt() == 2 } })
    }

    @Test fun aWholeSyncRunsOverTheLink() = run {
        val (ab, ba) = connected()
        fun env(n: Int, c: Long): ByteArray = EnvelopeSigning.sign(KeyDerivation.groupIdentity(seed, 100L + n), 1, group, 1, c, ByteArray(24), ByteArray(60) { (c + it).toByte() }).encode()
        val sa = InMemorySyncStore(group).also { s -> (1L..40L).forEach { s.offer(env(1, it)) } }
        val sb = InMemorySyncStore(group).also { s -> (1L..25L).forEach { s.offer(env(2, it)) } }
        world.slice = 512
        coroutineScope {
            val x = async { SyncEngine(room, sa).run(ab, SyncRole.INITIATOR) }
            val y = async { SyncEngine(room, sb).run(ba, SyncRole.RESPONDER) }
            assertEquals(SyncOutcome.COMPLETED, x.await().outcome); assertEquals(SyncOutcome.COMPLETED, y.await().outcome)
        }
        assertEquals(sa.summary(), sb.summary())
    }

    // ---- finding and trusting the other phone ------------------------------------------------------------------------------------------

    @Test fun anAddressThatIsNotPublishedYetIsRetriedUntilItAppears() = run {
        val pb = FakePort(b, world, publishedAtMs = 700)
        val tb = transport(pb); val incoming = tb.incoming(room)
        val pa = FakePort(a, world)
        val link = transport(pa).connect(room, b)
        assertTrue(pa.connectAttempts > 2, "tried again: ${pa.connectAttempts}")
        assertEquals(b, link.peer)
        assertEquals(a, withTimeout(10_000) { incoming.first() }.peer)
    }

    @Test fun aPeerThatNeverAppearsIsUnreachableAfterTheWindow() = run {
        val started = System.currentTimeMillis()
        assertFailsWith<PeerUnreachable> { transport(FakePort(a, world), window = 800).connect(room, b) }
        assertTrue(System.currentTimeMillis() - started in 700..4_000)
    }

    @Test fun aPhoneThatShowsAnotherKeyThanTheOneDialledIsRefusedAndNotRetried() = run {
        // The connection that comes back has an authenticated remote that is not who we asked for.
        val pb = FakePort(b, world, answersAs = m)
        val tb = transport(pb); tb.incoming(room); delay(100)
        val pa = FakePort(a, world)
        assertFailsWith<LinkAuthFailed> { transport(pa).connect(room, b) }
        assertEquals(1, pa.connectAttempts, "a refusal is not retried")
    }

    @Test fun aPhoneCannotDialItself() = run {
        assertFailsWith<PeerUnreachable> { transport(FakePort(a, world)).connect(room, a) }
    }

    @Test fun aFailedStartIsUnreachableNotACrash() = run {
        assertFailsWith<PeerUnreachable> { transport(FakePort(a, world, failStart = true)).connect(room, b) }
    }

    @Test fun whenOneSideClosesTheOthersMessagesEnd() = run {
        val (ab, ba) = connected()
        ab.send(byteArrayOf(7)); ab.close()
        assertEquals(listOf(7), withTimeout(5_000) { ba.received.toList().map { it[0].toInt() } })
    }

    @Test fun aLengthPrefixNoSenderWouldMakeEndsTheLinkInsteadOfAllocatingIt() = run {
        val pb = FakePort(b, world)
        val tb = transport(pb); val incoming = tb.incoming(room)
        delay(100)
        // A raw connection that writes a huge length by hand.
        val link = transport(FakePort(a, world)).connect(room, b)
        val other = withTimeout(10_000) { incoming.first() }
        val raw = (link as Any).let { l -> l.javaClass.getDeclaredField("conn").also { it.isAccessible = true }.get(l) as FakeConnection }
        raw.write(byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f))
        assertEquals(emptyList(), withTimeout(5_000) { other.received.toList() })
    }

    @Test fun stoppingShutsTheEndpointDown() = run {
        val p = FakePort(a, world)
        val t = transport(p)
        t.incoming(room); delay(100)
        t.stop()
        assertTrue(p.stopped)
    }

    // --- finding F7 (docs/SECURITY_REVIEW.md): a stranger who connects and says nothing must not hold up the others ---------------------

    @Test fun aSilentStrangerDoesNotHoldUpALaterConnection() = run {
        val pb = FakePort(b, world)
        val tb = transport(pb); val incoming = tb.incoming(room)
        delay(100)
        val stranger = pb.silentStranger()
        delay(100)
        val link = transport(FakePort(a, world)).connect(room, b)
        val got = withTimeout(2_000) { incoming.first() } // the default window is 10 s: this must not wait for it
        assertEquals(a, got.peer)
        assertTrue(stranger.opened && !stranger.abandoned)
        link.close()
    }

    @Test fun aStrangerWhoNeverOpensTheStreamIsDroppedAfterTheWindow() = run {
        val pb = FakePort(b, world)
        val tb = IrohTransport(pb, scope, IrohConfig(acceptWindowMs = 300)); tb.incoming(room)
        delay(100)
        val stranger = pb.silentStranger()
        delay(1_000)
        assertTrue(stranger.abandoned, "dropped once the window had passed")
    }

    @Test fun moreSilentStrangersThanTheLimitAreDroppedAtOnce() = run {
        val pb = FakePort(b, world)
        val tb = IrohTransport(pb, scope, IrohConfig(acceptWindowMs = 5_000, maxPendingAccepts = 2)); tb.incoming(room)
        delay(100)
        val strangers = List(5) { pb.silentStranger() }
        delay(500)
        assertEquals(3, strangers.count { it.abandoned }, "two wait, the other three are dropped without waiting")
        assertEquals(2, strangers.count { it.opened })
    }
}
