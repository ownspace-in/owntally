package owntally.model

import kotlin.random.Random

/** Test helper: a random set of events from several authors with random "seen" relations. Bodies carry no meaning. */
fun randomEvents(rnd: Random, authors: Int = 4, maxPerAuthor: Int = 6): List<Event> {
    val counts = (1..authors).associate { key(it) to rnd.nextInt(0, maxPerAuthor + 1) }
    val events = mutableListOf<Event>()
    for ((author, n) in counts) {
        for (counter in 1..n) {
            val after = mutableMapOf<PublicKey, Long>()
            if (counter > 1) after[author] = counter - 1L
            for ((other, m) in counts) {
                if (other != author && m > 0 && rnd.nextInt(3) == 0) after[other] = rnd.nextInt(1, m + 1).toLong()
            }
            events += Event(1, author, counter.toLong(), after, rnd.nextLong(0, 1_000_000), EventBody.Unknown(200 + rnd.nextInt(5), ByteArray(0)))
        }
    }
    return events
}

fun logOf(events: Iterable<Event>) = EventLog().also { it.merge(events) }

private val NAMES = listOf("Asha", "Ben", "Chitra", "अशोक", "Dev ☃", "Eve")

/** Test helper: a random well-formed event of any body type, including unknown ones. */
fun randomAnyEvent(rnd: Random): Event {
    val keys = (1..6).map { key(it) }
    fun k() = keys.random(rnd)
    fun enc() = EncryptionKey(ByteArray(32) { rnd.nextInt(256).toByte() })
    fun name() = NAMES.random(rnd)
    fun phone() = listOf("98765 43210", "+91 98765 00002", "12345").random(rnd)
    fun sealedMap() = keys.shuffled(rnd).take(rnd.nextInt(0, 7)).associateWith { SealedKey(ByteArray(rnd.nextInt(1, 100)) { rnd.nextInt(256).toByte() }) }
    fun id() = EventId(k(), rnd.nextLong(1, 1_000_000_000_000L))
    val body: EventBody = when (rnd.nextInt(17)) {
        0 -> EventBody.GroupCreated(GroupId(ByteArray(16) { rnd.nextInt(256).toByte() }), name(), listOf("INR", "USD", "JPY").random(rnd), enc())
        1 -> EventBody.MemberJoined(k(), enc(), name(), phone())
        2 -> EventBody.ProfileUpdated(name(), phone())
        3 -> {
            val parts = keys.shuffled(rnd).take(rnd.nextInt(1, 7))
            val split = if (rnd.nextBoolean()) Split.Equal(parts.toSet()) else Split.Exact(parts.associateWith { Money(rnd.nextLong(1, 1_000_000_000)) })
            val total = if (split is Split.Exact) split.sum() else Money(rnd.nextLong(1, Money.MAX_AMOUNT.paise))
            EventBody.ExpenseAdded("D${rnd.nextInt()}", total, k(), rnd.nextLong(-5, 40_000), split)
        }
        4 -> EventBody.ExpenseDeleted(id())
        5 -> k().let { from -> EventBody.SettlementRecorded(from, keys.first { it != from }, Money(rnd.nextLong(1, Money.MAX_AMOUNT.paise))) }
        6 -> EventBody.SettlementConfirmed(id())
        7 -> EventBody.PaymentDisputed(id(), DisputeReason.values().random(rnd))
        8 -> EventBody.AdminAdded(k())
        9 -> EventBody.AdminRemoved(k())
        10 -> EventBody.OwnershipOffered(k())
        11 -> EventBody.OwnershipOfferCancelled
        12 -> EventBody.OwnershipAccepted
        13 -> EventBody.MemberRemoved(k(), rnd.nextLong(1, 0xFFFF_FFFFL), sealedMap())
        14 -> EventBody.KeyRotated(rnd.nextLong(1, 0xFFFF_FFFFL), sealedMap())
        else -> EventBody.Unknown(listOf(0, 16, 17, 200, 255).random(rnd), ByteArray(rnd.nextInt(0, 30)) { rnd.nextInt(256).toByte() })
    }
    val author = k()
    val counter = if (rnd.nextBoolean()) rnd.nextLong(1, 20) else rnd.nextLong(1, Long.MAX_VALUE / 2)
    val after = mutableMapOf<PublicKey, Long>()
    if (counter > 1) after[author] = counter - 1
    for (o in keys) if (o != author && rnd.nextBoolean()) after[o] = rnd.nextLong(1, Long.MAX_VALUE / 2)
    return Event(1, author, counter, after, rnd.nextLong(), body)
}
