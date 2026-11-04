package owntally.crypto

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import owntally.model.GroupId
import owntally.model.PublicKey

/** docs/CRYPTO.md section 8: no key or phrase is ever written to a log, and none shows up in printed text. */
class SecretsNotLoggedTest {
    private val phraseText = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val phrase = RecoveryPhrase.fromText(phraseText)
    private val seed = Bip39.seed(phrase)
    private val identity = KeyDerivation.groupIdentity(seed, 7)
    private val groupKey = GroupKey(ByteArray(32) { (0xA0 + it % 16).toByte() })
    private val host = PublicKey(ByteArray(32) { 0x33 })
    private val ticket = Ticket(ByteArray(16) { 0x44 }, host, GroupId(ByteArray(16) { 5 }), 2, groupKey, 1_800_000_000, "relay")
    private val registry = TicketRegistry(host, Clock { 1000 }).also { it.issue(GroupId(ByteArray(16)), 2, groupKey) }
    private val keyring = GroupKeyring(GroupId(ByteArray(16))).also { it.add(1, groupKey); it.add(2, GroupKey(ByteArray(32) { 0x55 })) }

    /** Every form a byte secret could take in printed text. */
    private fun forms(secret: ByteArray): List<String> = listOf(
        secret.joinToString("") { "%02x".format(it) },
        secret.joinToString("") { "%02X".format(it) },
        Base64.getEncoder().encodeToString(secret),
        Base64.getEncoder().withoutPadding().encodeToString(secret),
        Base64.getUrlEncoder().encodeToString(secret),
        Base64.getUrlEncoder().withoutPadding().encodeToString(secret),
        secret.contentToString(),
        secret.joinToString(","),
        secret.joinToString(" "),
    )

    /** The pieces of a secret worth looking for: the whole thing, and any run of 6 bytes from it. */
    private fun needles(secret: ByteArray): List<String> =
        forms(secret) + (0..secret.size - 6 step 3).flatMap { forms(secret.copyOfRange(it, it + 6)).take(2) }

    private val secrets: Map<String, ByteArray> = mapOf(
        "master seed" to seed.bytes(),
        "group key" to groupKey.bytes(),
        "other epoch key" to ByteArray(32) { 0x55 },
        "signing seed" to identity.signingSeed(),
        "encryption private key" to identity.encryptionPrivateKey(),
        "ticket id" to ticket.ticketId(),
    )

    private val printed: Map<String, List<String>> = mapOf(
        "phrase" to listOf(phrase.toString(), "$phrase", String.format("%s", phrase), listOf(phrase).toString(), mapOf("k" to phrase).toString()),
        "seed" to listOf(seed.toString(), "$seed", String.format("%s", seed), listOf(seed).toString()),
        "identity" to listOf(identity.toString(), "$identity", listOf(identity).toString()),
        "group key" to listOf(groupKey.toString(), "$groupKey", listOf(groupKey).toString()),
        "ticket" to listOf(ticket.toString(), "$ticket", listOf(ticket).toString()),
        "registry" to listOf(registry.toString(), "$registry"),
        "keyring" to listOf(keyring.toString(), "$keyring"),
        "open result" to listOf(OpenResult.NeedKey(2).toString(), OpenResult.Rejected(RejectReason.BAD_SIGNATURE).toString()),
    )

    @Test
    fun noPrintedFormShowsASecretInHexBase64OrAsAByteList() {
        for ((label, texts) in printed) for (text in texts) for ((what, secret) in secrets) for (needle in needles(secret)) {
            assertTrue(needle !in text, "$label printed the $what: $text")
        }
    }

    @Test
    fun noPrintedFormShowsTheTicketText() {
        for (text in printed.getValue("ticket") + printed.getValue("registry")) assertTrue(ticket.encode().take(24) !in text, text)
    }

    @Test
    fun noPrintedFormShowsTheWordsOfThePhrase() {
        for ((label, texts) in printed) for (text in texts) for (word in setOf("abandon", "about")) assertTrue(word !in text, "$label printed a phrase word: $text")
    }

    @Test
    fun secretsPrintAsRedacted() {
        for (secret in listOf(phrase.toString(), seed.toString(), identity.toString(), groupKey.toString(), ticket.toString(), registry.toString(), keyring.toString())) {
            assertTrue("redacted" in secret || "epochs" in secret, secret)
        }
    }

    @Test
    fun errorMessagesDoNotRepeatWhatWasTyped() {
        val typed = phraseText.replace("about", "abandn")
        val e = assertFailsWith<InvalidPhraseException> { RecoveryPhrase.fromText(typed) }
        for (word in listOf("abandn", "abandon", "about")) assertTrue(word !in e.message.orEmpty(), e.message)
        val wrongSize = assertFailsWith<IllegalArgumentException> { GroupKey(ByteArray(31) { 0x77 }) }
        for (needle in needles(ByteArray(31) { 0x77 })) assertTrue(needle !in wrongSize.message.orEmpty())
        val badSeed = assertFailsWith<IllegalArgumentException> { MasterSeed(ByteArray(63) { 0x66 }) }
        for (needle in needles(ByteArray(63) { 0x66 })) assertTrue(needle !in badSeed.message.orEmpty())
    }

    @Test
    fun theKeyringShowsWhichEpochsItHoldsAndNothingElse() {
        assertEquals("GroupKeyring(epochs=[1, 2])", keyring.toString())
    }

    @Test
    fun theMainCodeOfTheCoreAndCryptoModulesNeverLogsOrPrints() {
        val roots = listOf(File("src/main/kotlin"), File("../core-model/src/main/kotlin"))
        for (r in roots) assertTrue(r.walkTopDown().any { it.extension == "kt" }, "no sources found in $r")
        val sources = roots.flatMap { r -> r.walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue(sources.size >= 12, "expected to find the sources, found ${sources.size}")
        val forbidden = Regex("""\bprintln\s*\(|\bprint\s*\(|System\.(out|err)|printStackTrace|java\.util\.logging|\bLogger\b|android\.util\.Log|\bTimber\b|\bLog\.[a-z]\(|slf4j""")
        val offenders = sources.flatMap { f -> f.readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" } }
        assertEquals(emptyList(), offenders)
    }
}
