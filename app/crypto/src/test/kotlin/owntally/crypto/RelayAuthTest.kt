package owntally.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import owntally.model.PublicKey

/**
 * M12, T12.2 (docs/CRYPTO.md section 7a): the signature a member gives the relay to prove it holds the key it connects as. The expected values come
 * from crypto_vectors.py, an independent implementation; the relay's Go tests read the same file, so the two sides cannot disagree.
 */
class RelayAuthTest {
    private val root = Json.parseToJsonElement(RelayAuthTest::class.java.getResource("/crypto-vectors.json")!!.readText()).jsonObject
    private val vectors = root.getValue("relayAuth").jsonArray.map { it.jsonObject }
    private val seed = Bip39.seed(RecoveryPhrase.fromText(root.getValue("phrase").jsonPrimitive.content))
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun String.field(v: kotlinx.serialization.json.JsonObject) = v.getValue(this).jsonPrimitive.content

    @Test fun everyVectorVerifiesOrFailsAsTheIndependentImplementationSays() {
        assertTrue(vectors.size >= 8)
        for (v in vectors) {
            val ok = RelayAuth.verify(PublicKey(hex("id".field(v))), hex("room".field(v)), hex("nonce".field(v)), hex("signature".field(v)))
            assertEquals(v.getValue("valid").jsonPrimitive.boolean, ok, "name".field(v))
        }
    }

    @Test fun ourSignatureIsTheSameBytesTheIndependentImplementationMakes() {
        val good = vectors.filter { it.getValue("valid").jsonPrimitive.boolean }
        assertTrue(good.isNotEmpty())
        for (v in good) {
            val id = "id".field(v)
            val identity = (0L..300L).map { KeyDerivation.groupIdentity(seed, it) }.first { it.publicKey.toHex() == id }
            assertEquals("signature".field(v), RelayAuth.sign(identity, hex("room".field(v)), hex("nonce".field(v))).joinToString("") { "%02x".format(it) }, "name".field(v))
        }
    }

    @Test fun aSignatureHoldsOnlyForItsRoomItsNonceAndItsKey() {
        val a = KeyDerivation.groupIdentity(seed, 0); val b = KeyDerivation.groupIdentity(seed, 1)
        val room = ByteArray(16) { it.toByte() }; val nonce = RelayAuth.newNonce()
        val s = RelayAuth.sign(a, room, nonce)
        assertTrue(RelayAuth.verify(a.publicKey, room, nonce, s))
        assertFalse(RelayAuth.verify(b.publicKey, room, nonce, s), "another key")
        assertFalse(RelayAuth.verify(a.publicKey, ByteArray(16) { 9 }, nonce, s), "another room")
        assertFalse(RelayAuth.verify(a.publicKey, room, RelayAuth.newNonce(), s), "another connection's nonce: a replay")
        assertFalse(RelayAuth.verify(a.publicKey, room, nonce, s.copyOf().also { it[3] = (it[3] + 1).toByte() }), "changed")
        assertFalse(RelayAuth.verify(a.publicKey, room, nonce, ByteArray(5)), "short, and no throw")
    }

    @Test fun theNonceIsRightSizeAndRandomAndTheRoomIsChecked() {
        assertEquals(32, RelayAuth.newNonce().size)
        assertFalse(RelayAuth.newNonce().contentEquals(RelayAuth.newNonce()))
        val a = KeyDerivation.groupIdentity(seed, 0)
        kotlin.test.assertFailsWith<IllegalArgumentException> { RelayAuth.sign(a, ByteArray(15), RelayAuth.newNonce()) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { RelayAuth.sign(a, ByteArray(33), RelayAuth.newNonce()) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { RelayAuth.sign(a, ByteArray(16), ByteArray(31)) }
    }

    @Test fun aSignatureForTheRelayIsNotOneForAnythingElse() {
        // The same bytes under another label (a link proof, a join proof) never verify as a relay answer.
        val a = KeyDerivation.groupIdentity(seed, 0)
        val room = ByteArray(16) { 3 }; val nonce = ByteArray(32) { 4 }
        val link = LinkAuth.signAsServer(a, room + a.publicKey.bytes() + nonce)
        assertFalse(RelayAuth.verify(a.publicKey, room, nonce, link))
    }
}
