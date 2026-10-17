package owntally.sync

import kotlinx.coroutines.flow.Flow

class PublicKey(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is PublicKey && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = bytes.joinToString("") { "%02x".format(it) }
}

/** Opaque room name derived from the group id. The relay must never learn the real group id. */
class GroupRoom(val id: ByteArray) {
    override fun equals(other: Any?) = other is GroupRoom && id.contentEquals(other.id)
    override fun hashCode() = id.contentHashCode()
    override fun toString() = id.joinToString("") { "%02x".format(it) }
}

enum class LinkKind { DIRECT, RELAYED, NEARBY }

/** Payloads are opaque bytes. A transport must never read or interpret them. */
interface Link {
    val peer: PublicKey
    val kind: LinkKind
    suspend fun send(bytes: ByteArray)
    val received: Flow<ByteArray>
    suspend fun close()
}

interface Transport {
    /** Connect to the member with this public key, in this group. Throws if the peer cannot be reached. */
    suspend fun connect(room: GroupRoom, peer: PublicKey): Link

    /** Links opened by other members in this group. */
    fun incoming(room: GroupRoom): Flow<Link>
}
