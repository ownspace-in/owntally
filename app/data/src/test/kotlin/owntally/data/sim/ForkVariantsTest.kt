package owntally.data.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import owntally.crypto.EventSealing
import owntally.crypto.OpenResult
import owntally.data.session.AddResult
import owntally.model.Envelope
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.IgnoreReason
import owntally.model.Money
import owntally.model.Split
import owntally.sync.Fingerprint

/**
 * Several envelopes for one event id (S1): the same event sealed twice (another nonce), and a different event under the same id.
 * A phone keeps and reads only the two with the smallest hashes (the same two the sync view holds), so every phone reaches the same state
 * whatever order the envelopes arrive in, even when a copy of the same event pushes the different one out.
 */
class ForkVariantsTest {
    private fun permutations(items: List<ByteArray>): List<List<ByteArray>> =
        if (items.size <= 1) listOf(items) else items.indices.flatMap { i -> permutations(items.filterIndexed { j, _ -> j != i }).map { listOf(items[i]) + it } }

    @Test fun everyOrderOfArrivalGivesTheSameStateAndAtMostTwoEnvelopesPerIdAreRead() {
        repeat(20) { world ->
            val seed = 100L + world
            val owner = SimDevice.owner(seed, "Asha")
            val ben = SimDevice.joined(seed, 1, "Ben", owner)
            owner.env.groups.envelopes(owner.group).envelopes.forEach { ben.env.groups.appendEnvelope(ben.group, it) }
            ben.session.refresh()

            val id = (owner.session.addExpense("Dinner", Money(30_000), owner.key, 20_000, Split.Equal(setOf(owner.key, ben.key))) as AddResult.Added).id
            val ring = owner.env.secrets.loadKeyring(owner.group).keyring
            val first = owner.env.groups.envelopes(owner.group).envelopes.first { it.author == owner.key && it.counter == id.counter }
            val event = (EventSealing.open(first, ring) as OpenResult.Opened).event
            val identity = owner.env.identity.groupIdentity(owner.slot)!!
            val sameAgain = EventSealing.seal(identity, owner.group, first.epoch, ring[first.epoch]!!, event) // same event, another nonce
            val different = EventSealing.seal(
                identity, owner.group, first.epoch, ring[first.epoch]!!,
                Event(event.version, event.author, event.counter, event.after, event.time, (event.body as EventBody.ExpenseAdded).copy(total = Money(300_000))),
            )
            val variants = listOf(first.encode(), sameAgain.encode(), different.encode())
            val base = (owner.env.groups.rawEnvelopes(owner.group) + ben.env.groups.rawEnvelopes(ben.group))
                .filter { b -> variants.none { it.contentEquals(b) } }.distinctBy { it.toList() }

            // What the rules must say: the two smallest versions decide. Two different events among them: both ignored.
            val kept = Fingerprint.smallestTwo(variants).map { (EventSealing.open(Envelope.decode(it), ring) as OpenResult.Opened).event }
            val conflict = kept.size == 2 && kept[0] != kept[1]

            val reference = SimDevice.copyOf(seed, ben, base + variants)
            for (order in permutations(variants)) {
                val receiver = SimDevice.copyOf(seed, ben, base)
                for (v in order) {
                    receiver.env.groups.appendEnvelope(receiver.group, Envelope.decode(v))
                    receiver.session.refresh()
                }
                val state = receiver.session.view.value.state
                assertEquals(reference.session.view.value.state, state, "world $world, order of arrival differs")
                assertEquals(reference.session.view.value.balances, receiver.session.view.value.balances, "world $world")
                assertTrue(receiver.env.groups.envelopes(receiver.group).envelopes.count { it.author == owner.key && it.counter == id.counter } <= 2, "world $world: at most two are read")
            }
            val ignored = reference.session.view.value.state.ignored.any { it.id == id && it.reason == IgnoreReason.DUPLICATE_ID }
            assertEquals(conflict, ignored, "world $world: the event is ignored exactly when the two smallest versions are different events")
        }
    }
}
