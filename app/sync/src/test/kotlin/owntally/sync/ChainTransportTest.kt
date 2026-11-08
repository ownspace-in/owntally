package owntally.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import owntally.model.PublicKey

/** T7.5: the chain of ways to reach a phone, on virtual time with pretend ways that take as long as the test says. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChainTransportTest {
    private val room = GroupRoom(ByteArray(16) { 1 })
    private val peer = PublicKey(ByteArray(32) { 2 })

    private class FakeLink(override val peer: PublicKey, override val kind: LinkKind, val name: String) : Link {
        var closed = false
        override suspend fun send(bytes: ByteArray) = Unit
        override val received: Flow<ByteArray> = Channel<ByteArray>().receiveAsFlow()
        override suspend fun close() { closed = true }
    }

    /** A way that takes [ms] of virtual time and then [outcome]s. */
    private class Way(val name: String, val kind: LinkKind, val ms: Long, val outcome: () -> Exception? = { null }) : Transport {
        var started = 0; var cancelled = 0; var startedAt = -1L
        val links = ArrayList<FakeLink>()
        val inbox = Channel<Link>(Channel.UNLIMITED)
        lateinit var now: () -> Long
        override suspend fun connect(room: GroupRoom, peer: PublicKey): Link {
            started++; startedAt = now()
            try { delay(ms) } catch (e: kotlinx.coroutines.CancellationException) { cancelled++; throw e }
            outcome()?.let { throw it }
            return FakeLink(peer, kind, name).also { links += it }
        }
        override fun incoming(room: GroupRoom): Flow<Link> = inbox.receiveAsFlow()
    }

    private fun TestScope.chain(local: Way?, iroh: Way?, relay: Way?, config: ChainConfig = ChainConfig()): ChainTransport {
        for (w in listOfNotNull(local, iroh, relay)) w.now = { currentTime }
        return ChainTransport(backgroundScope, local, iroh, relay, config)
    }

    private val unreachable = { PeerUnreachable(peer) as Exception }
    private val refused = { LinkAuthFailed("the other phone does not hold the key it should") as Exception }

    @Test fun aPhoneOnTheSameNetworkIsReachedWithoutTheInternetBeingTouched() = runTest {
        val local = Way("local", LinkKind.DIRECT, 500); val iroh = Way("iroh", LinkKind.DIRECT, 100); val relay = Way("relay", LinkKind.RELAYED, 100)
        val link = chain(local, iroh, relay).connect(room, peer)
        assertEquals(500, currentTime)
        assertEquals("local", (link as FakeLink).name)
        assertEquals(0, iroh.started + relay.started)
    }

    @Test fun whenTheSameNetworkSaysNoAtOnceIrohStartsAtOnceNotAfterTheWholeLook() = runTest {
        val local = Way("local", LinkKind.DIRECT, 200, unreachable); val iroh = Way("iroh", LinkKind.DIRECT, 1_000); val relay = Way("relay", LinkKind.RELAYED, 100)
        val link = chain(local, iroh, relay).connect(room, peer) as FakeLink
        assertEquals("iroh", link.name)
        assertEquals(200, iroh.startedAt, "Iroh started when the local way had given up, not after the full look")
        assertEquals(1_200, currentTime)
        assertEquals(0, relay.started, "the relay's 3 seconds were not up")
    }

    @Test fun aSameNetworkLinkThatTakesLongerThanTheLookIsLeftForTheInternet() = runTest {
        val local = Way("local", LinkKind.DIRECT, 5_000); val iroh = Way("iroh", LinkKind.DIRECT, 300)
        val link = chain(local, iroh, null).connect(room, peer) as FakeLink
        assertEquals("iroh", link.name)
        assertEquals(2_000, iroh.startedAt, "after the 2 second look")
        assertEquals(1, local.cancelled)
    }

    @Test fun whenIrohIsSlowTheRelayJoinsAfterThreeSecondsAndTheFirstToConnectWins() = runTest {
        val iroh = Way("iroh", LinkKind.DIRECT, 9_000); val relay = Way("relay", LinkKind.RELAYED, 500)
        val link = chain(null, iroh, relay).connect(room, peer) as FakeLink
        assertEquals("relay", link.name); assertEquals(LinkKind.RELAYED, link.kind)
        assertEquals(3_000, relay.startedAt)
        assertEquals(3_500, currentTime)
        assertEquals(1, iroh.cancelled, "the other is cancelled")
    }

    @Test fun whenIrohConnectsAfterTheRelayHasStartedAndBeforeItConnectsIrohWinsAndTheRelayIsCancelled() = runTest {
        val iroh = Way("iroh", LinkKind.DIRECT, 3_200); val relay = Way("relay", LinkKind.RELAYED, 400)
        val link = chain(null, iroh, relay).connect(room, peer) as FakeLink
        assertEquals("iroh", link.name)
        assertEquals(3_200, currentTime)
        assertEquals(1, relay.cancelled)
    }

    @Test fun twoLinksThatConnectAtTheSameMomentLeaveOneAndCloseTheOther() = runTest {
        val iroh = Way("iroh", LinkKind.DIRECT, 3_500); val relay = Way("relay", LinkKind.RELAYED, 500) // relay starts at 3,000 and ends at 3,500 too
        val link = chain(null, iroh, relay).connect(room, peer) as FakeLink
        runCurrent()
        val all = iroh.links + relay.links
        assertTrue(all.count { !it.closed } == 1 && !link.closed, "exactly one link stays open: ${all.map { it.name to it.closed }}")
    }

    @Test fun whenIrohFailsOutrightTheRelayStartsAtOnceNotAfterThreeSeconds() = runTest {
        val iroh = Way("iroh", LinkKind.DIRECT, 400, unreachable); val relay = Way("relay", LinkKind.RELAYED, 300)
        val link = chain(null, iroh, relay).connect(room, peer) as FakeLink
        assertEquals("relay", link.name)
        assertEquals(400, relay.startedAt)
        assertEquals(700, currentTime)
    }

    @Test fun whenNothingConnectsThePhoneIsUnreachableAndTheTimeIsNotFifteenSecondsWhenAllHaveGivenUp() = runTest {
        val local = Way("local", LinkKind.DIRECT, 100, unreachable); val iroh = Way("iroh", LinkKind.DIRECT, 500, unreachable); val relay = Way("relay", LinkKind.RELAYED, 300, unreachable)
        assertFailsWith<PeerUnreachable> { chain(local, iroh, relay).connect(room, peer) }
        assertEquals(100 + 500 + 300, currentTime, "the relay started when Iroh gave up and everything was over soon after")
    }

    @Test fun aWayThatNeverAnswersEndsAtTheFifteenSecondLimitAndEveryWayIsCancelled() = runTest {
        val iroh = Way("iroh", LinkKind.DIRECT, 600_000); val relay = Way("relay", LinkKind.RELAYED, 600_000)
        assertFailsWith<PeerUnreachable> { chain(null, iroh, relay).connect(room, peer) }
        assertEquals(15_000, currentTime)
        assertEquals(1, iroh.cancelled); assertEquals(1, relay.cancelled)
    }

    @Test fun aRefusalOnOneWayStillLetsTheOthersConnect() = runTest {
        val iroh = Way("iroh", LinkKind.DIRECT, 200, refused); val relay = Way("relay", LinkKind.RELAYED, 300)
        val link = chain(null, iroh, relay).connect(room, peer) as FakeLink
        assertEquals("relay", link.name, "every way proves the key by itself, so the relay is still tried")
    }

    @Test fun aRefusalOnTheSameNetworkLeavesTheInternetToTryAndIsReportedIfNothingElseConnects() = runTest {
        val local = Way("local", LinkKind.DIRECT, 100, refused); val iroh = Way("iroh", LinkKind.DIRECT, 300)
        assertEquals("iroh", (chain(local, iroh, null).connect(room, peer) as FakeLink).name)
        val local2 = Way("local", LinkKind.DIRECT, 100, refused); val iroh2 = Way("iroh", LinkKind.DIRECT, 300, unreachable)
        assertFailsWith<LinkAuthFailed> { chain(local2, iroh2, null).connect(room, peer) }
    }

    @Test fun aRefusalWithNothingElseConnectingIsWhatIsReported() = runTest {
        val iroh = Way("iroh", LinkKind.DIRECT, 200, refused); val relay = Way("relay", LinkKind.RELAYED, 300, unreachable)
        assertFailsWith<LinkAuthFailed> { chain(null, iroh, relay).connect(room, peer) }
    }

    @Test fun aChainOfWaysThatAreNotAvailableSaysNotAvailableAndNotUnreachable() = runTest {
        val unavailable = { TransportUnavailable() as Exception }
        assertFailsWith<TransportUnavailable> { chain(null, Way("iroh", LinkKind.DIRECT, 10, unavailable), Way("relay", LinkKind.RELAYED, 10, unavailable)).connect(room, peer) }
    }

    @Test fun aChainWithNoInternetWaysIsJustTheLocalOne() = runTest {
        val local = Way("local", LinkKind.DIRECT, 100)
        assertEquals("local", (chain(local, null, null).connect(room, peer) as FakeLink).name)
        assertFailsWith<PeerUnreachable> { chain(Way("l", LinkKind.DIRECT, 100, unreachable), null, null).connect(room, peer) }
    }

    @Test fun connectionsFromEveryWayArriveTogetherAndOnlyOnce() = runTest {
        val local = Way("local", LinkKind.DIRECT, 0); val iroh = Way("iroh", LinkKind.DIRECT, 0); val relay = Way("relay", LinkKind.RELAYED, 0)
        val c = chain(local, iroh, relay)
        val flow = c.incoming(room)
        val a = FakeLink(peer, LinkKind.DIRECT, "a"); val b = FakeLink(peer, LinkKind.RELAYED, "b"); val d = FakeLink(peer, LinkKind.DIRECT, "d")
        local.inbox.send(a); relay.inbox.send(b); iroh.inbox.send(d)
        runCurrent()
        val got = ArrayList<Link>()
        val job = launch { flow.collect { got += it } }
        runCurrent()
        assertEquals(setOf("a", "b", "d"), got.map { (it as FakeLink).name }.toSet())
        assertEquals(3, got.size)
        job.cancel()
        assertSame(c.incoming(room).let { flow }, flow)
    }
}
