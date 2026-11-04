package owntally.crypto

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class Bip39Test {
    private fun String.hex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val vectors = Json.parseToJsonElement(Bip39Test::class.java.getResource("/bip39/vectors-english.json")!!.readText()).jsonObject
    private val passphrase = vectors.getValue("passphrase").jsonPrimitive.content

    /** The published vectors of 12 words (128 bits of entropy), the only length the app uses. */
    private val twelveWord = vectors.getValue("vectors").jsonArray.map { v -> v.jsonArray.map { it.jsonPrimitive.content } }.filter { it[1].split(" ").size == 12 }

    @Test
    fun theWordListIsTheReferenceListByteForByte() {
        val bytes = Bip39Test::class.java.getResourceAsStream("/bip39/english.txt")!!.readBytes()
        assertEquals("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda", MessageDigest.getInstance("SHA-256").digest(bytes).hex())
        val words = Bip39.wordList()
        assertEquals(2048, words.size)
        assertEquals(words.sorted(), words)
        assertEquals(2048, words.toSet().size)
        assertEquals(2048, words.map { it.take(4) }.toSet().size, "the first four letters identify a word")
        assertTrue(words.all { w -> w.length in 3..8 && w.all { it in 'a'..'z' } })
        assertEquals("abandon", words.first())
        assertEquals("zoo", words.last())
    }

    @Test
    fun thereAreEightPublishedTwelveWordVectors() = assertEquals(8, twelveWord.size)

    @Test
    fun entropyBecomesThePublishedPhraseAndBack() {
        for ((entropy, mnemonic) in twelveWord) {
            val phrase = Bip39.fromEntropy(entropy.hex())
            assertEquals(mnemonic.split(" "), phrase.words(), entropy)
            assertContentEquals(entropy.hex(), Bip39.entropy(phrase), entropy)
        }
    }

    @Test
    fun thePhraseBecomesThePublishedSeed() {
        for (v in twelveWord) {
            val phrase = RecoveryPhrase.fromText(v[1])
            assertEquals(v[2], Bip39.seed(phrase, passphrase).bytes().hex(), v[1])
        }
    }

    @Test
    fun withNoPassphraseTheSeedMatchesAnIndependentCalculation() {
        // Python hashlib.pbkdf2_hmac("sha512", phrase, "mnemonic", 2048, 64), computed outside this project.
        val phrase = RecoveryPhrase.fromText("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        val expected = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        assertEquals(expected, Bip39.seed(phrase).bytes().hex())
        assertEquals(64, Bip39.seed(phrase).bytes().size)
    }

    @Test
    fun aGeneratedPhraseHasTwelveValidWordsAndTwoDiffer() {
        val one = Bip39.generate()
        val two = Bip39.generate()
        assertEquals(12, one.words().size)
        assertNull(Bip39.check(one.words()))
        assertNotEquals(one.words(), two.words())
    }

    // --- checking what a person typed -----------------------------------------------------------

    private val good = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun aWrongWordCountIsReportedWithTheCount() {
        assertEquals(PhraseProblem.WrongWordCount(11), Bip39.check(good.split(" ").dropLast(1)))
        assertEquals(PhraseProblem.WrongWordCount(13), Bip39.check(good.split(" ") + "zoo"))
        assertEquals(PhraseProblem.WrongWordCount(0), Bip39.check(emptyList()))
    }

    @Test
    fun anUnknownWordIsReportedByPositionAndNotRepeated() {
        val words = good.split(" ").toMutableList().also { it[4] = "abandn" }
        assertEquals(PhraseProblem.UnknownWord(5), Bip39.check(words))
        val e = assertFailsWith<InvalidPhraseException> { RecoveryPhrase.of(words) }
        assertTrue("abandn" !in e.message.orEmpty(), "the message must not repeat what was typed")
    }

    @Test
    fun aMistypedButRealWordFailsTheChecksum() {
        val words = good.split(" ").toMutableList().also { it[11] = "abandon" } // abandon x12: not a valid phrase
        assertEquals(PhraseProblem.BadChecksum, Bip39.check(words))
        assertFailsWith<InvalidPhraseException> { RecoveryPhrase.of(words) }
    }

    @Test
    fun swappingTwoWordsIsCaughtForThisPhrase() {
        val words = twelveWord[1][1].split(" ").toMutableList()
        words[0] = words[1].also { words[1] = words[0] }
        assertEquals(PhraseProblem.BadChecksum, Bip39.check(words))
    }

    @Test
    fun typedTextIsTidiedBeforeChecking() {
        val messy = "  Abandon  ABANDON abandon\tabandon abandon abandon\nabandon abandon abandon abandon abandon About "
        assertEquals(good.split(" "), RecoveryPhrase.fromText(messy).words())
    }

    // --- properties -----------------------------------------------------------------------------

    @Test
    fun anyEntropyRoundTripsAndGivesAValidPhrase() = runBlocking<Unit> {
        checkAll(500, Arb.long()) { seed ->
            val entropy = Random(seed).nextBytes(16)
            val phrase = Bip39.fromEntropy(entropy)
            assertNull(Bip39.check(phrase.words()))
            assertContentEquals(entropy, Bip39.entropy(phrase))
        }
    }

    @Test
    fun changingOneWordNeverGivesTheSameEntropyBack() = runBlocking<Unit> {
        checkAll(200, Arb.long()) { seed ->
            val rnd = Random(seed)
            val entropy = rnd.nextBytes(16)
            val words = Bip39.fromEntropy(entropy).words().toMutableList()
            val i = rnd.nextInt(12)
            val other = Bip39.wordList().filter { it != words[i] }.random(rnd)
            words[i] = other
            val problem = Bip39.check(words)
            if (problem == null) assertTrue(!Bip39.entropy(RecoveryPhrase.of(words)).contentEquals(entropy)) else assertEquals(PhraseProblem.BadChecksum, problem)
        }
    }

    @Test
    fun secretsAreNotPrintedByToString() {
        val phrase = RecoveryPhrase.fromText(good)
        val seed = Bip39.seed(phrase)
        assertTrue("abandon" !in phrase.toString() && "about" !in phrase.toString())
        assertTrue(seed.bytes().hex().take(8) !in seed.toString())
        assertEquals(RecoveryPhrase.fromText(good), phrase)
    }

    @Test
    fun entropyMustBeSixteenBytes() {
        assertFailsWith<IllegalArgumentException> { Bip39.fromEntropy(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { Bip39.fromEntropy(ByteArray(17)) }
    }
}
