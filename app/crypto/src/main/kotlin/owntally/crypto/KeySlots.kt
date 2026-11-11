package owntally.crypto

import java.nio.ByteBuffer
import java.security.MessageDigest
import owntally.model.GroupId

/**
 * Which slot of the seed a group's key comes from (M11, owner decision D2; docs/CRYPTO.md section 2). Not a counter any more: a number made from the
 * group's id, so a phone restored from the recovery phrase finds the key it used in a group from an invitation alone, with no search and no list of
 * the slots already used. The slot is the first 62 bits of SHA-256 over a label and the inputs; the keys are then derived from it exactly as before
 * ([KeyDerivation.groupIdentity]).
 *
 * - A **member** of a group (anyone who joined it) uses `member(groupId, generation)`. A person who left a group can never be admitted under the same
 *   key again (a removed key stays removed), so joining it again uses the next generation: 0, 1, 2, ...
 * - A group's **creator** uses `creator(random)`, with the 8 random bytes that begin the group's id ([owntally.model.GroupIds]); the creator's key
 *   is derived before the id exists, and the id then holds a check of it, so the same key can be derived again from the id alone.
 */
object KeySlots {
    private val MEMBER = "owntally/v1/slot/member".toByteArray()
    private val CREATOR = "owntally/v1/slot/creator".toByteArray()
    private const val MAX_GENERATION = 0xFFFF_FFFFL

    private fun slot(label: ByteArray, vararg parts: ByteArray): Long {
        val digest = MessageDigest.getInstance("SHA-256").apply { update(label); parts.forEach { update(it) } }.digest()
        return ByteBuffer.wrap(digest, 0, 8).long ushr 2 // 62 bits, never negative
    }

    fun member(groupId: GroupId, generation: Long): Long {
        require(generation in 0..MAX_GENERATION) { "a generation fits 32 bits" }
        return slot(MEMBER, groupId.bytes(), ByteBuffer.allocate(4).putInt(generation.toInt()).array())
    }

    fun creator(random: ByteArray): Long {
        require(random.size == 8) { "the random part of a group id is 8 bytes" }
        return slot(CREATOR, random)
    }
}
