package owntally.crypto

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import owntally.model.DecodeException
import owntally.model.Envelope
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.GroupId
import owntally.model.Money
import owntally.model.Split

/** docs/CRYPTO.md section 5: encrypt the payload, then sign the envelope. */
class EventSealingTest {
    private fun String.hex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val vectors: JsonObject = Json.parseToJsonElement(EventSealingTest::class.java.getResource("/crypto-vectors.json")!!.readText()).jsonObject
    private val seed = MasterSeed(vectors.getValue("seed").jsonPrimitive.content.hex())
    private val a = KeyDerivation.groupIdentity(seed, 0)
    private val b = KeyDerivation.groupIdentity(seed, 1)
    private val group = GroupId(ByteArray(16) { 9 })
    private val key = GroupKey(ByteArray(32) { it.toByte() })

    private fun keyring(vararg entries: Pair<Long, GroupKey>, groupId: GroupId = group) =
        GroupKeyring(groupId).also { r -> entries.forEach { (epoch, k) -> r.add(epoch, k) } }

    private fun profile(counter: Long = 1, author: owntally.model.PublicKey = a.publicKey) =
        Event(1, author, counter, if (counter > 1) mapOf(author to counter - 1) else emptyMap(), 1000, EventBody.ProfileUpdated("Asha", "98765 43210"))

    private fun opened(r: OpenResult) = (r as OpenResult.Opened).event

    @Test
    fun anEventSealedByTheSpecInAnotherImplementationOpens() {
        val v = vectors.getValue("sealed").jsonArray[0].jsonObject
        val envelope = Envelope.decode(v.getValue("hex").jsonPrimitive.content.hex())
        val result = EventSealing.open(envelope, keyring(2L to GroupKey(v.getValue("groupKey").jsonPrimitive.content.hex())))
        val event = opened(result)
        assertEquals(EventBody.ProfileUpdated("Asha", "98765 43210"), event.body)
        assertEquals(a.publicKey, event.author)
        assertEquals(1L, event.counter)
        assertEquals(1000L, event.time)
    }

    @Test
    fun sealingThenOpeningGivesTheEventBack() {
        val event = profile()
        val envelope = EventSealing.seal(a, group, 2, key, event)
        assertEquals(event, opened(EventSealing.open(envelope, keyring(2L to key))))
        assertEquals(a.publicKey, envelope.author)
        assertEquals(2L, envelope.epoch)
        assertEquals(1L, envelope.counter)
        assertTrue(EnvelopeSigning.verify(envelope))
    }

    @Test
    fun theSameEventSealedTwiceGivesDifferentBytes() {
        val one = EventSealing.seal(a, group, 2, key, profile())
        val two = EventSealing.seal(a, group, 2, key, profile())
        assertNotEquals(one.encode().hex(), two.encode().hex())
        assertNotEquals(one.nonce().hex(), two.nonce().hex())
        assertNotEquals(one.ciphertext().hex(), two.ciphertext().hex())
    }

    @Test
    fun theCiphertextHidesTheContents() {
        val envelope = EventSealing.seal(a, group, 2, key, profile())
        val bytes = envelope.encode().hex()
        for (secret in listOf("Asha", "98765 43210")) assertFalse(secret.toByteArray().hex() in bytes, secret)
    }

    @Test
    fun aWrongKeyDoesNotOpenIt() {
        val envelope = EventSealing.seal(a, group, 2, key, profile())
        val other = GroupKey(ByteArray(32) { 1 })
        assertEquals(OpenResult.Rejected(RejectReason.DECRYPTION_FAILED), EventSealing.open(envelope, keyring(2L to other)))
    }

    @Test
    fun withNoKeyForTheEpochTheAnswerIsToAskForOne() {
        val envelope = EventSealing.seal(a, group, 3, key, profile())
        assertEquals(OpenResult.NeedKey(3), EventSealing.open(envelope, keyring(2L to key)))
        assertEquals(OpenResult.NeedKey(3), EventSealing.open(envelope, keyring()))
    }

    @Test
    fun eachEpochUsesItsOwnKey() {
        val k2 = GroupKey(ByteArray(32) { 2 })
        val ring = keyring(1L to key, 2L to k2)
        val old = EventSealing.seal(a, group, 1, key, profile(1))
        val new = EventSealing.seal(a, group, 2, k2, profile(2))
        assertEquals(profile(1), opened(EventSealing.open(old, ring)))
        assertEquals(profile(2), opened(EventSealing.open(new, ring)))
        // Someone holding only the old key reads the old event but is told they need the new key.
        val oldOnly = keyring(1L to key)
        assertEquals(profile(1), opened(EventSealing.open(old, oldOnly)))
        assertEquals(OpenResult.NeedKey(2), EventSealing.open(new, oldOnly))
    }

    @Test
    fun aCiphertextCannotBeMovedToAnotherEventSlot() {
        // The attacker re-signs with a valid signature, so only the encryption itself can catch the move.
        val sealed = EventSealing.seal(a, group, 2, key, profile(1))
        val ring = keyring(2L to key, 3L to key, groupId = group)
        val otherGroup = GroupId(ByteArray(16) { 8 })
        val ringOtherGroup = keyring(2L to key, groupId = otherGroup)
        fun resigned(identity: GroupIdentity, groupId: GroupId, epoch: Long, counter: Long) =
            EnvelopeSigning.sign(identity, 1, groupId, epoch, counter, sealed.nonce(), sealed.ciphertext())

        assertEquals(profile(1), opened(EventSealing.open(resigned(a, group, 2, 1), ring)), "unchanged opens")
        val moved = mapOf(
            "epoch" to EventSealing.open(resigned(a, group, 3, 1), ring),
            "counter" to EventSealing.open(resigned(a, group, 2, 2), ring),
            "author" to EventSealing.open(resigned(b, group, 2, 1), ring),
            "group" to EventSealing.open(resigned(a, otherGroup, 2, 1), ringOtherGroup),
        )
        for ((what, result) in moved) assertEquals(OpenResult.Rejected(RejectReason.DECRYPTION_FAILED), result, what)
    }

    @Test
    fun aChangedNonceOrCiphertextFailsToDecryptEvenWhenResigned() {
        val sealed = EventSealing.seal(a, group, 2, key, profile())
        val ring = keyring(2L to key)
        val badNonce = sealed.nonce().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val badCiphertext = sealed.ciphertext().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val shortCiphertext = sealed.ciphertext().copyOf(5)
        for ((nonce, ct) in listOf(badNonce to sealed.ciphertext(), sealed.nonce() to badCiphertext, sealed.nonce() to shortCiphertext, sealed.nonce() to ByteArray(0))) {
            val e = EnvelopeSigning.sign(a, 1, group, 2, 1, nonce, ct)
            assertEquals(OpenResult.Rejected(RejectReason.DECRYPTION_FAILED), EventSealing.open(e, ring))
        }
    }

    @Test
    fun anUnsignedChangeIsRejectedBeforeDecrypting() {
        val sealed = EventSealing.seal(a, group, 2, key, profile())
        val bent = Envelope(1, group, 2, a.publicKey, 1, sealed.nonce(), sealed.ciphertext().also { it[0] = (it[0].toInt() xor 1).toByte() }, sealed.signature())
        assertEquals(OpenResult.Rejected(RejectReason.BAD_SIGNATURE), EventSealing.open(bent, keyring(2L to key)))
    }

    @Test
    fun anEnvelopeForAnotherGroupIsRejected() {
        val envelope = EventSealing.seal(a, group, 2, key, profile())
        val ring = keyring(2L to key, groupId = GroupId(ByteArray(16) { 5 }))
        assertEquals(OpenResult.Rejected(RejectReason.WRONG_GROUP), EventSealing.open(envelope, ring))
    }

    @Test
    fun aPayloadThatDecryptsButIsNotAValidEventIsRejectedAsMalformed() {
        // Encrypted correctly, signed correctly, but the bytes inside are not an event.
        val nonceAndCt = com.google.crypto.tink.subtle.XChaCha20Poly1305(key.bytes()).encrypt(
            byteArrayOf(1, 2, 3),
            EventSealing.associatedData(group, 2, a.publicKey, 1),
        )
        val e = EnvelopeSigning.sign(a, 1, group, 2, 1, nonceAndCt.copyOfRange(0, 24), nonceAndCt.copyOfRange(24, nonceAndCt.size))
        assertEquals(OpenResult.Rejected(RejectReason.MALFORMED), EventSealing.open(e, keyring(2L to key)))
    }

    @Test
    fun theAuthorOfTheEventMustBeTheSigner() {
        val someoneElse = profile(1, b.publicKey)
        assertFailsWith<IllegalArgumentException> { EventSealing.seal(a, group, 2, key, someoneElse) }
    }

    @Test
    fun theKeyMustBe32BytesAndPrintsNothing() {
        assertFailsWith<IllegalArgumentException> { GroupKey(ByteArray(31)) }
        val text = key.toString()
        assertTrue(key.bytes().hex().take(8) !in text && "redacted" in text)
        assertNotEquals(GroupKey.generate(), GroupKey.generate())
    }

    @Test
    fun theEpochMustBeAtLeastOne() {
        assertFailsWith<IllegalArgumentException> { EventSealing.seal(a, group, 0, key, profile()) }
    }

    @Test
    fun anyEventSealedForSomeoneElsesOpeningRoundTripsAndAnyChangedByteIsRefused() = runBlocking<Unit> {
        checkAll(150, Arb.long()) { s ->
            val rnd = Random(s)
            val identity = KeyDerivation.groupIdentity(seed, rnd.nextLong(0, 1000))
            val counter = rnd.nextLong(1, 100)
            val event = Event(
                1, identity.publicKey, counter, if (counter > 1) mapOf(identity.publicKey to counter - 1) else emptyMap(), rnd.nextLong(),
                EventBody.ExpenseAdded("Dinner ${rnd.nextInt()}", Money(rnd.nextLong(1, 1_000_000)), identity.publicKey, rnd.nextLong(0, 30000), Split.Equal(setOf(identity.publicKey))),
            )
            val groupKey = GroupKey.generate()
            val epoch = rnd.nextLong(1, 1000)
            val envelope = EventSealing.seal(identity, group, epoch, groupKey, event)
            val ring = keyring(epoch to groupKey)
            assertEquals(event, opened(EventSealing.open(envelope, ring)))
            // Flip one bit anywhere in the stored bytes: decoding, the signature or the decryption must refuse it.
            val bytes = envelope.encode()
            val bad = bytes.copyOf().also { val i = rnd.nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
            val result = try { EventSealing.open(Envelope.decode(bad), ring) } catch (e: DecodeException) { null }
            assertTrue(result == null || result !is OpenResult.Opened, "seed $s: a changed envelope was accepted")
        }
    }

}
