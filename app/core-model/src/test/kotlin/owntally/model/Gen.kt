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
            events += Event(1, author, counter.toLong(), after, rnd.nextLong(0, 1_000_000), EventBody.Unknown("t${rnd.nextInt(5)}"))
        }
    }
    return events
}

fun logOf(events: Iterable<Event>) = EventLog().also { it.merge(events) }
