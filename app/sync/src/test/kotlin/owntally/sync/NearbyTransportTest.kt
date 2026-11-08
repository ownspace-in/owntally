package owntally.sync

import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import owntally.crypto.EnvelopeSigning
import owntally.crypto.KeyDerivation
import owntally.crypto.LinkAuth
import owntally.crypto.MasterSeed
import owntally.model.GroupId

/** T6.10: Nearby sync through a pretend Nearby (the real one needs two phones with Bluetooth, which the owner checks). */
class NearbyTransportTest {
    /** The air between phones: who advertises, who looks, and the connections between them. Payloads are delivered whole and in order, never over [MAX]. */
    private class Air {
        val devices = ConcurrentHashMap<String, FakePort>()
        fun port(id: String) = FakePort(id, this).also { devices[id] = it }
    }

    private class FakePort(val id: String, private val air: Air) : NearbyPort {
        override val maxPayload = 32_768
        private val channel = Channel<NearbyEvent>(Channel.UNLIMITED)
        override val events: Flow<NearbyEvent> = channel.receiveAsFlow()
        @Volatile var advertisedAs: String? = null
        @Volatile var discovering = false
        @Volatile var stops = 0
        val accepted = ConcurrentHashMap.newKeySet<String>()
        val sent = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var biggest = 0

        fun emit(e: NearbyEvent) { channel.trySend(e) }

        override fun startAdvertising(name: String) {
            advertisedAs = name
            for (d in air.devices.values) if (d.discovering) d.emit(NearbyEvent.Found(id, name))
        }

        override fun startDiscovery() {
            discovering = true
            for (d in air.devices.values) d.advertisedAs?.let { emit(NearbyEvent.Found(d.id, it)) }
        }

        override fun stopAdvertising() { advertisedAs = null; stops++ }
        override fun stopDiscovery() { discovering = false; stops++ }

        override fun requestConnection(endpoint: String, ownName: String) {
            val other = air.devices[endpoint] ?: return emit(NearbyEvent.Result(endpoint, false))
            emit(NearbyEvent.Initiated(endpoint, other.advertisedAs ?: ""))
            other.emit(NearbyEvent.Initiated(id, ownName))
        }

        override fun acceptConnection(endpoint: String) {
            accepted += endpoint
            val other = air.devices[endpoint] ?: return
            if (endpoint in accepted && id in other.accepted) { emit(NearbyEvent.Result(endpoint, true)); other.emit(NearbyEvent.Result(id, true)) }
        }

        override fun send(endpoint: String, bytes: ByteArray) {
            check(bytes.size <= maxPayload) { "Nearby allows ${maxPayload} bytes, not ${bytes.size}" }
            sent.incrementAndGet(); biggest = maxOf(biggest, bytes.size)
            air.devices[endpoint]?.emit(NearbyEvent.Payload(id, bytes.copyOf()))
        }

        override fun disconnect(endpoint: String) {
            emit(NearbyEvent.Disconnected(endpoint))
            air.devices[endpoint]?.emit(NearbyEvent.Disconnected(id))
        }
    }

    private val seed = MasterSeed(ByteArray(64) { 8 })
    private val ids = listOf(KeyDerivation.groupIdentity(seed, 0), KeyDerivation.groupIdentity(seed, 1), KeyDerivation.groupIdentity(seed, 2)).sortedBy { it.publicKey }
    private val small = ids[0]; private val large = ids[1]; private val third = ids[2]
    private val group = GroupId(ByteArray(16) { 3 })
    private val room = GroupRoom.fromGroupId(group.bytes())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val air = Air()
    private val config = NearbyConfig(connectTimeoutMs = 1_500, handshakeTimeoutMs = 1_000)
    private fun transport(port: FakePort, who: owntally.crypto.GroupIdentity) = NearbyTransport(port, who, scope, config)
    private fun run(block: suspend CoroutineScope.() -> Unit): Unit = try { runBlocking { withTimeout(30_000) { block() } } } finally { scope.cancel() }

    private suspend fun connected(): Triple<Link, Link, Pair<NearbyTransport, NearbyTransport>> {
        val ta = transport(air.port("A"), small); val tb = transport(air.port("B"), large)
        val incoming = tb.incoming(room)
        val link = ta.connect(room, large.publicKey)
        return Triple(link, withTimeout(10_000) { incoming.first() }, ta to tb)
    }

    @Test fun thePhoneWithTheSmallerKeyOpensTheConnectionAndBothEndsKnowWhoTheOtherIs() = run {
        val (ab, ba, _) = connected()
        assertEquals(large.publicKey, ab.peer); assertEquals(small.publicKey, ba.peer)
        assertEquals(LinkKind.NEARBY, ab.kind)
        assertNull(ab.security)
        ab.send(byteArrayOf(1, 2)); ba.send(byteArrayOf(9))
        assertContentEquals(byteArrayOf(1, 2), withTimeout(5_000) { ba.received.first() })
        assertContentEquals(byteArrayOf(9), withTimeout(5_000) { ab.received.first() })
    }

    @Test fun aLargeMessageCrossesInChunksThatNearbyAccepts() = run {
        val (ab, ba, _) = connected()
        val big = ByteArray(Frames.MAX) { (it * 3).toByte() }
        ab.send(big)
        assertContentEquals(big, withTimeout(10_000) { ba.received.first { it.size > 100 } })
        assertTrue(air.devices["A"]!!.biggest <= 32_768)
        assertTrue(air.devices["A"]!!.sent.get() > 30, "in many payloads")
        assertFailsWith<Frames.Bad> { ab.send(ByteArray(Frames.MAX + 1)) }
    }

    @Test fun thePhoneWithTheLargerKeyDoesNotOpenAConnectionItWaitsToBeCalled() = run {
        val tb = transport(air.port("B"), large)
        assertFailsWith<TransportUnavailable> { tb.connect(room, small.publicKey) }
    }

    @Test fun aPhoneThatIsNotAdvertisingIsUnreachableAfterTheNearbyWait() = run {
        val ta = transport(air.port("A"), small)
        val started = System.currentTimeMillis()
        assertFailsWith<PeerUnreachable> { ta.connect(room, large.publicKey) }
        assertTrue(System.currentTimeMillis() - started in 1_000..6_000)
    }

    @Test fun aPhoneAdvertisingUnderTheRightNameWithAnotherKeyIsRefused() = run {
        val ta = transport(air.port("A"), small)
        val mallory = transport(air.port("M"), third)
        // Mallory advertises under the name Ben's phone would use (the name is only a hint), but she holds another key.
        val port = air.devices["M"]!!
        mallory.incoming(room)
        port.advertisedAs = NearbyNames.endpointName(room, large.publicKey)
        assertFailsWith<LinkAuthFailed> { ta.connect(room, large.publicKey) }
    }

    @Test fun aPhoneThatAnswersTheHandshakeWithAnotherMembersKeyIsRefused() = run {
        // Not just a wrong name: Mallory answers properly, signing the handshake with her own key where Ben's should be.
        val mallory = air.port("M")
        val chunker = Chunker(mallory.maxPayload - Chunker.HEADER)
        val reassembler = Reassembler()
        mallory.startAdvertising(NearbyNames.endpointName(room, large.publicKey))
        val serving = scope.async {
            val initiated = mallory.events.first { it is NearbyEvent.Initiated } as NearbyEvent.Initiated
            mallory.acceptConnection(initiated.endpoint)
            var message: ByteArray? = null
            mallory.events.first { e -> (e as? NearbyEvent.Payload)?.let { message = reassembler.add(it.bytes) }; message != null }
            val hello = LocalHandshake.Hello.decode(message!!)!!
            val nonce = LinkAuth.newNonce()
            val transcript = LocalHandshake.transcript(hello, LocalHandshake.KIND_PLAIN, nonce)
            chunker.split(LocalHandshake.encodeReply(LocalHandshake.KIND_PLAIN, nonce, LinkAuth.signAsServer(third, transcript))).forEach { mallory.send(initiated.endpoint, it) }
        }
        val e = assertFailsWith<LinkAuthFailed> { transport(air.port("A"), small).connect(room, large.publicKey) }
        assertTrue("does not hold the key" in e.message!!, e.message)
        serving.cancel()
    }

    @Test fun anotherGroupsNameIsNotFound() = run {
        val ta = transport(air.port("A"), small); val tb = transport(air.port("B"), large)
        tb.incoming(GroupRoom.fromGroupId(ByteArray(16) { 9 }))
        assertFailsWith<PeerUnreachable> { ta.connect(room, large.publicKey) }
    }

    /** A caller by hand, so its proof can be wrong. */
    private suspend fun rawCaller(portId: String, tb: NearbyTransport, from: ByteArray, signWith: owntally.crypto.GroupIdentity?, toKey: owntally.model.PublicKey = large.publicKey, inRoom: GroupRoom = room): FakePort {
        val port = air.port(portId)
        val chunker = Chunker(port.maxPayload - Chunker.HEADER)
        val reassembler = Reassembler()
        port.startDiscovery()
        val found = withTimeout(5_000) { port.events.first { it is NearbyEvent.Found } as NearbyEvent.Found }
        port.requestConnection(found.endpoint, "x")
        withTimeout(5_000) { port.events.first { it is NearbyEvent.Initiated } }
        port.acceptConnection(found.endpoint)
        withTimeout(5_000) { port.events.first { it is NearbyEvent.Result } }
        val hello = LocalHandshake.Hello(LocalHandshake.KIND_PLAIN, inRoom, owntally.model.PublicKey(from), toKey, LinkAuth.newNonce())
        chunker.split(hello.encode()).forEach { port.send(found.endpoint, it) }
        val reply = withTimeoutOrNull(1_500) {
            var m: ByteArray? = null
            port.events.first { e -> (e as? NearbyEvent.Payload)?.let { m = reassembler.add(it.bytes) }; m != null }
            LocalHandshake.decodeReply(m!!)
        }
        if (reply != null) {
            val transcript = LocalHandshake.transcript(hello, reply.chosen, reply.nonce)
            val sig = if (signWith != null) LinkAuth.signAsClient(signWith, transcript) else ByteArray(64)
            chunker.split(LocalHandshake.encodeProof(sig)).forEach { port.send(found.endpoint, it) }
        }
        return port
    }

    @Test fun aCallerThatCannotProveTheKeyItClaimsGetsNoLink() = run {
        val tb = transport(air.port("B"), large); val incoming = tb.incoming(room)
        rawCaller("X1", tb, small.publicKey.bytes(), signWith = third)
        assertNull(withTimeoutOrNull(1_200) { incoming.first() }, "signed by another key")
        rawCaller("X2", tb, small.publicKey.bytes(), signWith = null)
        assertNull(withTimeoutOrNull(1_200) { incoming.first() }, "a proof of zeros")
    }

    @Test fun aCallThatNamesAnotherKeyOrAnotherRoomGetsNoLinkAndARealCallerStillGetsIn() = run {
        val tb = transport(air.port("B"), large); val incoming = tb.incoming(room)
        rawCaller("X3", tb, small.publicKey.bytes(), signWith = small, toKey = third.publicKey)
        assertNull(withTimeoutOrNull(1_200) { incoming.first() }, "another key called")
        rawCaller("X4", tb, small.publicKey.bytes(), signWith = small, inRoom = GroupRoom(ByteArray(16) { 1 }))
        assertNull(withTimeoutOrNull(1_200) { incoming.first() }, "another room")
        val ta = transport(air.port("A"), small)
        ta.connect(room, large.publicKey)
        assertEquals(small.publicKey, withTimeout(10_000) { incoming.first() }.peer)
    }

    @Test fun aConnectionThatSaysNothingIsDroppedAndDoesNotBlockTheNextCaller() = run {
        val tb = transport(air.port("B"), large); val incoming = tb.incoming(room)
        val silent = air.port("S")
        silent.startDiscovery()
        val found = withTimeout(5_000) { silent.events.first { it is NearbyEvent.Found } as NearbyEvent.Found }
        silent.requestConnection(found.endpoint, "s")
        withTimeout(5_000) { silent.events.first { it is NearbyEvent.Initiated } }
        silent.acceptConnection(found.endpoint)
        withTimeout(5_000) { silent.events.first { it is NearbyEvent.Disconnected } } // dropped after the handshake wait
        transport(air.port("A"), small).connect(room, large.publicKey)
        assertEquals(small.publicKey, withTimeout(10_000) { incoming.first() }.peer)
    }

    @Test fun aWholeSyncRunsOverTheLink() = run {
        val (ab, ba, _) = connected()
        fun env(n: Int, c: Long): ByteArray = EnvelopeSigning.sign(KeyDerivation.groupIdentity(seed, 100L + n), 1, group, 1, c, ByteArray(24), ByteArray(60) { (c + it).toByte() }).encode()
        val sa = InMemorySyncStore(group).also { s -> (1L..40L).forEach { s.offer(env(1, it)) } }
        val sb = InMemorySyncStore(group).also { s -> (1L..25L).forEach { s.offer(env(2, it)) } }
        coroutineScope {
            val x = async { SyncEngine(room, sa).run(ab, SyncRole.INITIATOR) }
            val y = async { SyncEngine(room, sb).run(ba, SyncRole.RESPONDER) }
            val rx = x.await()
            assertEquals(SyncOutcome.COMPLETED, rx.outcome); assertEquals(SyncOutcome.COMPLETED, y.await().outcome)
            assertEquals(LinkKind.NEARBY, rx.via)
        }
        assertEquals(sa.summary(), sb.summary())
    }

    @Test fun stoppingEndsAdvertisingDiscoveryAndEveryConnection() = run {
        val (ab, ba, t) = connected()
        t.first.stop(); t.second.stop()
        assertNull(air.devices["B"]!!.advertisedAs)
        assertFalse(air.devices["A"]!!.discovering)
        withTimeout(5_000) { ba.received.collect { } } // the other end's messages end
        assertFailsWith<java.io.IOException> { ab.send(byteArrayOf(1)) }
    }

    @Test fun theEndpointNameSaysNothingAboutTheGroupOrThePerson() {
        val n = NearbyNames.endpointName(room, small.publicKey)
        assertEquals(20, n.length)
        assertFalse(small.publicKey.bytes().joinToString("") { "%02x".format(it) }.contains(n.take(8)))
        assertTrue(n != NearbyNames.endpointName(room, large.publicKey))
        assertTrue(n != LocalNames.serviceName(room, small.publicKey), "and is not the local name")
    }
}
