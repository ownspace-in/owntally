package owntally.sync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import owntally.crypto.EnvelopeSigning
import owntally.crypto.KeyDerivation
import owntally.crypto.MasterSeed
import owntally.model.GroupId
import owntally.model.PublicKey

/**
 * M10, T10.8, finding F3 (docs/SECURITY_REVIEW.md): a peer that sends faster than the phone stores must not make the phone's memory grow without
 * limit. The engine reads from the link only as fast as it works (the queue between them is bounded), so a flood waits in the sender.
 */
class InboundQueueTest {
    private val group = GroupId(ByteArray(16) { 3 })
    private val room = GroupRoom.fromGroupId(group.bytes())
    private val identity = KeyDerivation.groupIdentity(MasterSeed(ByteArray(64) { 7 }), 1)

    private class FloodingLink(override val peer: PublicKey, val messages: Flow<ByteArray>) : Link {
        override val kind = LinkKind.RELAYED
        override suspend fun send(bytes: ByteArray) {}
        override val received: Flow<ByteArray> = messages
        override suspend fun close() {}
    }

    /** A store that holds the first offered envelope until released, like a phone busy writing to its database. */
    private class StuckStore(group: GroupId, val release: CountDownLatch, val entered: CountDownLatch) : SyncStore {
        private val inner = InMemorySyncStore(group)
        override fun summary() = inner.summary()
        override fun versionsAbove(author: PublicKey, above: Long) = inner.versionsAbove(author, above)
        override fun fingerprintAt(author: PublicKey, counter: Long) = inner.fingerprintAt(author, counter)
        override fun offer(bytes: ByteArray): Offered {
            entered.countDown()
            release.await(20, TimeUnit.SECONDS)
            return inner.offer(bytes)
        }
    }

    @Test fun aFloodingPeerCannotMakeTheEngineQueueWithoutLimit() = runBlocking {
        val envelope = EnvelopeSigning.sign(identity, 1, group, 1, 1, ByteArray(24), ByteArray(80)).encode()
        val hello = SyncCodec.encode(SyncMessage.Hello(room, SyncSummary(emptyMap())))
        val batch = SyncCodec.encode(SyncMessage.Envelopes(listOf(envelope)))
        val emitted = AtomicInteger()
        val flood = flow {
            emit(hello)
            while (true) { emitted.incrementAndGet(); emit(batch) }
        }
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val engine = SyncEngine(room, StuckStore(group, release, entered), maxBadEnvelopes = Int.MAX_VALUE)
        val run: Job = launch(Dispatchers.Default) { engine.run(FloodingLink(PublicKey(ByteArray(32) { 9 }), flood), SyncRole.RESPONDER) }
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the engine reached the store")
        Thread.sleep(1_000) // a flood has had its chance
        val queued = emitted.get()
        release.countDown()
        run.cancelAndJoin()
        assertTrue(queued <= LinkQueue.CAPACITY + 10, "the engine let $queued messages pile up while it was busy (limit ${LinkQueue.CAPACITY})")
    }
}
