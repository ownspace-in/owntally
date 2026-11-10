package owntally.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M10, T10.8, finding F1 (docs/SECURITY_REVIEW.md): a member's encryption key is chosen by the member. A low-order X25519 point cannot be sealed to
 * (HPKE refuses it), so one such member would make every key change impossible, including their own removal. Replay therefore ignores a join
 * (or a creating event) that names one.
 */
class LowOrderKeysTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val encodings = listOf(
        "0000000000000000000000000000000000000000000000000000000000000000",
        "0100000000000000000000000000000000000000000000000000000000000000",
        "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
        "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    )

    @Test fun everyLowOrderEncodingIsRecognisedWithOrWithoutTheIgnoredTopBit() {
        for (e in encodings) {
            val k = hex(e)
            assertTrue(LowOrderKeys.isLowOrder(EncryptionKey(k)), e)
            assertTrue(LowOrderKeys.isLowOrder(EncryptionKey(k.copyOf().also { it[31] = (it[31].toInt() or 0x80).toByte() })), "top bit set: $e")
        }
        assertEquals(encodings, LowOrderKeys.ALL.map { it.joinToString("") { b -> "%02x".format(b) } })
    }

    @Test fun anOrdinaryKeyIsNotLowOrder() {
        assertFalse(LowOrderKeys.isLowOrder(ENC))
        assertFalse(LowOrderKeys.isLowOrder(encKeyFor(key(9))))
        assertFalse(LowOrderKeys.isLowOrder(EncryptionKey(ByteArray(32) { 0xFF.toByte() })), "all ones is not a listed point")
    }

    @Test fun aJoinWithALowOrderEncryptionKeyIsIgnoredAndTheMemberIsNotAdded() {
        val s = standardGroup()
        val bad = s.emit(a(), EventBody.MemberJoined(key(20), EncryptionKey(ByteArray(32)), "Zed", PHONE))
        assertEquals(IgnoreReason.INVALID, s.reasonOf(bad))
        assertFalse(key(20) in s.state().members)
        val good = s.emit(a(), EventBody.MemberJoined(key(21), encKeyFor(key(21)), "Yan", PHONE))
        assertEquals(null, s.reasonOf(good))
    }

    @Test fun aGroupCreatedWithALowOrderEncryptionKeyIsIgnored() {
        val s = Sim()
        val created = s.emit(a(), EventBody.GroupCreated(GROUP, "Trip", "INR", EncryptionKey(ByteArray(32).also { it[0] = 1 })))
        assertEquals(IgnoreReason.INVALID, s.reasonOf(created))
        assertEquals(null, s.state().owner)
    }

    private fun a() = key(1)
    private fun Sim.reasonOf(event: Event) = event.reason(state())
}
