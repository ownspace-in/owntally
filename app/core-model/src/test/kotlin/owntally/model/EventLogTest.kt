package owntally.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class EventLogTest {
    private val a = key(1)
    private val b = key(2)

    private fun ev(author: PublicKey, counter: Long, vararg seen: Pair<PublicKey, Long>, type: String = "x"): Event {
        val after = seen.toMap().toMutableMap()
        if (counter > 1) after[author] = counter - 1
        return Event(1, author, counter, after, 0, EventBody.Unknown(type))
    }

    @Test
    fun appendingTheSameEventTwiceChangesNothing() {
        val log = EventLog()
        assertTrue(log.append(ev(a, 1)))
        val before = log.copy()
        assertFalse(log.append(ev(a, 1)))
        assertEquals(before, log)
        assertEquals(1, log.allEvents().size)
    }

    @Test
    fun summaryCountsOnlyGapFreePrefixes() {
        val log = logOf(listOf(ev(a, 1), ev(a, 2), ev(a, 4), ev(b, 2)))
        assertEquals(mapOf(a to 2L), log.summary())
        log.append(ev(a, 3))
        log.append(ev(b, 1))
        assertEquals(mapOf(a to 4L, b to 2L), log.summary())
    }

    @Test
    fun missingForReturnsWhatTheOtherSideLacks() {
        val log = logOf(listOf(ev(a, 1), ev(a, 2), ev(a, 3), ev(b, 1)))
        val missing = log.missingFor(mapOf(a to 1L))
        assertEquals(listOf(EventId(a, 2), EventId(a, 3), EventId(b, 1)), missing.map { it.id })
        assertEquals(emptyList(), log.missingFor(log.summary()))
    }

    @Test
    fun anEventWhoseEarlierCountersAreMissingIsHeldBackUntilTheyArrive() {
        val log = logOf(listOf(ev(a, 2), ev(a, 3)))
        assertEquals(emptyList(), log.applicationOrder())
        assertEquals(listOf(EventId(a, 2), EventId(a, 3)), log.heldBack().map { it.id })
        log.append(ev(a, 1))
        assertEquals(listOf(EventId(a, 1), EventId(a, 2), EventId(a, 3)), log.applicationOrder().map { it.id })
        assertEquals(emptyList(), log.heldBack())
    }

    @Test
    fun anEventWaitsForEverythingItSaw() {
        // b#1 saw a#2, which is missing: b#1 waits even though b has no gap.
        val log = logOf(listOf(ev(a, 1), ev(b, 1, a to 2)))
        assertEquals(listOf(EventId(a, 1)), log.applicationOrder().map { it.id })
        log.append(ev(a, 2))
        assertEquals(listOf(EventId(a, 1), EventId(a, 2), EventId(b, 1)), log.applicationOrder().map { it.id })
    }

    @Test
    fun orderPicksTheSmallestReadyEventButNeverBeforeWhatItSaw() {
        // a#2 (smaller key) saw b#1, so it must come after b#1.
        val log = logOf(listOf(ev(a, 1), ev(a, 2, b to 1), ev(b, 1)))
        assertEquals(listOf(EventId(a, 1), EventId(b, 1), EventId(a, 2)), log.applicationOrder().map { it.id })
    }

    @Test
    fun twoDifferentEventsWithTheSameIdAreBothKeptAndFlagged() {
        val log = logOf(listOf(ev(a, 1, type = "one"), ev(a, 1, type = "two"), ev(a, 2)))
        assertEquals(setOf(EventId(a, 1)), log.conflictedIds())
        val order = log.applicationOrder()
        assertEquals(listOf(EventId(a, 1), EventId(a, 2)), order.map { it.id })
        assertTrue(order[0].duplicateId)
        assertFalse(order[1].duplicateId)
        assertEquals(2, log.allEvents().count { it.id == EventId(a, 1) })
    }

    @Test
    fun conflictResultDoesNotDependOnArrivalOrder() {
        val e1 = ev(a, 1, type = "one")
        val e2 = ev(a, 1, type = "two")
        assertEquals(logOf(listOf(e1, e2)), logOf(listOf(e2, e1)))
        assertEquals(logOf(listOf(e1, e2)).applicationOrder(), logOf(listOf(e2, e1)).applicationOrder())
    }

    @Test
    fun mergingInEitherOrderGivesEqualLogs() = runBlocking {
        checkAll(300, Arb.long()) { seed ->
            val rnd = Random(seed)
            val left = randomEvents(rnd).filter { rnd.nextBoolean() }
            val right = randomEvents(Random(seed)).filter { rnd.nextBoolean() }
            val lr = logOf(left).also { it.merge(right) }
            val rl = logOf(right).also { it.merge(left) }
            assertEquals(lr, rl)
            assertEquals(lr.applicationOrder(), rl.applicationOrder())
            assertEquals(lr.summary(), rl.summary())
        }
    }

    @Test
    fun missingForThenMergeMakesTwoLogsEqual() = runBlocking {
        checkAll(300, Arb.long()) { seed ->
            val rnd = Random(seed)
            val all = randomEvents(rnd)
            val one = logOf(all.filter { rnd.nextInt(3) != 0 })
            val two = logOf(all.filter { rnd.nextInt(3) != 0 })
            val toTwo = one.missingFor(two.summary())
            val toOne = two.missingFor(one.summary())
            one.merge(toOne)
            two.merge(toTwo)
            // Anything one side has at or below the other's prefix is already there; the rest was just sent.
            assertEquals(one, two)
            assertEquals(one.summary(), two.summary())
            assertEquals(one.applicationOrder(), two.applicationOrder())
        }
    }

    @Test
    fun arrivalOrderDoesNotChangeTheApplicationOrder() = runBlocking {
        checkAll(300, Arb.long()) { seed ->
            val events = randomEvents(Random(seed))
            val reference = logOf(events).applicationOrder()
            assertEquals(reference, logOf(events.shuffled(Random(seed + 1))).applicationOrder())
        }
    }

    @Test
    fun everyAppliedEventComesAfterItsDependencies() = runBlocking {
        checkAll(300, Arb.long()) { seed ->
            val order = logOf(randomEvents(Random(seed))).applicationOrder()
            val position = order.withIndex().associate { (i, o) -> o.id to i }
            for (o in order) for (dep in o.event.dependencies()) {
                assertTrue(position.getValue(dep) < position.getValue(o.id), "${o.id} before its dependency $dep")
            }
        }
    }
}
