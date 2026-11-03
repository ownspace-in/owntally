package owntally.crypto

import com.google.crypto.tink.subtle.X25519
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import owntally.model.EncryptionKey
import owntally.model.GroupId
import owntally.model.PublicKey

/** docs/CRYPTO.md section 2. Expected values come from crypto_vectors.py, an independent implementation. */
class KeyDerivationTest {
    private fun String.hex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val vectors: JsonObject = Json.parseToJsonElement(KeyDerivationTest::class.java.getResource("/crypto-vectors.json")!!.readText()).jsonObject
    private val seed = MasterSeed(vectors.getValue("seed").jsonPrimitive.content.hex())
    private val slots = vectors.getValue("slots").jsonArray.map { it.jsonObject }

    @Test
    fun theVectorSeedIsWhatThePhraseGives() {
        val phrase = RecoveryPhrase.fromText(vectors.getValue("phrase").jsonPrimitive.content)
        assertEquals(seed, Bip39.seed(phrase))
    }

    @Test
    fun everySlotGivesTheIndependentlyComputedKeys() {
        assertTrue(slots.size >= 5)
        for (v in slots) {
            val slot = v.getValue("slot").jsonPrimitive.long
            val id = KeyDerivation.groupIdentity(seed, slot)
            assertEquals(v.getValue("signPub").jsonPrimitive.content, id.publicKey.toHex(), "slot $slot signing key")
            assertEquals(v.getValue("encPub").jsonPrimitive.content, id.encryptionKey.toHex(), "slot $slot encryption key")
            assertEquals(v.getValue("signSeed").jsonPrimitive.content, id.signingSeed().hex(), "slot $slot signing seed")
            assertEquals(v.getValue("encPriv").jsonPrimitive.content, id.encryptionPrivateKey().hex(), "slot $slot encryption private key")
            assertEquals(slot, id.slot)
        }
    }

    @Test
    fun theSamePhraseAlwaysGivesTheSameKeys() {
        val one = KeyDerivation.groupIdentity(seed, 3)
        val two = KeyDerivation.groupIdentity(MasterSeed(seed.bytes()), 3)
        assertEquals(one, two)
        assertEquals(one.publicKey, two.publicKey)
        assertEquals(one.encryptionKey, two.encryptionKey)
    }

    @Test
    fun differentSlotsGiveUnrelatedKeys() {
        val ids = (0L..199L).map { KeyDerivation.groupIdentity(seed, it) }
        assertEquals(200, ids.map { it.publicKey }.toSet().size)
        assertEquals(200, ids.map { it.encryptionKey }.toSet().size)
    }

    @Test
    fun aDifferentPhraseGivesDifferentKeys() {
        val other = Bip39.seed(RecoveryPhrase.fromText("legal winner thank year wave sausage worth useful legal winner thank yellow"))
        assertNotEquals(KeyDerivation.groupIdentity(seed, 0).publicKey, KeyDerivation.groupIdentity(other, 0).publicKey)
    }

    @Test
    fun theSigningAndEncryptionKeysAreDifferentKeys() {
        val id = KeyDerivation.groupIdentity(seed, 0)
        assertNotEquals(id.signingSeed().hex(), id.encryptionPrivateKey().hex())
        assertNotEquals(id.publicKey.toHex(), id.encryptionKey.toHex())
    }

    @Test
    fun theSlotMustFitInThirtyTwoBits() {
        assertFailsWith<IllegalArgumentException> { KeyDerivation.groupIdentity(seed, -1) }
        assertFailsWith<IllegalArgumentException> { KeyDerivation.groupIdentity(seed, 4_294_967_296L) }
        KeyDerivation.groupIdentity(seed, 4_294_967_295L)
    }

    @Test
    fun twoMembersAgreeOnTheSameSharedSecret() {
        val a = KeyDerivation.groupIdentity(seed, 10)
        val b = KeyDerivation.groupIdentity(seed, 11)
        assertEquals(
            X25519.computeSharedSecret(a.encryptionPrivateKey(), b.encryptionKey.bytes()).hex(),
            X25519.computeSharedSecret(b.encryptionPrivateKey(), a.encryptionKey.bytes()).hex(),
        )
    }

    @Test
    fun theIdentityPrintsNoKeys() {
        val id = KeyDerivation.groupIdentity(seed, 0)
        val text = id.toString()
        for (secret in listOf(id.signingSeed(), id.encryptionPrivateKey(), id.publicKey.bytes())) {
            assertTrue(secret.hex().take(16) !in text, text)
        }
        assertTrue("redacted" in text)
    }

    @Test
    fun anySlotGivesKeysThatSignAndAgree() = runBlocking<Unit> {
        checkAll(100, Arb.long(0L, 4_294_967_295L)) { slot ->
            val id = KeyDerivation.groupIdentity(seed, slot)
            val signature = EnvelopeSigning.sign(id, 1, GroupId(ByteArray(16)), 1, 1, ByteArray(24), ByteArray(3))
            assertTrue(EnvelopeSigning.verify(signature), "slot $slot")
        }
    }
}

private fun PublicKey.toHex() = bytes().joinToString("") { "%02x".format(it) }
private fun EncryptionKey.toHex() = bytes().joinToString("") { "%02x".format(it) }
