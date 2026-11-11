package owntally.model

import java.security.MessageDigest

/**
 * A group id that says who created the group (owner decision, 4 Nov 2026). The 16 bytes are 8 random bytes followed by the first 8
 * bytes of SHA-256(creator's public key + the 8 random bytes). A `GroupCreated` event is accepted only if its group id matches its
 * author, so a member who holds the group key cannot write a second "create group" event and take the group over: they would
 * need a key whose hash matches the id (about 2^64 tries). Standard SHA-256, no new cryptography; the wire format is unchanged.
 */
object GroupIds {
    private const val RANDOM = 8

    private fun check(creator: PublicKey, random: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(creator.bytes() + random).copyOf(RANDOM)

    /** An id for a group created by [creator], from 8 random bytes. */
    fun create(creator: PublicKey, random: ByteArray): GroupId {
        require(random.size == RANDOM) { "the random part is $RANDOM bytes" }
        return GroupId(random + check(creator, random))
    }

    /** The 8 random bytes that begin the id: from them the creator's key is derived again (M11, `KeySlots.creator`). */
    fun randomPart(id: GroupId): ByteArray = id.bytes().copyOfRange(0, RANDOM)

    /** True if [id] was made for [creator]. */
    fun isCreatedBy(id: GroupId, creator: PublicKey): Boolean {
        val bytes = id.bytes()
        val random = bytes.copyOfRange(0, RANDOM)
        return MessageDigest.isEqual(bytes.copyOfRange(RANDOM, 2 * RANDOM), check(creator, random))
    }
}
