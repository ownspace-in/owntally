package owntally.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import owntally.model.DecodeException
import owntally.model.Envelope
import owntally.model.GroupId
import owntally.model.PublicKey

/** docs/CRYPTO.md section 4. */
class EnvelopeSigningTest {
    private fun String.hex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val vectors: JsonObject = Json.parseToJsonElement(EnvelopeSigningTest::class.java.getResource("/crypto-vectors.json")!!.readText()).jsonObject
    private val seed = MasterSeed(vectors.getValue("seed").jsonPrimitive.content.hex())
    private val a = KeyDerivation.groupIdentity(seed, 0)
    private val b = KeyDerivation.groupIdentity(seed, 1)
    private val group = GroupId(ByteArray(16) { 9 })
    private val nonce = ByteArray(24) { (it + 1).toByte() }
    private val ciphertext = ByteArray(20) { (0xA0 + it).toByte() }

    private fun signedByA() = EnvelopeSigning.sign(a, 1, group, 2, 5, nonce, ciphertext)

    @Test
    fun signaturesMatchTheIndependentImplementationByteForByte() {
        for (v in vectors.getValue("envelopes").jsonArray.map { it.jsonObject }) {
            val id = KeyDerivation.groupIdentity(seed, v.getValue("slot").jsonPrimitive.long)
            val envelope = EnvelopeSigning.sign(
                id, v.getValue("version").jsonPrimitive.int, GroupId(v.getValue("groupId").jsonPrimitive.content.hex()),
                v.getValue("epoch").jsonPrimitive.long, v.getValue("counter").jsonPrimitive.long,
                v.getValue("nonce").jsonPrimitive.content.hex(), v.getValue("ciphertext").jsonPrimitive.content.hex(),
            )
            assertEquals(v.getValue("signature").jsonPrimitive.content, envelope.signature().hex())
            assertEquals(v.getValue("hex").jsonPrimitive.content, envelope.encode().hex())
            assertTrue(EnvelopeSigning.verify(Envelope.decode(v.getValue("hex").jsonPrimitive.content.hex())))
        }
    }

    @Test
    fun theAuthorIsTheSignersPublicKey() {
        assertEquals(a.publicKey, signedByA().author)
    }

    @Test
    fun aGoodSignatureVerifiesAfterEncodingAndDecoding() {
        assertTrue(EnvelopeSigning.verify(Envelope.decode(signedByA().encode())))
    }

    @Test
    fun changingAnyByteOfTheEnvelopeIsCaught() {
        val good = signedByA().encode()
        for (i in good.indices) {
            val bad = good.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val verified = try { EnvelopeSigning.verify(Envelope.decode(bad)) } catch (e: DecodeException) { false }
            assertFalse(verified, "changing byte $i was not caught")
        }
    }

    @Test
    fun aSignatureCannotBeMovedToAnotherGroupEpochCounterNonceOrCiphertext() {
        val e = signedByA()
        val sig = e.signature()
        assertTrue(EnvelopeSigning.verify(Envelope(1, group, 2, a.publicKey, 5, nonce, ciphertext, sig)))
        assertFalse(EnvelopeSigning.verify(Envelope(1, GroupId(ByteArray(16) { 8 }), 2, a.publicKey, 5, nonce, ciphertext, sig)), "group")
        assertFalse(EnvelopeSigning.verify(Envelope(1, group, 3, a.publicKey, 5, nonce, ciphertext, sig)), "epoch")
        assertFalse(EnvelopeSigning.verify(Envelope(1, group, 2, a.publicKey, 6, nonce, ciphertext, sig)), "counter")
        assertFalse(EnvelopeSigning.verify(Envelope(1, group, 2, a.publicKey, 5, ByteArray(24), ciphertext, sig)), "nonce")
        assertFalse(EnvelopeSigning.verify(Envelope(1, group, 2, a.publicKey, 5, nonce, ciphertext + 1, sig)), "ciphertext")
        assertFalse(EnvelopeSigning.verify(Envelope(2, group, 2, a.publicKey, 5, nonce, ciphertext, sig)), "version")
    }

    @Test
    fun aSignatureByAnotherKeyIsRejected() {
        // Signed by B but claiming to be written by A.
        val bySigned = EnvelopeSigning.sign(b, 1, group, 2, 5, nonce, ciphertext)
        assertFalse(EnvelopeSigning.verify(Envelope(1, group, 2, a.publicKey, 5, nonce, ciphertext, bySigned.signature())))
    }

    @Test
    fun theEncryptionKeyCannotStandInForTheSigningKey() {
        val e = signedByA()
        val claimed = PublicKey(a.encryptionKey.bytes())
        assertFalse(EnvelopeSigning.verify(Envelope(1, group, 2, claimed, 5, nonce, ciphertext, e.signature())))
    }

    @Test
    fun aSignatureWithoutTheFixedPrefixOrWithAnotherPrefixIsRejected() {
        val unsigned = Envelope(1, group, 2, a.publicKey, 5, nonce, ciphertext, ByteArray(64))
        val signer = Ed25519Sign(a.signingSeed())
        fun withSig(sig: ByteArray) = Envelope(1, group, 2, a.publicKey, 5, nonce, ciphertext, sig)
        assertTrue(EnvelopeSigning.verify(withSig(signer.sign("owntally/v1/event".toByteArray() + unsigned.signedPart()))))
        assertFalse(EnvelopeSigning.verify(withSig(signer.sign(unsigned.signedPart()))), "no prefix")
        assertFalse(EnvelopeSigning.verify(withSig(signer.sign("owntally/v1/payload".toByteArray() + unsigned.signedPart()))), "other purpose")
        assertFalse(EnvelopeSigning.verify(withSig(signer.sign("owntally/v2/event".toByteArray() + unsigned.signedPart()))), "other version")
    }

    @Test
    fun anEventFromANonMemberIsRejectedEvenWithAValidSignature() {
        val e = signedByA()
        assertTrue(EnvelopeSigning.verifyFrom(e, listOf(a.publicKey, b.publicKey)))
        assertFalse(EnvelopeSigning.verifyFrom(e, listOf(b.publicKey)))
        assertFalse(EnvelopeSigning.verifyFrom(e, emptyList()))
        val tampered = Envelope(1, group, 3, a.publicKey, 5, nonce, ciphertext, e.signature())
        assertFalse(EnvelopeSigning.verifyFrom(tampered, listOf(a.publicKey)))
    }

    @Test
    fun anAuthorMustMatchTheIdentityThatSigns() {
        assertEquals(a.publicKey, signedByA().author)
        assertFailsWith<IllegalArgumentException> { EnvelopeSigning.sign(a, 1, group, 2, 0, nonce, ciphertext) }
        assertFailsWith<IllegalArgumentException> { EnvelopeSigning.sign(a, 1, group, 2, 5, ByteArray(23), ciphertext) }
    }

    @Test
    fun verifyNeverThrowsOnGarbage() = runBlocking<Unit> {
        checkAll(300, Arb.long()) { seedValue ->
            val rnd = Random(seedValue)
            val e = Envelope(1, group, 2, PublicKey(rnd.nextBytes(32)), 5, nonce, ciphertext, rnd.nextBytes(64))
            assertFalse(EnvelopeSigning.verify(e))
        }
    }

    @Test
    fun anySignedEnvelopeVerifiesAndAnyChangeToItsCiphertextDoesNot() = runBlocking<Unit> {
        checkAll(200, Arb.long()) { s ->
            val rnd = Random(s)
            val id = KeyDerivation.groupIdentity(seed, rnd.nextLong(0, 1000))
            val ct = rnd.nextBytes(rnd.nextInt(0, 200))
            val e = EnvelopeSigning.sign(id, 1, GroupId(rnd.nextBytes(16)), rnd.nextLong(1, 0xFFFF_FFFFL), rnd.nextLong(1, Long.MAX_VALUE), rnd.nextBytes(24), ct)
            assertTrue(EnvelopeSigning.verify(e))
            if (ct.isNotEmpty()) {
                val bent = ct.copyOf().also { it[rnd.nextInt(it.size)] = (it[rnd.nextInt(it.size)].toInt() xor 0x40).toByte() }
                if (!bent.contentEquals(ct)) assertFalse(EnvelopeSigning.verify(Envelope(1, e.groupId, e.epoch, e.author, e.counter, e.nonce(), bent, e.signature())))
            }
        }
    }

    private fun PublicKey.toHex() = bytes().hex()
}
