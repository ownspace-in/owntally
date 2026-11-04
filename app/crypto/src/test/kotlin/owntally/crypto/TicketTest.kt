package owntally.crypto

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import owntally.model.GroupId
import owntally.model.PublicKey

/** docs/CRYPTO.md section 7. Expected texts come from crypto_vectors.py, an independent implementation. */
class TicketTest {
    private fun String.hex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private class FakeClock(var now: Long) : Clock {
        override fun nowSeconds() = now
    }

    private val vectors: JsonObject = Json.parseToJsonElement(TicketTest::class.java.getResource("/crypto-vectors.json")!!.readText()).jsonObject
    private val host = PublicKey(ByteArray(32) { 0x11 })
    private val otherHost = PublicKey(ByteArray(32) { 0x22 })
    private val group = GroupId(ByteArray(16) { 9 })
    private val key = GroupKey(ByteArray(32) { it.toByte() })

    private fun fromVector(v: JsonObject) = Ticket(
        v.getValue("ticketId").jsonPrimitive.content.hex(), PublicKey(v.getValue("hostId").jsonPrimitive.content.hex()),
        GroupId(v.getValue("groupId").jsonPrimitive.content.hex()), v.getValue("epoch").jsonPrimitive.long,
        GroupKey(v.getValue("groupKey").jsonPrimitive.content.hex()), v.getValue("expires").jsonPrimitive.long, v.getValue("hint").jsonPrimitive.content,
    )

    // --- format -----------------------------------------------------------------------------------

    @Test
    fun everyVectorEncodesToTheGivenTextAndDecodesBack() {
        val list = vectors.getValue("tickets").jsonArray.map { it.jsonObject }
        assertTrue(list.size >= 4)
        for (v in list) {
            val text = v.getValue("text").jsonPrimitive.content
            val ticket = fromVector(v)
            assertEquals(text, ticket.encode(), v.getValue("name").jsonPrimitive.content)
            assertEquals(ticket, Ticket.decode(text), v.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun aTicketWithNoHintIs152Characters() {
        val v = vectors.getValue("tickets").jsonArray.map { it.jsonObject }.first { it.getValue("name").jsonPrimitive.content == "no-hint" }
        assertEquals(152, fromVector(v).encode().length)
    }

    @Test
    fun everyInvalidVectorIsRejected() {
        val list = vectors.getValue("invalidTickets").jsonArray.map { it.jsonObject }
        assertTrue(list.size >= 15)
        for (v in list) assertNull(Ticket.decode(v.getValue("text").jsonPrimitive.content), v.getValue("name").jsonPrimitive.content + ": " + v.getValue("why").jsonPrimitive.content)
    }

    @Test
    fun changingAnyCharacterOfATicketIsCaught() {
        val text = Ticket(ByteArray(16) { 1 }, host, group, 2, key, 1_800_000_000, "").encode()
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        for (i in text.indices) {
            val other = alphabet.first { it != text[i] }
            assertNull(Ticket.decode(text.substring(0, i) + other + text.substring(i + 1)), "character $i")
        }
    }

    @Test
    fun everyShorterPrefixIsRejected() {
        val text = Ticket(ByteArray(16) { 1 }, host, group, 2, key, 1_800_000_000, "relay").encode()
        for (n in 0 until text.length) assertNull(Ticket.decode(text.take(n)), "length $n")
    }

    @Test
    fun aLinkOrPastedTextWithSpacesGivesTheSameTicket() {
        val ticket = Ticket(ByteArray(16) { 1 }, host, group, 2, key, 1_800_000_000, "")
        val text = ticket.encode()
        assertEquals(ticket, Ticket.fromShared(text))
        assertEquals(ticket, Ticket.fromShared("  $text\n"))
        assertEquals(ticket, Ticket.fromShared("https://joinowntally.ownspace.in/j#$text"))
        assertNull(Ticket.fromShared("https://joinowntally.ownspace.in/j"))
        assertNull(Ticket.fromShared("hello"))
    }

    @Test
    fun theHintIsLimitedToOneHundredBytesNotCharacters() {
        Ticket(ByteArray(16), host, group, 2, key, 1, "h".repeat(100))
        assertFailsWith<IllegalArgumentException> { Ticket(ByteArray(16), host, group, 2, key, 1, "h".repeat(101)) }
        assertFailsWith<IllegalArgumentException> { Ticket(ByteArray(16), host, group, 2, key, 1, "€".repeat(34)) } // 102 bytes
        assertFailsWith<IllegalArgumentException> { Ticket(ByteArray(15), host, group, 2, key, 1, "") }
        assertFailsWith<IllegalArgumentException> { Ticket(ByteArray(16), host, group, 0, key, 1, "") }
    }

    @Test
    fun aTicketPrintsNothingSecret() {
        val ticket = Ticket(ByteArray(16) { 1 }, host, group, 2, key, 1_800_000_000, "relay")
        val text = ticket.toString()
        assertTrue("redacted" in text)
        assertFalse(ticket.encode().take(20) in text)
    }

    @Test
    fun anyTicketRoundTrips() = runBlocking<Unit> {
        checkAll(500, Arb.long()) { s ->
            val rnd = Random(s)
            val ticket = Ticket(rnd.nextBytes(16), PublicKey(rnd.nextBytes(32)), GroupId(rnd.nextBytes(16)), rnd.nextLong(1, 0xFFFF_FFFFL), GroupKey(rnd.nextBytes(32)), rnd.nextLong(0, Long.MAX_VALUE), "x".repeat(rnd.nextInt(0, 101)))
            assertEquals(ticket, Ticket.decode(ticket.encode()))
        }
    }

    // --- the host's side --------------------------------------------------------------------------

    private fun registry(clock: Clock) = TicketRegistry(host, clock)

    @Test
    fun aFreshTicketIsAcceptedOnceAndNotAgain() {
        val clock = FakeClock(1_000_000)
        val registry = registry(clock)
        val ticket = registry.issue(group, 2, key)
        assertTrue(registry.accept(ticket))
        assertFalse(registry.accept(ticket))
        assertFalse(registry.accept(Ticket.decode(ticket.encode())!!), "the same ticket, read back from its text")
    }

    @Test
    fun aTicketLastsEightHours() {
        val clock = FakeClock(1_000_000)
        val registry = registry(clock)
        val ticket = registry.issue(group, 2, key)
        assertEquals(1_000_000 + 8 * 3600L, ticket.expiresAtSeconds)
        clock.now = ticket.expiresAtSeconds - 1
        assertFalse(ticket.isExpired(clock.now))
        assertTrue(registry.accept(ticket), "one second before the end")
        val second = registry.issue(group, 2, key)
        clock.now = second.expiresAtSeconds
        assertTrue(second.isExpired(clock.now))
        assertFalse(registry.accept(second), "at the moment it ends")
    }

    @Test
    fun anExpiredTicketIsRejectedEvenIfNeverUsed() {
        val clock = FakeClock(0)
        val registry = registry(clock)
        val ticket = registry.issue(group, 2, key)
        clock.now = 8 * 3600L + 1
        assertFalse(registry.accept(ticket))
    }

    @Test
    fun aTicketForAnotherHostIsRejected() {
        val clock = FakeClock(0)
        val mine = registry(clock)
        val theirs = TicketRegistry(otherHost, clock)
        val ticket = theirs.issue(group, 2, key)
        assertFalse(mine.accept(ticket))
        assertTrue(theirs.accept(ticket), "still good at the host that issued it")
    }

    @Test
    fun aTicketTheHostNeverIssuedIsRejected() {
        val clock = FakeClock(0)
        val registry = registry(clock)
        val forged = Ticket(ByteArray(16) { 7 }, host, group, 2, key, clock.now + 1000, "")
        assertFalse(registry.accept(forged))
        // A host that has forgotten its tickets (for example after a restart before storage exists) refuses old ones.
        val old = registry.issue(group, 2, key)
        assertFalse(registry(clock).accept(old))
    }

    @Test
    fun aTicketWithTheRightIdButADifferentGroupKeyOrEpochIsRejected() {
        val clock = FakeClock(0)
        val registry = registry(clock)
        val real = registry.issue(group, 2, key)
        val otherKey = Ticket(real.ticketId(), real.hostId, real.groupId, real.epoch, GroupKey(ByteArray(32) { 5 }), real.expiresAtSeconds, real.hint)
        val otherEpoch = Ticket(real.ticketId(), real.hostId, real.groupId, 3, real.groupKey, real.expiresAtSeconds, real.hint)
        val longer = Ticket(real.ticketId(), real.hostId, real.groupId, real.epoch, real.groupKey, real.expiresAtSeconds + 99999, real.hint)
        assertFalse(registry.accept(otherKey))
        assertFalse(registry.accept(otherEpoch))
        assertFalse(registry.accept(longer))
        assertTrue(registry.accept(real))
    }

    @Test
    fun issuedTicketsAreDifferent() {
        val registry = registry(FakeClock(0))
        val a = registry.issue(group, 2, key)
        val b = registry.issue(group, 2, key)
        assertNotEquals(a, b)
        assertNotEquals(a.encode(), b.encode())
    }

    @Test
    fun whenManyTryAtOnceExactlyOneWins() {
        val registry = registry(FakeClock(0))
        val ticket = registry.issue(group, 2, key)
        val wins = AtomicInteger()
        val pool = Executors.newFixedThreadPool(16)
        try {
            val tasks = (1..200).map { pool.submit { if (registry.accept(ticket)) wins.incrementAndGet() } }
            tasks.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(1, wins.get())
    }

    @Test
    fun theRegistryPrintsNoTicketIds() {
        val registry = registry(FakeClock(0))
        val ticket = registry.issue(group, 2, key)
        assertFalse(ticket.ticketId().joinToString("") { "%02x".format(it) } in registry.toString())
        assertTrue("redacted" in registry.toString())
    }
}
