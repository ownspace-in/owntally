package owntally.crypto

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import owntally.model.GroupId
import owntally.model.GroupIds

/**
 * M11, T11.2 (owner decision D2; docs/CRYPTO.md section 2, "which slot a group uses"): the key of a group is derived from the seed and the group's
 * id, so a restored phone finds it again from an invitation alone, with no search and no list of slots used. The expected numbers come from
 * crypto_vectors.py, an independent implementation.
 */
class KeySlotsTest {
    private val vectors = Json.parseToJsonElement(KeySlotsTest::class.java.getResource("/crypto-vectors.json")!!.readText()).jsonObject.getValue("slotNumbers").jsonArray.map { it.jsonObject }
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val seed = Bip39.seed(Bip39.fromEntropy(ByteArray(16) { 9 }))
    private val otherSeed = Bip39.seed(Bip39.fromEntropy(ByteArray(16) { 10 }))

    @Test fun theNumbersAreTheOnesTheIndependentImplementationMakes() {
        assertTrue(vectors.size >= 4)
        for (v in vectors) {
            val expected = v.getValue("slot").jsonPrimitive.long
            when (v.getValue("kind").jsonPrimitive.content) {
                "member" -> assertEquals(expected, KeySlots.member(GroupId(hex(v.getValue("groupId").jsonPrimitive.content)), v.getValue("generation").jsonPrimitive.long), v.toString())
                "creator" -> assertEquals(expected, KeySlots.creator(hex(v.getValue("random").jsonPrimitive.content)), v.toString())
            }
        }
    }

    @Test fun everySlotIsAValidSlotOfAtMost62Bits() {
        val rnd = SecureRandom()
        repeat(2_000) {
            val gid = GroupId(ByteArray(16).also { rnd.nextBytes(it) })
            for (s in listOf(KeySlots.member(gid, 0), KeySlots.member(gid, 1), KeySlots.creator(ByteArray(8).also { rnd.nextBytes(it) }))) {
                assertTrue(s in 0 until (1L shl 62), "slot $s")
            }
        }
        KeyDerivation.groupIdentity(seed, KeySlots.member(GroupId(ByteArray(16)), 0)) // the derivation accepts what this makes
    }

    @Test fun theLimitOfTheDerivationIs62BitsAndNothingNegative() {
        KeyDerivation.groupIdentity(seed, (1L shl 62) - 1)
        assertFailsWith<IllegalArgumentException> { KeyDerivation.groupIdentity(seed, 1L shl 62) }
        assertFailsWith<IllegalArgumentException> { KeyDerivation.groupIdentity(seed, -1) }
    }

    @Test fun differentGroupsGenerationsAndKindsGiveDifferentSlots() {
        val g1 = GroupId(ByteArray(16) { 1 }); val g2 = GroupId(ByteArray(16) { 2 })
        val slots = setOf(KeySlots.member(g1, 0), KeySlots.member(g1, 1), KeySlots.member(g2, 0), KeySlots.creator(ByteArray(8) { 1 }), KeySlots.creator(ByteArray(8) { 2 }))
        assertEquals(5, slots.size)
        assertNotEquals(KeySlots.member(g1, 0), KeySlots.creator(ByteArray(8) { 1 }), "the same bytes under the two labels")
    }

    @Test fun sameInputsGiveTheSameSlotAndTheSameKeysOnAnyPhoneWithTheSamePhrase() {
        val g = GroupId(ByteArray(16) { 5 })
        assertEquals(KeySlots.member(g, 0), KeySlots.member(GroupId(ByteArray(16) { 5 }), 0))
        val a = KeyDerivation.groupIdentity(seed, KeySlots.member(g, 0))
        val b = KeyDerivation.groupIdentity(seed, KeySlots.member(g, 0))
        assertEquals(a.publicKey, b.publicKey)
        assertNotEquals(a.publicKey, KeyDerivation.groupIdentity(otherSeed, KeySlots.member(g, 0)).publicKey, "another person's phrase gives another key")
    }

    @Test fun aCreatorFindsTheirOwnKeyAgainFromTheGroupIdAlone() {
        val random = ByteArray(8) { (it * 7 + 3).toByte() }
        val creator = KeyDerivation.groupIdentity(seed, KeySlots.creator(random))
        val id = GroupIds.create(creator.publicKey, random)
        // A restored phone knows only the id and the phrase:
        val again = KeyDerivation.groupIdentity(seed, KeySlots.creator(GroupIds.randomPart(id)))
        assertEquals(creator.publicKey, again.publicKey)
        assertTrue(GroupIds.isCreatedBy(id, again.publicKey))
        // Someone else's phrase gets a key the id was not made for, and a member's key is not the creator's.
        assertFalse(GroupIds.isCreatedBy(id, KeyDerivation.groupIdentity(otherSeed, KeySlots.creator(GroupIds.randomPart(id))).publicKey))
        assertFalse(GroupIds.isCreatedBy(id, KeyDerivation.groupIdentity(seed, KeySlots.member(id, 0)).publicKey))
    }

    @Test fun aGenerationMustFit32Bits() {
        val g = GroupId(ByteArray(16))
        KeySlots.member(g, 0xFFFF_FFFFL)
        assertFailsWith<IllegalArgumentException> { KeySlots.member(g, -1) }
        assertFailsWith<IllegalArgumentException> { KeySlots.member(g, 0x1_0000_0000L) }
        assertFailsWith<IllegalArgumentException> { KeySlots.creator(ByteArray(7)) }
    }
}
