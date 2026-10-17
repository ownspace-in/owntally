package owntally.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class InMemoryTransportTest {
    private val room = GroupRoom(ByteArray(16) { it.toByte() })
    private val alice = PublicKey(ByteArray(32) { 1 })
    private val bob = PublicKey(ByteArray(32) { 2 })

    @Test
    fun hundredMessagesArriveInOrder() = runTest {
        val net = InMemoryNetwork()
        val a = net.transportFor(alice)
        val b = net.transportFor(bob)
        b.incoming(room) // makes bob reachable

        val toBob = a.connect(room, bob)
        val fromAlice = b.incoming(room).first()
        repeat(100) { toBob.send(byteArrayOf(it.toByte())) }

        val got = fromAlice.received.take(100).toList()
        assertEquals(100, got.size)
        got.forEachIndexed { i, bytes -> assertContentEquals(byteArrayOf(i.toByte()), bytes) }
        assertEquals(alice, fromAlice.peer)
        assertEquals(bob, toBob.peer)
    }

    @Test
    fun messagesFlowBothWays() = runTest {
        val net = InMemoryNetwork()
        val a = net.transportFor(alice, LinkKind.NEARBY)
        val b = net.transportFor(bob, LinkKind.NEARBY)
        b.incoming(room)

        val toBob = a.connect(room, bob)
        val fromAlice = b.incoming(room).first()
        toBob.send(byteArrayOf(1))
        fromAlice.send(byteArrayOf(2))

        assertContentEquals(byteArrayOf(1), fromAlice.received.first())
        assertContentEquals(byteArrayOf(2), toBob.received.first())
        assertEquals(LinkKind.NEARBY, toBob.kind)
    }

    @Test
    fun connectingToUnknownPeerFails() = runTest {
        val a = InMemoryNetwork().transportFor(alice)
        assertFailsWith<PeerUnreachable> { a.connect(room, bob) }
    }

    @Test
    fun closingEndsTheOtherSidesStream() = runTest {
        val net = InMemoryNetwork()
        val a = net.transportFor(alice)
        val b = net.transportFor(bob)
        b.incoming(room)

        val toBob = a.connect(room, bob)
        val fromAlice = b.incoming(room).first()
        toBob.send(byteArrayOf(7))
        toBob.close()

        assertEquals(1, fromAlice.received.toList().size)
    }
}
