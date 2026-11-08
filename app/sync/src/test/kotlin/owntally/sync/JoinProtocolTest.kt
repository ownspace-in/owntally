package owntally.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import owntally.crypto.Clock
import owntally.crypto.GroupIdentity
import owntally.crypto.GroupKey
import owntally.crypto.JoinCrypto
import owntally.crypto.KeyDerivation
import owntally.crypto.MasterSeed
import owntally.crypto.Ticket
import owntally.crypto.TicketRegistry
import owntally.model.GroupId
import owntally.model.PublicKey

/** T6.2: the join handshake over the fake network. The tickets are real ones from the real registry. */
class JoinProtocolTest {
    private val seed = MasterSeed(ByteArray(64) { 4 })
    private val host = KeyDerivation.groupIdentity(seed, 0)
    private val joiner = KeyDerivation.groupIdentity(seed, 1)
    private val intruder = KeyDerivation.groupIdentity(seed, 2)
    private val group = GroupId(ByteArray(16) { 9 })
    private val key = GroupKey(ByteArray(32) { it.toByte() })
    private val room = GroupRoom.fromGroupId(group.bytes())
    private var now = 1_800_000_000L
    private val registry = TicketRegistry(host.publicKey, Clock { now })

    private inner class Backend(override val host: GroupIdentity = this@JoinProtocolTest.host, var accepts: Boolean = true, val leaky: Boolean = false) : JoinHostBackend {
        override val group = this@JoinProtocolTest.group
        val submitted = ArrayList<JoinRequestBody>()
        val statuses = HashMap<PublicKey, JoinStatus>()
        override fun issued(ticketId: ByteArray) = registry.find(ticketId)
        override fun consume(ticket: Ticket) = leaky || registry.accept(ticket)
        override fun submit(request: JoinRequestBody): Boolean { if (accepts) submitted += request; return accepts }
        override fun statusOf(joinerKey: PublicKey) = statuses[joinerKey] ?: JoinStatus.UNKNOWN
    }

    private fun ticket() = registry.issue(group, 1, key)
    private suspend fun links(): Pair<Link, Link> {
        val net = InMemoryNetwork()
        val incoming = net.transportFor(host.publicKey).incoming(room)
        val a = net.transportFor(joiner.publicKey).connect(room, host.publicKey)
        return a to incoming.first()
    }

    private suspend fun join(t: Ticket, backend: Backend, who: GroupIdentity = joiner, name: String = "Aarav", client: JoinClient = JoinClient(t, who, name, "98765 43210")): JoinOutcome {
        val (a, b) = links()
        return coroutineScope {
            val served = launch { JoinHost(backend, room).serve(b) }
            val r = client.requestToJoin(a)
            served.join()
            r
        }
    }

    @Test fun aJoinerWithAGoodTicketIsKeptAsARequestAndTheTicketIsUsedUp() = runTest {
        val backend = Backend(); val t = ticket()
        assertEquals(JoinOutcome.REQUESTED, join(t, backend))
        val req = backend.submitted.single()
        assertEquals(joiner.publicKey, req.joinerKey); assertEquals(joiner.encryptionKey, req.encryptionKey)
        assertEquals("Aarav", req.name); assertEquals("98765 43210", req.phone)
        assertNull(registry.find(t.ticketId()), "used up")
        assertEquals(JoinOutcome.REFUSED, join(t, backend), "a second use is refused")
        assertEquals(1, backend.submitted.size)
    }

    @Test fun expiredUnknownAndUsedTicketsAreAllRefusedTheSameWay() = runTest {
        val backend = Backend()
        val expired = ticket(); now += Ticket.LIFETIME_SECONDS + 1
        assertEquals(JoinOutcome.REFUSED, join(expired, backend))
        val unknown = Ticket(ByteArray(16) { 3 }, host.publicKey, group, 1, key, now + 1000, "")
        assertEquals(JoinOutcome.REFUSED, join(unknown, backend))
        assertEquals(0, backend.submitted.size)
    }

    @Test fun aPeerThatIsNotTheHostNamedInTheTicketIsNeverSentTheRequest() = runTest {
        val t = ticket()
        val impostor = Backend(host = intruder) // answers with its own key
        val seen = ArrayList<JoinMessage?>()
        val (a, b) = links()
        val spy = object : Link by b { override val received = b.received }
        coroutineScope {
            val served = launch { JoinHost(impostor, room).serve(spy) }
            assertEquals(JoinOutcome.HOST_NOT_VERIFIED, JoinClient(t, joiner, "Aarav", "1").requestToJoin(a))
            served.join()
        }
        assertEquals(0, impostor.submitted.size)
        assertNotNull(registry.find(t.ticketId()), "the real ticket is untouched")
    }

    @Test fun aRequestCopiedFromAnotherConnectionIsRefusedBecauseTheNonceDiffers() = runTest {
        val backend = Backend(); val t = ticket()
        // First connection: capture what the joiner sent, but make the host refuse by not issuing yet (use a recording link).
        val captured = ArrayList<ByteArray>()
        val (a1, b1) = links()
        val recording = object : Link by a1 { override suspend fun send(bytes: ByteArray) { captured += bytes; a1.send(bytes) } }
        coroutineScope {
            val served = launch { JoinHost(Backend(accepts = false, leaky = true), room).serve(b1) } // a host that keeps nothing and does not use the ticket up
            JoinClient(t, joiner, "Aarav", "1").requestToJoin(recording)
            served.join()
        }
        val sealed = JoinCodec.decode(captured.last()) as JoinMessage.Request
        // An attacker replays the captured sealed request, for the same ticket, on a new connection: only the host's nonce differs.
        val t2 = t
        val (a2, b2) = links()
        coroutineScope {
            val served = launch { JoinHost(backend, room).serve(b2) }
            a2.send(JoinCodec.encode(JoinMessage.Hello(room, t2.ticketId())))
            a2.received.first() // the challenge
            a2.send(JoinCodec.encode(sealed))
            val reply = JoinCodec.decode(a2.received.first())
            assertEquals(JoinMessage.Reply(false), reply)
            served.join()
        }
        assertEquals(0, backend.submitted.size)
        assertNotNull(registry.find(t2.ticketId()), "a replay does not use the ticket up")
        assertEquals(JoinOutcome.REQUESTED, join(t, Backend()), "the real joiner still can")
    }

    @Test fun someoneWhoKnowsTheTicketIdButNotTheGroupKeyCannotUseTheTicketUp() = runTest {
        val backend = Backend(); val t = ticket()
        val withoutKey = Ticket(t.ticketId(), host.publicKey, group, 1, GroupKey(ByteArray(32) { 99 }), t.expiresAtSeconds, "")
        assertEquals(JoinOutcome.REFUSED, join(withoutKey, backend))
        assertNotNull(registry.find(t.ticketId()), "still there for the real joiner")
        assertEquals(JoinOutcome.REQUESTED, join(t, backend))
    }

    @Test fun aRequestNamingAKeyItsSenderDoesNotHoldIsRefusedAndTheTicketKept() = runTest {
        val backend = Backend(); val t = ticket()
        val (a, b) = links()
        coroutineScope {
            val served = launch { JoinHost(backend, room).serve(b) }
            a.send(JoinCodec.encode(JoinMessage.Hello(room, t.ticketId())))
            val c = JoinCodec.decode(a.received.first()) as JoinMessage.Challenge
            // The body names the joiner's key, but is signed by the intruder.
            val body = JoinRequestBody(joiner.publicKey, joiner.encryptionKey, "Aarav", "1").encode()
            val sig = JoinCrypto.signRequest(intruder, group, host.publicKey, c.nonce, body)
            a.send(JoinCodec.encode(JoinMessage.Request(JoinCrypto.sealRequest(key, t.ticketId(), host.publicKey, c.nonce, body + sig))))
            assertEquals(JoinMessage.Reply(false), JoinCodec.decode(a.received.first()))
            served.join()
        }
        assertEquals(0, backend.submitted.size)
        assertNotNull(registry.find(t.ticketId()))
    }

    @Test fun twoJoinersWithOneTicketAtTheSameTimeOnlyOneIsKept() = runTest {
        val backend = Backend(); val t = ticket()
        val results = coroutineScope {
            listOf(joiner, intruder).map { who -> async { join(t, backend, who) } }.map { it.await() }
        }
        assertEquals(1, results.count { it == JoinOutcome.REQUESTED })
        assertEquals(1, backend.submitted.size)
    }

    @Test fun aHostThatCannotKeepTheRequestSaysSoWithTheSameRefusal() = runTest {
        assertEquals(JoinOutcome.REFUSED, join(ticket(), Backend(accepts = false)))
    }

    @Test fun theStatusQueryAnswersOnlyTheHolderOfTheKeyAndNeverLeaksOthers() = runTest {
        val backend = Backend(); val t = ticket()
        backend.statuses[joiner.publicKey] = JoinStatus.APPROVED
        suspend fun ask(who: GroupIdentity, claimed: PublicKey = who.publicKey): JoinStatusResult {
            val (a, b) = links()
            return coroutineScope {
                val served = launch { JoinHost(backend, room).serve(b) }
                val r = if (claimed == who.publicKey) JoinClient(t, who, "x", "1").status(a) else {
                    a.send(JoinCodec.encode(JoinMessage.StatusHello(room, claimed)))
                    val c = JoinCodec.decode(a.received.first()) as JoinMessage.StatusChallenge
                    a.send(JoinCodec.encode(JoinMessage.StatusProof(JoinCrypto.signStatus(who, group, host.publicKey, c.nonce))))
                    JoinStatusResult(null, (JoinCodec.decode(a.received.first()) as JoinMessage.StatusReply).status)
                }
                served.join(); r
            }
        }
        assertEquals(JoinStatus.APPROVED, ask(joiner).status)
        assertEquals(JoinStatus.UNKNOWN, ask(intruder).status, "someone with no request")
        assertEquals(JoinStatus.UNKNOWN, ask(intruder, claimed = joiner.publicKey).status, "claiming another's key without holding it")
        backend.statuses[joiner.publicKey] = JoinStatus.PENDING
        assertEquals(JoinStatus.PENDING, ask(joiner).status)
        backend.statuses[joiner.publicKey] = JoinStatus.DECLINED
        assertEquals(JoinStatus.DECLINED, ask(joiner).status)
    }

    @Test fun aSilentHostTimesOutTheJoinerAndASilentJoinerReleasesTheHost() = runTest {
        val t = ticket()
        val (a, b) = links() // nobody serves b
        assertEquals(JoinOutcome.TIMED_OUT, JoinClient(t, joiner, "x", "1").requestToJoin(a))
        // A joiner that says hello and then nothing: the host gives up and returns.
        val (a2, b2) = links()
        coroutineScope {
            val served = launch { JoinHost(Backend(), room).serve(b2) }
            a2.send(JoinCodec.encode(JoinMessage.Hello(room, t.ticketId())))
            served.join()
        }
        assertNotNull(registry.find(t.ticketId()))
    }

    @Test fun garbageAndAnotherGroupsRoomGetNoAnswerAndUseNothingUp() = runTest {
        val t = ticket()
        for (first in listOf(ByteArray(0), byteArrayOf(1, 2, 3, 4, 5), JoinCodec.encode(JoinMessage.Hello(GroupRoom(ByteArray(16) { 7 }), t.ticketId())), JoinCodec.encode(JoinMessage.Reply(true)))) {
            val (a, b) = links()
            coroutineScope {
                val served = launch { JoinHost(Backend(), room).serve(b) }
                a.send(first)
                assertNull(kotlinx.coroutines.withTimeoutOrNull(60_000) { a.received.first() }, "no answer")
                served.join()
            }
        }
        assertNotNull(registry.find(t.ticketId()))
    }
}
