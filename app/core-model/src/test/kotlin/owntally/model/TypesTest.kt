package owntally.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TypesTest {
    @Test
    fun groupIdReadsBackFromHexAndRefusesBadText() {
        val id = GroupId(ByteArray(16) { (it * 17).toByte() })
        assertEquals(id, GroupId.fromHexOrNull(id.toHex()))
        assertEquals(id, GroupId.fromHexOrNull(id.toHex().uppercase()))
        assertEquals(null, GroupId.fromHexOrNull(""))
        assertEquals(null, GroupId.fromHexOrNull(id.toHex().dropLast(2)))
        assertEquals(null, GroupId.fromHexOrNull(id.toHex() + "00"))
        assertEquals(null, GroupId.fromHexOrNull("zz" + id.toHex().drop(2)))
    }

    @Test
    fun moneyAddsAndSubtractsInPaise() {
        assertEquals(Money(350), Money(100) + Money(250))
        assertEquals(Money(-150), Money(100) - Money(250))
        assertEquals(Money(-100), -Money(100))
        assertEquals(Money.ZERO, Money(5) - Money(5))
    }

    @Test
    fun moneyOverflowFailsLoudly() {
        assertFailsWith<ArithmeticException> { Money(Long.MAX_VALUE) + Money(1) }
        assertFailsWith<ArithmeticException> { Money(Long.MIN_VALUE) - Money(1) }
    }

    @Test
    fun moneyOrdersByAmount() {
        assertTrue(Money(1) < Money(2))
        assertEquals(listOf(Money(-3), Money(0), Money(7)), listOf(Money(7), Money(-3), Money(0)).sorted())
        assertTrue(Money(1).isPositive())
        assertTrue(!Money.ZERO.isPositive())
        assertTrue(Money(-1).isNegative())
    }

    @Test
    fun keyIsCopiedSoLaterChangesToTheArrayDoNotAffectIt() {
        val bytes = ByteArray(32) { 1 }
        val key = PublicKey(bytes)
        bytes[0] = 99
        assertEquals(1, key.bytes()[0].toInt())
    }

    @Test
    fun keyNeedsExactly32Bytes() {
        assertFailsWith<IllegalArgumentException> { PublicKey(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { PublicKey(ByteArray(33)) }
    }

    @Test
    fun keysWithSameBytesAreEqualAndHashTheSame() {
        val a = PublicKey(ByteArray(32) { 7 })
        val b = PublicKey(ByteArray(32) { 7 })
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(1, setOf(a, b).size)
        assertNotEquals(a, PublicKey(ByteArray(32) { 8 }))
    }

    @Test
    fun keysSortAsUnsignedBytes() {
        val low = PublicKey(ByteArray(32).also { it[0] = 0x7f })
        val high = PublicKey(ByteArray(32).also { it[0] = 0x80.toByte() })
        assertTrue(low < high, "0x80 must sort after 0x7f, not before as a signed byte would")
        val second = PublicKey(ByteArray(32).also { it[1] = 1 })
        val zero = PublicKey(ByteArray(32))
        assertTrue(zero < second)
    }

    @Test
    fun keyHexRoundTrips() {
        val key = PublicKey(ByteArray(32) { it.toByte() })
        assertEquals(64, key.toHex().length)
        assertEquals(key, PublicKey.fromHex(key.toHex()))
        assertFailsWith<IllegalArgumentException> { PublicKey.fromHex("zz") }
    }

    @Test
    fun groupIdNeeds16Bytes() {
        GroupId(ByteArray(16))
        assertFailsWith<IllegalArgumentException> { GroupId(ByteArray(15)) }
        assertEquals(GroupId(ByteArray(16) { 3 }), GroupId(ByteArray(16) { 3 }))
    }

    @Test
    fun eventIdsOrderByAuthorThenCounter() {
        val a = PublicKey(ByteArray(32) { 1 })
        val b = PublicKey(ByteArray(32) { 2 })
        val ids = listOf(EventId(b, 1), EventId(a, 5), EventId(a, 2))
        assertEquals(listOf(EventId(a, 2), EventId(a, 5), EventId(b, 1)), ids.sorted())
    }

    @Test
    fun eventIdCounterStartsAtOne() {
        val a = PublicKey(ByteArray(32) { 1 })
        assertFailsWith<IllegalArgumentException> { EventId(a, 0) }
    }

    @Test
    fun ownerIsAnAdminAndMemberIsNot() {
        assertTrue(Role.OWNER.isAdmin)
        assertTrue(Role.ADMIN.isAdmin)
        assertTrue(!Role.MEMBER.isAdmin)
    }
}
