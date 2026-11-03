package owntally.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EventTest {
    private val a = key(1)
    private val b = key(2)
    private val c = key(3)

    private fun event(counter: Long, body: EventBody, after: Map<PublicKey, Long> = if (counter > 1) mapOf(a to counter - 1) else emptyMap()) =
        Event(version = 1, author = a, counter = counter, after = after, time = 1_000, body = body)

    @Test
    fun everyBodyTypeCanBeBuilt() {
        val id = EventId(a, 3)
        val bodies = listOf(
            EventBody.GroupCreated(GROUP, "Goa Trip", "INR"),
            EventBody.MemberJoined(b, "Ben", "+91 98765 43210"),
            EventBody.ProfileUpdated("Asha", "98765 43210"),
            EventBody.ExpenseAdded("Dinner", rupees(900), a, 20_000, Split.Equal(setOf(a, b, c))),
            EventBody.ExpenseAdded("Taxi", rupees(600), b, 20_000, Split.Exact(mapOf(a to rupees(200), b to rupees(400)))),
            EventBody.ExpenseDeleted(id),
            EventBody.SettlementRecorded(c, a, rupees(400)),
            EventBody.SettlementConfirmed(id),
            EventBody.PaymentDisputed(id, DisputeReason.NOTHING_RECEIVED),
            EventBody.AdminAdded(b),
            EventBody.AdminRemoved(b),
            EventBody.OwnershipOffered(b),
            EventBody.OwnershipOfferCancelled,
            EventBody.OwnershipAccepted,
            EventBody.MemberRemoved(c),
            EventBody.Unknown("FutureThing"),
        )
        bodies.forEachIndexed { i, body -> event(i + 1L, body) }
    }

    @Test
    fun expenseNeedsPositiveTotalAndDescription() {
        val split = Split.Equal(setOf(a))
        assertFailsWith<IllegalArgumentException> { EventBody.ExpenseAdded("x", Money(0), a, 1, split) }
        assertFailsWith<IllegalArgumentException> { EventBody.ExpenseAdded("x", Money(-100), a, 1, split) }
        assertFailsWith<IllegalArgumentException> { EventBody.ExpenseAdded("", rupees(1), a, 1, split) }
        assertFailsWith<IllegalArgumentException> { EventBody.ExpenseAdded("x".repeat(81), rupees(1), a, 1, split) }
        EventBody.ExpenseAdded("x".repeat(80), rupees(1), a, 1, split)
    }

    @Test
    fun equalSplitNeedsParticipants() {
        assertFailsWith<IllegalArgumentException> { Split.Equal(emptySet()) }
    }

    @Test
    fun exactSplitMustSumToTotal() {
        val split = Split.Exact(mapOf(a to rupees(200), b to rupees(300)))
        assertFailsWith<IllegalArgumentException> { EventBody.ExpenseAdded("x", rupees(600), a, 1, split) }
        EventBody.ExpenseAdded("x", rupees(500), a, 1, split)
    }

    @Test
    fun exactSplitAmountsMustBePositiveAndNotEmpty() {
        assertFailsWith<IllegalArgumentException> { Split.Exact(emptyMap()) }
        assertFailsWith<IllegalArgumentException> { Split.Exact(mapOf(a to Money(0), b to rupees(5))) }
        assertFailsWith<IllegalArgumentException> { Split.Exact(mapOf(a to Money(-5), b to rupees(5))) }
    }

    @Test
    fun exactSplitSumOverflowIsRejectedNotWrapped() {
        val big = Money(Long.MAX_VALUE / 2 + 1)
        assertFailsWith<ArithmeticException> { Split.Exact(mapOf(a to big, b to big)).sum() }
    }

    @Test
    fun settlementNeedsTwoDifferentMembersAndPositiveAmount() {
        assertFailsWith<IllegalArgumentException> { EventBody.SettlementRecorded(a, a, rupees(1)) }
        assertFailsWith<IllegalArgumentException> { EventBody.SettlementRecorded(a, b, Money(0)) }
        assertFailsWith<IllegalArgumentException> { EventBody.SettlementRecorded(a, b, Money(-1)) }
    }

    @Test
    fun namesPhonesAndCurrencyAreChecked() {
        assertFailsWith<IllegalArgumentException> { EventBody.GroupCreated(GROUP, "", "INR") }
        assertFailsWith<IllegalArgumentException> { EventBody.GroupCreated(GROUP, "   ", "INR") }
        assertFailsWith<IllegalArgumentException> { EventBody.GroupCreated(GROUP, "x".repeat(41), "INR") }
        assertFailsWith<IllegalArgumentException> { EventBody.GroupCreated(GROUP, "Trip", "inr") }
        EventBody.GroupCreated(GROUP, "x".repeat(40), "INR")
        assertFailsWith<IllegalArgumentException> { EventBody.MemberJoined(b, "Ben", "1234") }
        assertFailsWith<IllegalArgumentException> { EventBody.MemberJoined(b, "Ben", "12345abc") }
        assertFailsWith<IllegalArgumentException> { EventBody.MemberJoined(b, "Ben", "1".repeat(21)) }
        EventBody.MemberJoined(b, "Ben", "+91 98765 43210")
    }

    @Test
    fun counterStartsAtOneAndAfterMustBeConsistent() {
        val body = EventBody.OwnershipAccepted
        assertFailsWith<IllegalArgumentException> { event(0, body, emptyMap()) }
        // Event 1 has no own previous counter.
        assertFailsWith<IllegalArgumentException> { event(1, body, mapOf(a to 1)) }
        // Later events must include the author's own previous counter exactly.
        assertFailsWith<IllegalArgumentException> { event(3, body, mapOf(a to 1)) }
        assertFailsWith<IllegalArgumentException> { event(3, body, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { event(3, body, mapOf(a to 2, b to 0)) }
        event(3, body, mapOf(a to 2, b to 7))
    }

    @Test
    fun versionStartsAtOne() {
        assertFailsWith<IllegalArgumentException> { Event(0, a, 1, emptyMap(), 0, EventBody.OwnershipAccepted) }
    }

    @Test
    fun eventCopiesItsAfterMap() {
        val after = mutableMapOf(a to 1L)
        val e = event(2, EventBody.OwnershipAccepted, after)
        after[b] = 50
        assertEquals(mapOf(a to 1L), e.after)
    }

    @Test
    fun eventsWithSameContentAreEqual() {
        assertEquals(event(1, EventBody.OwnershipAccepted), event(1, EventBody.OwnershipAccepted))
        assertEquals(EventId(a, 4), event(4, EventBody.OwnershipAccepted).id)
    }

    @Test
    fun dependenciesAreOwnPreviousAndWhatWasSeen() {
        val e = Event(1, a, 4, mapOf(a to 3, b to 5, c to 2), 0, EventBody.OwnershipAccepted)
        assertEquals(setOf(EventId(a, 3), EventId(b, 5), EventId(c, 2)), e.dependencies())
        assertEquals(emptySet(), event(1, EventBody.OwnershipAccepted).dependencies())
    }
}
