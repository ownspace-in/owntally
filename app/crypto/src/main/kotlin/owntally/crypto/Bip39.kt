package owntally.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** What is wrong with a typed recovery phrase. Never contains the words themselves. */
sealed class PhraseProblem {
    data class WrongWordCount(val count: Int) : PhraseProblem()

    /** [position] counts from 1. */
    data class UnknownWord(val position: Int) : PhraseProblem()
    data object BadChecksum : PhraseProblem()
}

class InvalidPhraseException(val problem: PhraseProblem) : IllegalArgumentException(
    when (problem) {
        is PhraseProblem.WrongWordCount -> "the phrase must have 12 words, not ${problem.count}"
        is PhraseProblem.UnknownWord -> "word ${problem.position} is not in the list"
        PhraseProblem.BadChecksum -> "the words do not form a valid phrase"
    },
)

/** The 12 words of a valid phrase. A secret: [toString] shows nothing. */
class RecoveryPhrase private constructor(private val words: List<String>) {
    fun words(): List<String> = words

    override fun equals(other: Any?) = other is RecoveryPhrase && words == other.words
    override fun hashCode() = words.hashCode()
    override fun toString() = "RecoveryPhrase([redacted])"

    companion object {
        /** Throws [InvalidPhraseException] unless [words] is a valid 12-word phrase. */
        fun of(words: List<String>): RecoveryPhrase {
            Bip39.check(words)?.let { throw InvalidPhraseException(it) }
            return RecoveryPhrase(words.toList())
        }

        /** Words separated by any whitespace, in any case. */
        fun fromText(text: String): RecoveryPhrase = of(text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() })

        internal fun unchecked(words: List<String>) = RecoveryPhrase(words)
    }
}

/** The 64-byte BIP39 seed from which every group key is derived. A secret: [toString] shows nothing. */
class MasterSeed(bytes: ByteArray) {
    private val data: ByteArray

    init {
        require(bytes.size == 64) { "a seed is 64 bytes" }
        data = bytes.copyOf()
    }

    fun bytes(): ByteArray = data.copyOf()
    override fun equals(other: Any?) = other is MasterSeed && MessageDigest.isEqual(data, other.data)
    override fun hashCode() = 0
    override fun toString() = "MasterSeed([redacted])"
}

/**
 * Standard BIP39 with 12 words (128 bits of entropy plus 4 checksum bits), docs/CRYPTO.md section 1. The word list is the
 * reference English list; its SHA-256 is checked before it is used. Hashing and key stretching come from the JDK.
 */
object Bip39 {
    const val ENTROPY_BYTES = 16
    const val WORD_COUNT = 12
    private const val LIST_SHA256 = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"

    private val words: List<String> by lazy {
        val bytes = Bip39::class.java.getResourceAsStream("/bip39/english.txt")!!.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == LIST_SHA256) { "the BIP39 word list has changed" }
        bytes.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }.also { check(it.size == 2048) }
    }
    private val index: Map<String, Int> by lazy { wordList().withIndex().associate { it.value to it.index } }

    fun wordList(): List<String> = words

    fun generate(random: SecureRandom = SecureRandom()): RecoveryPhrase = fromEntropy(ByteArray(ENTROPY_BYTES).also { random.nextBytes(it) })

    fun fromEntropy(entropy: ByteArray): RecoveryPhrase {
        require(entropy.size == ENTROPY_BYTES) { "entropy is $ENTROPY_BYTES bytes" }
        val checksum = MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 0xFF
        // 128 entropy bits followed by the first 4 bits of the hash, read as 12 groups of 11 bits.
        val bits = BooleanArray(132) { i ->
            if (i < 128) (entropy[i / 8].toInt() shr (7 - i % 8)) and 1 == 1 else (checksum shr (7 - (i - 128))) and 1 == 1
        }
        val result = (0 until WORD_COUNT).map { w ->
            var n = 0
            for (b in 0 until 11) n = (n shl 1) or (if (bits[w * 11 + b]) 1 else 0)
            words[n]
        }
        return RecoveryPhrase.unchecked(result)
    }

    /** Null when [words] is a valid phrase, otherwise the first thing that is wrong. */
    fun check(words: List<String>): PhraseProblem? {
        if (words.size != WORD_COUNT) return PhraseProblem.WrongWordCount(words.size)
        val numbers = words.mapIndexed { i, w -> index[w] ?: return PhraseProblem.UnknownWord(i + 1) }
        return if (decode(numbers).second) null else PhraseProblem.BadChecksum
    }

    /** The 16 bytes of entropy behind a valid phrase. */
    fun entropy(phrase: RecoveryPhrase): ByteArray = decode(phrase.words().map { index.getValue(it) }).first

    private fun decode(numbers: List<Int>): Pair<ByteArray, Boolean> {
        val entropy = ByteArray(ENTROPY_BYTES)
        var checksumBits = 0
        for ((w, n) in numbers.withIndex()) for (b in 0 until 11) {
            val bit = (n shr (10 - b)) and 1
            val i = w * 11 + b
            if (i < 128) entropy[i / 8] = (entropy[i / 8].toInt() or (bit shl (7 - i % 8))).toByte() else checksumBits = (checksumBits shl 1) or bit
        }
        val expected = (MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 0xFF) shr 4
        return entropy to (checksumBits == expected)
    }

    /**
     * The BIP39 seed: PBKDF2-HMAC-SHA512 of the phrase, salt "mnemonic" plus [passphrase], 2048 rounds, 64 bytes. The app
     * never uses a passphrase (docs/CRYPTO.md section 1); the parameter exists to check the published test vectors.
     */
    fun seed(phrase: RecoveryPhrase, passphrase: String = ""): MasterSeed {
        val password = Normalizer.normalize(phrase.words().joinToString(" "), Normalizer.Form.NFKD).toCharArray()
        val salt = Normalizer.normalize("mnemonic$passphrase", Normalizer.Form.NFKD).toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(password, salt, 2048, 512)
        try {
            return MasterSeed(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded)
        } finally {
            spec.clearPassword()
            password.fill('\u0000')
        }
    }
}
