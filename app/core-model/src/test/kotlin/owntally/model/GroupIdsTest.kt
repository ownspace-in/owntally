package owntally.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GroupIdsTest {
    private val creator = key(1) // 01 00 00 ... 00
    private val random8 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    @Test fun knownAnswerComputedSeparatelyInPython() {
        // sha256(creatorKey + random8) taken from Python's hashlib, first 8 bytes: 0f3375755e4497de
        assertEquals("01020304050607080f3375755e4497de", GroupIds.create(creator, random8).toHex())
        assertEquals(
            "ec284cd805ef849b",
            GroupIds.create(key(2), random8).toHex().takeLast(16),
            "another creator gets another check value",
        )
    }

    @Test fun anIdIsRecognisedOnlyForItsCreator() {
        val id = GroupIds.create(creator, random8)
        assertTrue(GroupIds.isCreatedBy(id, creator))
        assertFalse(GroupIds.isCreatedBy(id, key(2)))
        assertFalse(GroupIds.isCreatedBy(id, PublicKey(ByteArray(32))))
    }

    @Test fun anyChangedBitBreaksIt() {
        val bytes = GroupIds.create(creator, random8).bytes()
        for (i in 0 until 16) for (bit in 0 until 8) {
            val changed = bytes.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }
            assertFalse(GroupIds.isCreatedBy(GroupId(changed), creator), "byte $i bit $bit")
        }
    }

    @Test fun anArbitraryRandomIdIsNotAcceptedForAnyone() {
        for (n in 0 until 50) assertFalse(GroupIds.isCreatedBy(GroupId(ByteArray(16) { (n * 7 + it).toByte() }), creator))
    }

    @Test fun differentRandomPartsGiveDifferentIds() {
        assertNotEquals(GroupIds.create(creator, random8), GroupIds.create(creator, ByteArray(8) { 9 }))
    }

    @Test fun theRandomPartMustBeEightBytes() {
        assertFailsWith<IllegalArgumentException> { GroupIds.create(creator, ByteArray(7)) }
        assertFailsWith<IllegalArgumentException> { GroupIds.create(creator, ByteArray(9)) }
    }

    @Test fun theFirstEightBytesAreTheRandomPart() {
        assertEquals(random8.toList(), GroupIds.create(creator, random8).bytes().take(8))
    }
}
