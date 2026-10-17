package owntally.sync

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** A fake network for tests: every transport created from one network can reach the others. */
class InMemoryNetwork {
    private val inboxes = ConcurrentHashMap<Pair<GroupRoom, PublicKey>, Channel<Link>>()

    fun transportFor(self: PublicKey, kind: LinkKind = LinkKind.DIRECT): Transport = Endpoint(self, kind)

    private fun inbox(room: GroupRoom, key: PublicKey) =
        inboxes.getOrPut(room to key) { Channel(Channel.UNLIMITED) }

    private inner class Endpoint(private val self: PublicKey, private val kind: LinkKind) : Transport {
        override suspend fun connect(room: GroupRoom, peer: PublicKey): Link {
            val target = inboxes[room to peer] ?: throw PeerUnreachable(peer)
            val toPeer = Channel<ByteArray>(Channel.UNLIMITED)
            val toSelf = Channel<ByteArray>(Channel.UNLIMITED)
            target.send(MemoryLink(self, kind, outbound = toSelf, inbound = toPeer))
            return MemoryLink(peer, kind, outbound = toPeer, inbound = toSelf)
        }

        override fun incoming(room: GroupRoom): Flow<Link> = inbox(room, self).receiveAsFlow()
    }
}

class PeerUnreachable(peer: PublicKey) : Exception("Cannot reach $peer")

private class MemoryLink(
    override val peer: PublicKey,
    override val kind: LinkKind,
    private val outbound: Channel<ByteArray>,
    private val inbound: Channel<ByteArray>,
) : Link {
    override suspend fun send(bytes: ByteArray) = outbound.send(bytes.copyOf())
    override val received: Flow<ByteArray> = inbound.receiveAsFlow()
    override suspend fun close() {
        outbound.close()
    }
}
