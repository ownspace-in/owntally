package owntally.model

import java.util.TreeSet

/** An event in application order. [duplicateId] is true when different events claim the same id (both are ignored). */
data class Ordered(val id: EventId, val event: Event, val duplicateId: Boolean)

/**
 * The set of events a phone holds for one group. Merge is a union, so arrival order never matters.
 * Not thread safe: the storage layer owns it.
 */
class EventLog {
    private val byId = HashMap<EventId, MutableSet<Event>>()

    /** Returns true if the event was new. */
    fun append(event: Event): Boolean = byId.getOrPut(event.id) { HashSet() }.add(event)

    fun merge(events: Iterable<Event>) {
        for (e in events) append(e)
    }

    fun copy() = EventLog().also { copy -> allEvents().forEach { copy.append(it) } }

    /** All events, in a fixed order (by id, then content). */
    fun allEvents(): List<Event> = byId.keys.sorted().flatMap { id -> byId.getValue(id).sortedWith(CONTENT_ORDER) }

    /** Different events that claim the same id. */
    fun conflictedIds(): Set<EventId> = byId.filterValues { it.size > 1 }.keys

    /** For each author, the highest counter such that all counters from 1 up to it are present. */
    fun summary(): Map<PublicKey, Long> {
        val counters = HashMap<PublicKey, TreeSet<Long>>()
        for (id in byId.keys) counters.getOrPut(id.author) { TreeSet() }.add(id.counter)
        val result = HashMap<PublicKey, Long>()
        for ((author, set) in counters) {
            var next = 1L
            while (set.contains(next)) next++
            if (next > 1) result[author] = next - 1
        }
        return result
    }

    /** The events a peer with [other] as its summary lacks: everything above its prefix for each author. */
    fun missingFor(other: Map<PublicKey, Long>): List<Event> =
        allEvents().filter { it.counter > (other[it.author] ?: 0L) }

    /**
     * The events that can be applied, in the deterministic order of PROTOCOL.md section 3: repeatedly take the ready
     * event with the smallest id, where ready means everything it saw has been applied.
     */
    fun applicationOrder(): List<Ordered> {
        val nodes = HashMap<EventId, Ordered>()
        val pending = HashMap<EventId, Int>()
        val dependents = HashMap<EventId, MutableList<EventId>>()
        val blocked = HashSet<EventId>()
        for ((id, variants) in byId) {
            val duplicate = variants.size > 1
            val event = variants.sortedWith(CONTENT_ORDER).first()
            nodes[id] = Ordered(id, event, duplicate)
            // Conflicted ids have no effect, so they do not wait for anything.
            val deps = if (duplicate) emptySet() else event.dependencies()
            if (deps.any { it !in byId }) blocked += id
            pending[id] = deps.size
            for (d in deps) dependents.getOrPut(d) { ArrayList() }.add(id)
        }
        val ready = TreeSet<EventId>()
        for ((id, count) in pending) if (count == 0 && id !in blocked) ready += id
        val order = ArrayList<Ordered>()
        while (ready.isNotEmpty()) {
            val id = ready.pollFirst()!!
            order += nodes.getValue(id)
            for (next in dependents[id].orEmpty()) {
                val left = pending.getValue(next) - 1
                pending[next] = left
                if (left == 0 && next !in blocked) ready += next
            }
        }
        return order
    }

    /** Events that cannot be applied yet because something they depend on is missing. */
    fun heldBack(): List<Event> {
        val applied = applicationOrder().map { it.id }.toSet()
        return allEvents().filter { it.id !in applied }
    }

    override fun equals(other: Any?) = other is EventLog && byId == other.byId
    override fun hashCode() = byId.hashCode()

    private companion object {
        val CONTENT_ORDER: Comparator<Event> = compareBy<Event>({ it.time }, { it.version }, { it.after.entries.sortedBy { e -> e.key }.toString() }, { it.body.toString() })
    }
}
