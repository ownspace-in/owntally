package owntally.crypto

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import owntally.model.GroupId
import owntally.model.PublicKey

/**
 * Found by a flaky test in T6.8: Ed25519 accepts a signature of zeros from a key of small order for about a quarter of all messages. Such
 * a key belongs to nobody, so nothing signed by it may count.
 */
class WeakKeysTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val weak = listOf(
        "0000000000000000000000000000000000000000000000000000000000000000",
        "0100000000000000000000000000000000000000000000000000000000000000",
        "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
        "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    ).map(::hex)

    private fun rawAccepts(key: ByteArray, signature: ByteArray, message: ByteArray) = try { Ed25519Verify(key).verify(signature, message); true } catch (e: GeneralSecurityException) { false }

    @Test fun theVerifierItselfAcceptsAZeroSignatureFromAZeroKeyForSomeMessages() {
        // The reason for the check below: this is not a made-up risk.
        val rnd = Random(1)
        val accepted = (1..400).count { rawAccepts(ByteArray(32), ByteArray(64), rnd.nextBytes(40)) }
        assertTrue(accepted in 1..399, "some but not all: $accepted of 400")
    }

    @Test fun everyKeyOfSmallOrderIsRecognisedWithOrWithoutTheSignBit() {
        for (k in weak) {
            assertTrue(WeakKeys.isWeak(k), k.joinToString("") { "%02x".format(it) })
            assertTrue(WeakKeys.isWeak(k.copyOf().also { it[31] = (it[31].toInt() or 0x80).toByte() }), "sign bit set")
        }
    }

    @Test fun realKeysAreNotWeak() {
        val seed = MasterSeed(ByteArray(64) { 5 })
        for (slot in 0L..200L) assertFalse(WeakKeys.isWeak(KeyDerivation.groupIdentity(seed, slot).publicKey.bytes()), "slot $slot")
        assertFalse(WeakKeys.isWeak(ByteArray(32) { 7 }))
        assertFalse(WeakKeys.isWeak(ByteArray(5)), "a wrong size is not a weak key (it is no key; the verifier refuses it)")
    }

    @Test fun noVerificationInTheAppAcceptsAWeakKeyWhateverTheMessage() {
        val rnd = Random(2)
        val seed = MasterSeed(ByteArray(64) { 6 })
        val host = KeyDerivation.groupIdentity(seed, 0)
        for (k in weak.take(4)) {
            val key = PublicKey(k)
            repeat(300) {
                val m = rnd.nextBytes(48)
                assertFalse(LinkAuth.verifyServer(key, m, ByteArray(64)))
                assertFalse(LinkAuth.verifyClient(key, m, ByteArray(64)))
                assertFalse(LinkAuth.verifyTlsBinding(key, m, ByteArray(64)))
                assertFalse(JoinCrypto.verifyHost(key, GroupId(ByteArray(16)), m.copyOf(16), m.copyOf(32), ByteArray(64)))
                assertFalse(JoinCrypto.verifyRequest(key, GroupId(ByteArray(16)), host.publicKey, m.copyOf(32), m, ByteArray(64)))
                assertFalse(JoinCrypto.verifyStatus(key, GroupId(ByteArray(16)), host.publicKey, m.copyOf(32), ByteArray(64)))
            }
        }
    }

    @Test fun anEnvelopeFromAWeakKeyWithAZeroSignatureNeverVerifies() {
        val rnd = Random(3)
        for (k in weak.take(4)) repeat(300) {
            val envelope = owntally.model.Envelope(1, GroupId(ByteArray(16) { 1 }), 1, PublicKey(k), 1 + rnd.nextLong(100), rnd.nextBytes(24), rnd.nextBytes(40), ByteArray(owntally.model.Envelope.SIGNATURE_SIZE))
            assertFalse(EnvelopeSigning.verify(envelope))
        }
        assertEquals(7, weak.size)
    }
}
