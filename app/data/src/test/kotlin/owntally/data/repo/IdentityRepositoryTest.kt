package owntally.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.crypto.Bip39
import owntally.crypto.KeyDerivation
import owntally.data.secrets.AeadSecretBox
import owntally.data.secrets.BoxedSecretStore
import owntally.data.secrets.SecretBox
import owntally.data.secrets.SecretStoreException
import owntally.data.secrets.newAead

class IdentityRepositoryTest {
    private val env = TestEnv()

    @Test
    fun nothingExistsAtFirst() {
        assertEquals(IdentityState.NOT_CREATED, env.identity.state())
        assertNull(env.identity.defaultProfile())
        assertNull(env.identity.groupIdentity(0))
    }

    @Test
    fun firstRunSavesTheSeedAndTheDefaultProfile() {
        val phrase = env.firstRun("Asha", "98765 43210")
        assertEquals(IdentityState.READY, env.identity.state())
        assertEquals(Profile("Asha", "98765 43210"), env.identity.defaultProfile())
        assertEquals(KeyDerivation.groupIdentity(Bip39.seed(phrase), 4), env.identity.groupIdentity(4))
    }

    @Test
    fun thePhraseIsNeverStored() {
        val phrase = env.firstRun()
        val everythingStored = env.blobs.all().values.joinToString(" ") { String(it, Charsets.ISO_8859_1) } + env.settings.all().toString()
        // A leak of the phrase is words side by side, written the ways a phrase could be saved. One word alone proves nothing: the
        // word list has ordinary words such as "phone" and "group" that are also the names of stored settings.
        val words = phrase.words()
        fun leaks(text: String) = words.zipWithNext().flatMap { (x, y) -> listOf("$x $y", "$x,$y", "$x, $y", "\"$x\",\"$y\"", "$x\n$y", "$x$y") }.filter { it in text }
        assertEquals(emptyList(), leaks(everythingStored), "two phrase words side by side were stored")
        assertTrue(leaks("saved: " + words.joinToString(" ")).isNotEmpty(), "the check must find a planted phrase")
        assertTrue(leaks(words.joinToString(",")).isNotEmpty())
    }

    @Test
    fun firstRunCannotBeDoneTwiceSoTheSeedIsNeverOverwrittenByMistake() {
        env.firstRun()
        assertFailsWith<IllegalStateException> { env.identity.completeFirstRun(env.identity.newPhrase(), "Other", "11111 22222") }
    }

    @Test
    fun theNameAndPhoneFollowTheSameRulesAsInEvents() {
        for ((name, phone) in listOf("" to "98765 43210", "   " to "98765 43210", "x".repeat(41) to "98765 43210", "Asha" to "1234", "Asha" to "12345abc", "Asha" to "1".repeat(21))) {
            assertFailsWith<IllegalArgumentException>("$name / $phone") { env.identity.completeFirstRun(env.identity.newPhrase(), name, phone) }
        }
        assertEquals(IdentityState.NOT_CREATED, env.identity.state())
    }

    @Test
    fun ifTheSeedCannotBeSavedNothingLooksCompleted() {
        val broken = TestEnv(object : SecretBox {
            override fun seal(plain: ByteArray, context: ByteArray): ByteArray = throw SecretStoreException("keystore unavailable")
            override fun open(sealed: ByteArray, context: ByteArray): ByteArray? = null
        })
        assertFailsWith<SecretStoreException> { broken.identity.completeFirstRun(broken.identity.newPhrase(), "Asha", "98765 43210") }
        assertEquals(IdentityState.NOT_CREATED, broken.identity.state())
    }

    @Test
    fun aSeedThatCannotBeOpenedIsReportedAndGivesNoKeys() {
        env.firstRun()
        val otherKeystoreKey = IdentityRepository(BoxedSecretStore(AeadSecretBox(newAead()), env.blobs), env.settings)
        assertEquals(IdentityState.SEED_UNREADABLE, otherKeystoreKey.state())
        assertNull(otherKeystoreKey.groupIdentity(0))
    }

    @Test
    fun newPhrasesAreValidAndDifferent() {
        val a = env.identity.newPhrase()
        val b = env.identity.newPhrase()
        assertNotEquals(a.words(), b.words())
        assertNull(Bip39.check(a.words()))
    }

    @Test
    fun theNextSlotIsOneAboveTheHighestUsedAndStartsAtZero() {
        assertEquals(0L, IdentityRepository.nextFreeSlot(emptyList()))
        assertEquals(3L, IdentityRepository.nextFreeSlot(listOf(0, 1, 2)))
        assertEquals(3L, IdentityRepository.nextFreeSlot(listOf(0, 2)), "a gap is not reused")
        assertEquals(10L, IdentityRepository.nextFreeSlot(listOf(9)))
    }

    @Test
    fun aReservedSlotIsNeverHandedOutAgain() {
        env.firstRun()
        assertEquals(0L, env.identity.reserveSlot(emptyList()))
        assertEquals(1L, env.identity.reserveSlot(emptyList()), "the first was used up even though no group was made")
        // Groups that already exist ahead of the counter win (for example if the counter was lost).
        assertEquals(5L, env.identity.reserveSlot(listOf(4L)))
        assertEquals(6L, env.identity.reserveSlot(emptyList()))
    }

    @Test
    fun runningOutOfSlotsIsAnErrorNotAWrapAround() {
        assertFailsWith<IllegalStateException> { IdentityRepository.nextFreeSlot(listOf(4_294_967_295L)) }
        assertTrue(IdentityRepository.nextFreeSlot(listOf(4_294_967_294L)) == 4_294_967_295L)
    }

    @Test
    fun theSeedIsWrittenLastSoTheDefaultsExistWhenFirstRunLooksComplete() {
        val phrase = env.identity.newPhrase()
        env.identity.completeFirstRun(phrase, "Asha", "98765 43210")
        // Completion is the seed's presence; by then the profile must already be there.
        assertEquals(IdentityState.READY, env.identity.state())
        assertEquals(Profile("Asha", "98765 43210"), env.identity.defaultProfile())
    }
}
