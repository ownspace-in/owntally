package owntally.model

import kotlin.random.Random

const val PHONE = "98765 43210"

/** Test helper: builds a log the way phones would, tracking each author's counter and what they have seen. */
class Sim {
    val events = mutableListOf<Event>()
    private val last = mutableMapOf<PublicKey, Long>()

    /** [seen] defaults to everything written so far (a linear history). Pass a map to model concurrent writing. */
    fun emit(author: PublicKey, body: EventBody, seen: Map<PublicKey, Long>? = null, version: Int = 1): Event {
        val counter = (last[author] ?: 0L) + 1
        val after = (seen ?: last.toMap()).filterValues { it >= 1 }.toMutableMap()
        if (counter > 1) after[author] = counter - 1 else after.remove(author)
        val event = Event(version, author, counter, after, counter * 1000, body)
        events += event
        last[author] = counter
        return event
    }

    fun snapshot(): Map<PublicKey, Long> = last.toMap()
    fun log() = logOf(events)
    fun state() = replay(log())

    fun join(by: PublicKey, member: PublicKey, name: String) = emit(by, EventBody.MemberJoined(member, name, PHONE))
    fun expense(by: PublicKey, total: Money, paidBy: PublicKey, split: Split, description: String = "Dinner") =
        emit(by, EventBody.ExpenseAdded(description, total, paidBy, 20_000, split))
}

/** Owner key(1) creates a group and admits key(2) Ben, key(3) Chitra and key(4) Dev. */
fun standardGroup(): Sim = Sim().also { s ->
    val a = key(1)
    s.emit(a, EventBody.GroupCreated(GROUP, "Goa Trip", "INR"))
    s.emit(a, EventBody.ProfileUpdated("Asha", PHONE))
    s.join(a, key(2), "Ben")
    s.join(a, key(3), "Chitra")
    s.join(a, key(4), "Dev")
}

fun Event.reason(state: GroupState): IgnoreReason? = state.ignored.firstOrNull { it.id == id }?.reason

/** A random but well-formed history: some actions are invalid on purpose, and some are written concurrently. */
fun randomScenario(rnd: Random, steps: Int = 40): List<Event> {
    val sim = standardGroup()
    val everyone = (1..6).map { key(it) }
    val pool = mutableListOf<EventId>()
    repeat(steps) {
        val author = everyone.random(rnd)
        val seen = sim.snapshot().mapValues { (_, c) -> if (rnd.nextInt(4) == 0) rnd.nextLong(0, c + 1) else c }
        fun pick() = everyone.random(rnd)
        fun amount() = Money(rnd.nextLong(1, 100_000))
        val body: EventBody = when (rnd.nextInt(14)) {
            0, 1, 2, 3 -> {
                val parts = everyone.shuffled(rnd).take(rnd.nextInt(1, 5)).toSet()
                val total = amount()
                if (rnd.nextBoolean()) EventBody.ExpenseAdded("E", total, pick(), 20_000, Split.Equal(parts))
                else {
                    val list = parts.toList()
                    val first = list.dropLast(1).associateWith { Money(rnd.nextLong(1, 1000)) }
                    val rest = total - first.values.fold(Money.ZERO) { x, y -> x + y }
                    if (list.size == 1 || !rest.isPositive()) EventBody.ExpenseAdded("E", total, pick(), 20_000, Split.Equal(parts))
                    else EventBody.ExpenseAdded("E", total, pick(), 20_000, Split.Exact(first + (list.last() to rest)))
                }
            }
            4 -> pool.randomOrNull(rnd)?.let { EventBody.ExpenseDeleted(it) } ?: EventBody.Unknown("noop")
            5, 6 -> {
                val from = pick(); var to = pick()
                if (to == from) to = everyone.first { it != from }
                EventBody.SettlementRecorded(from, to, amount())
            }
            7 -> pool.randomOrNull(rnd)?.let { EventBody.SettlementConfirmed(it) } ?: EventBody.Unknown("noop")
            8 -> pool.randomOrNull(rnd)?.let { EventBody.PaymentDisputed(it, DisputeReason.NOT_CHECKED) } ?: EventBody.Unknown("noop")
            9 -> if (rnd.nextBoolean()) EventBody.AdminAdded(pick()) else EventBody.AdminRemoved(pick())
            10 -> when (rnd.nextInt(3)) {
                0 -> EventBody.OwnershipOffered(pick())
                1 -> EventBody.OwnershipAccepted
                else -> EventBody.OwnershipOfferCancelled
            }
            11 -> EventBody.MemberRemoved(pick())
            12 -> EventBody.MemberJoined(pick(), "New", PHONE)
            else -> EventBody.ProfileUpdated("P${rnd.nextInt(9)}", PHONE)
        }
        val e = sim.emit(author, body, seen)
        if (body is EventBody.ExpenseAdded || body is EventBody.SettlementRecorded) pool += e.id
    }
    return sim.events
}
