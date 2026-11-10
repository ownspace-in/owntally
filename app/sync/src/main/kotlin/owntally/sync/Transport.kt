package owntally.sync

import kotlinx.coroutines.flow.Flow
import owntally.model.PublicKey

/** Length of a member's public key in bytes. */
const val KEY_SIZE = 32

/** Opaque room name derived from the group id. The relay must never learn the real group id. */
class GroupRoom(val id: ByteArray) {
    override fun equals(other: Any?) = other is GroupRoom && id.contentEquals(other.id)
    override fun hashCode() = id.contentHashCode()
    override fun toString() = id.joinToString("") { "%02x".format(it) }

    companion object {
        /** The first 16 bytes of SHA-256 over the group id. */
        fun fromGroupId(groupId: ByteArray) =
            GroupRoom(java.security.MessageDigest.getInstance("SHA-256").digest(groupId).copyOf(16))
    }
}

enum class LinkKind { DIRECT, RELAYED, NEARBY }

/** Payloads are opaque bytes. A transport must never read or interpret them. */
interface Link {
    val peer: PublicKey
    val kind: LinkKind

    /** How a local link is protected on the network (TLS or plain); null for a link that is not a local network link. */
    val security: LinkSecurity? get() = null
    suspend fun send(bytes: ByteArray)
    val received: Flow<ByteArray>
    suspend fun close()
}

/** There is no way to reach other phones at all yet (no real transport is built in): not a failure to reach someone, so nothing is retried. */
class TransportUnavailable : Exception("No way to reach other phones is available yet")

/** The transport of a phone that has none yet (until M6): it cannot connect and nobody can connect to it. */
object NoTransport : Transport {
    override suspend fun connect(room: GroupRoom, peer: PublicKey): Link = throw TransportUnavailable()
    override fun incoming(room: GroupRoom): Flow<Link> = kotlinx.coroutines.flow.emptyFlow()
}

interface Transport {
    /** Connect to the member with this public key, in this group. Throws if the peer cannot be reached. */
    suspend fun connect(room: GroupRoom, peer: PublicKey): Link

    /** Links opened by other members in this group. */
    fun incoming(room: GroupRoom): Flow<Link>
}

/**
 * How many received messages may wait for the code that reads them (finding F3, docs/SECURITY_REVIEW.md). A reader that can wait (the engines, a
 * socket) lets the queue fill and the sender waits; a transport that is called back and cannot wait ends the link instead. Messages are at most
 * 256 KB (a sync batch) and mostly a few KB, so the most a queue holds is a few tens of megabytes.
 */
object LinkQueue {
    const val CAPACITY = 64

    /** For transports that are called back by a library and cannot make the sender wait: frames there are at most 64 KB. */
    const val CALLBACK_CAPACITY = 256
}
