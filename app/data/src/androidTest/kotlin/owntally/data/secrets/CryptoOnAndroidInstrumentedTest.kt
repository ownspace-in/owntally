package owntally.data.secrets

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import owntally.crypto.Bip39
import owntally.crypto.EnvelopeSigning
import owntally.crypto.EventSealing
import owntally.crypto.GroupKey
import owntally.crypto.GroupKeyring
import owntally.crypto.KeyDerivation
import owntally.crypto.KeyRotation
import owntally.crypto.OpenResult
import owntally.crypto.RecoveryPhrase
import owntally.crypto.Ticket
import owntally.crypto.TicketRegistry
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.GroupId

/**
 * The crypto module was built and tested on the JVM with Tink's JVM artifact. This runs its main operations on a phone, with
 * Tink's Android artifact, against values from an independent implementation (app/crypto/src/test/tools/crypto_vectors.py).
 */
@RunWith(AndroidJUnit4::class)
class CryptoOnAndroidInstrumentedTest {
    private val phrase = RecoveryPhrase.fromText("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
    private val group = GroupId(ByteArray(16) { 9 })

    @Test
    fun keysFromThePhraseMatchTheIndependentImplementation() {
        val id = KeyDerivation.groupIdentity(Bip39.seed(phrase), 0)
        assertEquals("7500c8382ec89300d935184dbbb9e51eeab57baf6a8088196547509376f013e8", id.publicKey.toHex())
        assertEquals("e9fca55de36d00daebc1252d3904dd4399fe5115896ee99511362d4e2ada9d1b", id.encryptionKey.toHex())
    }

    @Test
    fun signSealOpenAndRotateKeys() {
        val seed = Bip39.seed(phrase)
        val a = KeyDerivation.groupIdentity(seed, 0)
        val b = KeyDerivation.groupIdentity(seed, 1)
        val key = GroupKey.generate()
        val event = Event(1, a.publicKey, 1, emptyMap(), 5, EventBody.ProfileUpdated("Asha", "98765 43210"))
        val envelope = EventSealing.seal(a, group, 1, key, event)
        assertTrue(EnvelopeSigning.verify(envelope))
        val ring = GroupKeyring(group).also { it.add(1, key) }
        assertEquals(OpenResult.Opened(event), EventSealing.open(envelope, ring))

        // HPKE: seal a new epoch's key to b and open it.
        val next = GroupKey.generate()
        val sealed = KeyRotation.seal(group, 2, next, mapOf(b.publicKey to b.encryptionKey))
        assertEquals(next, KeyRotation.open(sealed.getValue(b.publicKey), b, group, 2))
        assertNull(KeyRotation.open(sealed.getValue(b.publicKey), a, group, 2))
    }

    @Test
    fun aGeneratedPhraseRestoresTheSameKeys() {
        val made = Bip39.generate()
        val again = RecoveryPhrase.fromText(made.words().joinToString(" "))
        assertEquals(KeyDerivation.groupIdentity(Bip39.seed(made), 3), KeyDerivation.groupIdentity(Bip39.seed(again), 3))
    }

    @Test
    fun ticketsAreAcceptedOnceOnlyAndTheTextRoundTrips() {
        val host = KeyDerivation.groupIdentity(Bip39.seed(phrase), 5).publicKey
        val registry = TicketRegistry(host)
        val ticket = registry.issue(group, 1, GroupKey.generate(), "relay")
        assertEquals(ticket, Ticket.decode(ticket.encode()))
        assertTrue(registry.accept(ticket))
        assertFalse(registry.accept(ticket))
    }
}
