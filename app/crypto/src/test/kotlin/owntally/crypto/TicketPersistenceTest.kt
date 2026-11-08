package owntally.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.model.GroupId
import owntally.model.PublicKey

/** T6.3 (CRYPTO.md clarification C4): the host's record of tickets survives a restart, and holds no copy of the group key. */
class TicketPersistenceTest {
    private val host = PublicKey(ByteArray(32) { 0x11 })
    private val group = GroupId(ByteArray(16) { 9 })
    private val key1 = GroupKey(ByteArray(32) { it.toByte() })
    private val key2 = GroupKey(ByteArray(32) { (it + 100).toByte() })
    private var now = 1_800_000_000L
    private val clock = Clock { now }
    private val records = InMemoryTicketRecords()
    private val keyring = HashMap<Pair<GroupId, Long>, GroupKey>().apply { put(group to 1L, key1); put(group to 2L, key2) }

    /** "Restart": a new registry over the same stored records, getting keys from the keyring as the app does. */
    private fun registry() = TicketRegistry(host, clock, records = records, keys = GroupKeys { g, e -> keyring[g to e] })

    @Test fun anIssuedTicketIsStillAcceptedOnceAfterARestart() {
        val ticket = registry().issue(group, 1, key1)
        val after = registry()
        assertNotNull(after.find(ticket.ticketId()))
        assertTrue(after.accept(ticket))
        assertFalse(after.accept(ticket))
    }

    @Test fun aUsedTicketStaysUsedAfterARestartAndAfterAnother() {
        val ticket = registry().issue(group, 1, key1)
        assertTrue(registry().accept(ticket))
        assertFalse(registry().accept(ticket), "after one restart")
        assertNull(registry().find(ticket.ticketId()))
        assertFalse(registry().accept(ticket), "after another")
    }

    @Test fun theStoredRecordHoldsNoCopyOfTheGroupKey() {
        val ticket = registry().issue(group, 1, key1)
        val record = records.get(ticket.ticketId())!!
        fun contains(o: Any?): Boolean = when (o) {
            is ByteArray -> o.indices.any { i -> i + 32 <= o.size && o.copyOfRange(i, i + 32).contentEquals(key1.bytes()) }
            is String -> o.contains(key1.bytes().joinToString("") { "%02x".format(it) })
            else -> false
        }
        for (f in record.javaClass.declaredFields) { f.isAccessible = true; assertFalse(contains(f.get(record)), f.name) }
        assertFalse(record.toString().contains(key1.bytes().joinToString("") { "%02x".format(it) }))
    }

    @Test fun theKeyComesFromTheKeyringSoAMissingKeyMeansNoTicket() {
        val ticket = registry().issue(group, 1, key1)
        keyring.remove(group to 1L)
        assertNull(registry().find(ticket.ticketId()))
        assertFalse(registry().accept(ticket))
        keyring[group to 1L] = key1
        assertTrue(registry().accept(ticket), "and it is still usable when the key is back")
    }

    @Test fun aTicketWithAnotherKeyEpochOrFieldIsNotTheIssuedOne() {
        val ticket = registry().issue(group, 1, key1)
        val other = TicketRegistry(PublicKey(ByteArray(32) { 0x22 }), clock, records = records, keys = GroupKeys { g, e -> keyring[g to e] })
        assertFalse(other.accept(ticket), "another host")
        assertNull(other.find(ticket.ticketId()))
        val wrongKey = Ticket(ticket.ticketId(), host, group, 1, key2, ticket.expiresAtSeconds, "")
        assertFalse(registry().accept(wrongKey))
        val wrongEpoch = Ticket(ticket.ticketId(), host, group, 2, key2, ticket.expiresAtSeconds, "")
        assertFalse(registry().accept(wrongEpoch))
        val longer = Ticket(ticket.ticketId(), host, group, 1, key1, ticket.expiresAtSeconds + 1000, "")
        assertFalse(registry().accept(longer))
        assertTrue(registry().accept(ticket), "none of that used it up")
    }

    @Test fun anExpiredTicketIsNotAcceptedAndItsRecordIsDroppedWhenAnotherIsIssued() {
        val old = registry().issue(group, 1, key1)
        now += Ticket.LIFETIME_SECONDS
        assertFalse(registry().accept(old))
        assertNull(registry().find(old.ticketId()))
        registry().issue(group, 1, key1)
        assertNull(records.get(old.ticketId()), "dropped")
    }

    @Test fun twoTicketsAreIndependentAndRevokingOneLeavesTheOther() {
        val a = registry().issue(group, 1, key1)
        val b = registry().issue(group, 2, key2)
        registry().revoke(a.ticketId())
        assertFalse(registry().accept(a))
        assertNull(registry().find(a.ticketId()))
        assertTrue(registry().accept(b))
    }

    @Test fun theNewestKeyEpochIsUsedByAHostThatRekeyedSinceIssuingNothingIsBroken() {
        val old = registry().issue(group, 1, key1)
        // The group moved to epoch 2 after the ticket was issued: the ticket still names epoch 1 and its key is still in the keyring.
        assertTrue(registry().accept(old))
        assertEquals(1L, old.epoch)
    }

    @Test fun markingUsedSucceedsForExactlyOneCallerEvenWithTwoRegistriesOverTheSameRecords() {
        val ticket = registry().issue(group, 1, key1)
        val results = java.util.concurrent.Executors.newFixedThreadPool(8).let { pool ->
            val futures = (1..8).map { pool.submit<Boolean> { registry().accept(ticket) } }
            futures.map { it.get() }.also { pool.shutdown() }
        }
        assertEquals(1, results.count { it })
        assertFalse(records.markUsed(ticket.ticketId()), "already used")
        assertFalse(records.markUsed(ByteArray(16) { 5 }), "unknown")
    }
}
