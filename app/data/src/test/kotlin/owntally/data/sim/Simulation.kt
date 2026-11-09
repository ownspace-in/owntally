package owntally.data.sim

import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import owntally.model.Envelope
import owntally.model.Money
import owntally.model.Split
import owntally.sync.GroupRoom
import owntally.sync.Link
import owntally.sync.SyncEngine
import owntally.sync.SyncOutcome
import owntally.sync.SyncResult
import owntally.sync.SyncRole

/** What one scenario did, for the failure message and the totals. */
class SimReport(
    val seed: Long,
    val devices: Int,
    val faults: SimFaults,
    val operations: Int,
    val syncs: Int,
    val outcomes: Map<SyncOutcome, Int>,
    val messagesLost: Int,
    val linksBroken: Int,
    val settleRounds: Int,
    val envelopes: Int,
    /** How many times a cheating phone handed a different version of one of its events to another phone. */
    val forks: Int,
    /** How often the owner's automatic key change was refused by the rules, by reason. */
    val dutyRefusals: Map<String, Int>,
    /** The most automatic key changes any one phone made in this scenario. */
    val maxRotations: Int,
    val log: List<String>,
)

class SimFailure(message: String, val report: SimReport?) : AssertionError(message)

/**
 * One random scenario (T5.4): 3 to 5 phones share a group; for a while each phone writes entries and changes membership, goes offline
 * and online, and phones try to sync over a network that delays, loses, repeats and cuts. Then the network is made reliable and the phones
 * sync until nothing changes, and everything must agree: the same envelopes everywhere, and the same state and balances on every phone
 * that is a member. The seed decides every random choice, so a failing seed can be run again (the keys and nonces are random, the
 * sequence of operations and faults is the same).
 */
class Simulation(
    private val seed: Long,
    private val steps: Int = 70,
    /** M8: phones also pay, receive, confirm, dispute, record the same payment twice and cancel the duplicate. */
    private val payments: Boolean = false,
) {
    private val random = Random(seed)
    private val startedAt = System.nanoTime()
    private val log = ArrayList<String>()
    private val outcomes = java.util.EnumMap<SyncOutcome, Int>(SyncOutcome::class.java)
    private var syncs = 0
    private var operations = 0
    private var nameCounter = 0
    private var forks = 0
    private var maxRotations = 0
    private val dutyRefusals = java.util.TreeMap<String, Int>()
    private lateinit var devices: List<SimDevice>
    private lateinit var network: SimNetwork

    private fun note(text: String) { log += text }

    suspend fun run(scope: CoroutineScope): SimReport {
        network = SimNetwork(scope, random)
        val count = random.nextInt(3, 6)
        val faults = SimFaults(
            drop = random.nextDouble(0.0, 0.08), duplicate = random.nextDouble(0.0, 0.08),
            maxDelayMs = random.nextLong(5, 1_500), breakPerMessage = random.nextDouble(0.0, 0.03),
        )
        val names = listOf("Asha", "Ben", "Chitra", "Dev", "Eve")
        val owner = SimDevice.owner(seed, names[0])
        devices = listOf(owner) + (1 until count).map { SimDevice.joined(seed, it, names[it], owner) }
        note("seed $seed: ${devices.size} phones, $faults")
        settle(scope, "setup")

        network.faults = faults
        val running = ArrayList<Job>()
        repeat(steps) { step ->
            if (random.nextInt(100) < 3) fork(devices.random(random))
            if (random.nextInt(100) < 62) operate(devices.random(random)) else {
                val online = devices.filter { it.online }
                if (online.size >= 2) {
                    val a = online.random(random)
                    val b = (online - a).random(random)
                    running += scope.launch { oneSync(a, b) }
                }
            }
            if (random.nextInt(14) == 0) devices.random(random).let { it.online = !it.online; note("step $step: ${it.name} is now ${if (it.online) "online" else "offline"}") }
            delay(random.nextLong(0, 400))
        }
        running.forEach { it.join() }

        network.faults = SimFaults.NONE
        devices.forEach { it.online = true }
        var rounds = settle(scope, "final")
        if (payments) {
            // Every receiver who sees a payment recorded twice cancels the payer's entry with the one call; then the phones agree again, with no
            // duplicate left for any receiver who is still a member (checked below).
            var cancelled = 0
            for (d in devices) for (dup in d.session.view.value.overview.duplicates.filter { it.payerEntry.to == d.key }) {
                val r = d.session.cancelDuplicate(dup.payerEntry.id)
                note("${d.name}: cancelDuplicate ${dup.payerEntry.id} (end) -> ${r.toString().take(60)}")
                if (r is owntally.data.session.AddResult.Added) cancelled++
            }
            if (cancelled > 0) rounds += settle(scope, "after cancelling duplicates")
        }
        check(devices)
        return report(faults, rounds)
    }

    private infix fun <T> Set<T>.xor(other: Set<T>): Set<T> = (this - other) + (other - this)

    private fun report(faults: SimFaults, rounds: Int) = SimReport(
        seed, devices.size, faults, operations, syncs, outcomes.toMap(), network.messagesLost, network.linksBroken, rounds,
        devices[0].syncStore().summary().entries.values.sumOf { it.counter.toInt() }, forks, dutyRefusals.toMap(), maxRotations, log.toList(),
    )

    // ---- what the phones do ---------------------------------------------------------------------------------------------

    private fun operate(d: SimDevice) {
        operations++
        val v = d.session.view.value
        val state = v.state
        val members = state.currentMembers.keys.toList()
        val roll = random.nextInt(100)
        val what: String
        val result: Any = when {
            members.isEmpty() -> { what = "nothing (no members known)"; Unit }
            roll < 45 -> {
                val payer = members.random(random)
                val sharers = members.shuffled(random).take(random.nextInt(1, members.size + 1)).toSet()
                val total = Money(random.nextLong(1, 5_000) * 100)
                what = "expense ${total.paise} by ${state.members[payer]?.name} among ${sharers.size}"
                d.session.addExpense("e${operations}", total, payer, 20_000L + random.nextInt(0, 5), Split.Equal(sharers))
            }
            roll < 55 -> {
                val target = state.expenses.values.filter { !it.deleted }.randomOrNull(random)
                what = "delete ${target?.description}"
                if (target == null) Unit else d.session.deleteExpense(target.id)
            }
            roll < 59 -> { val m = members.random(random); what = "makeAdmin ${state.members[m]?.name}"; d.session.makeAdmin(m) }
            roll < 61 -> { val m = members.random(random); what = "removeAdmin ${state.members[m]?.name}"; d.session.removeAdmin(m) }
            roll < 64 -> { val m = members.random(random); what = "offerOwnership ${state.members[m]?.name}"; d.session.offerOwnership(m) }
            roll < 66 -> { what = "cancelOffer"; d.session.cancelOffer() }
            roll < 69 -> { what = "acceptOwnership"; d.session.acceptOwnership() }
            roll < 71 -> { what = "declineOwnership"; d.session.declineOwnership() }
            roll < 74 -> { val m = members.random(random); what = "removeMember ${state.members[m]?.name}"; d.session.removeMember(m) }
            roll < 77 -> { what = "leave"; d.session.leave() }
            roll < 79 -> { what = "rotateKey"; d.session.rotateKey() }
            payments && roll < 83 -> {
                val to = (members - d.key).randomOrNull(random)
                // Often the amount the plan asks, sometimes a part of it, sometimes more (decision P5).
                val planned = v.overview.toMake.firstOrNull { it.from == d.key }?.amount ?: Money(random.nextLong(1, 3_000) * 100)
                val amount = when (random.nextInt(4)) { 0 -> Money(maxOf(1L, planned.paise / 2)); 1 -> Money(planned.paise + random.nextLong(1, 20_000)); else -> planned }
                what = "markPaid ${amount.paise} to ${to?.let { state.members[it]?.name }}"
                if (to == null) Unit else d.session.markPaid(to, amount)
            }
            payments && roll < 86 -> {
                val from = (members - d.key).randomOrNull(random)
                val amount = v.overview.toMake.firstOrNull { it.to == d.key && it.from == from }?.amount ?: Money(random.nextLong(1, 3_000) * 100)
                what = "markReceived ${amount.paise} from ${from?.let { state.members[it]?.name }}"
                if (from == null) Unit else d.session.markReceived(from, amount)
            }
            payments && roll < 88 -> {
                val target = v.overview.waiting.filter { it.to == d.key }.randomOrNull(random) ?: v.overview.disputed.filter { it.to == d.key }.randomOrNull(random)
                what = "confirm ${target?.id}"
                if (target == null) Unit else d.session.confirm(target.id)
            }
            payments && roll < 90 -> {
                val target = v.overview.waiting.filter { it.to == d.key }.randomOrNull(random)
                val reason = owntally.model.DisputeReason.values().random(random)
                what = "dispute ${target?.id} $reason"
                if (target == null) Unit else d.session.dispute(target.id, reason)
            }
            payments && roll < 93 -> {
                // The receiver saw the money arrive and records the payment before the payer's entry has reached them (the simulation looks at the
                // other phones to find one): a duplicate once the phones sync, on purpose.
                val known = v.overview.waiting.map { it.id }.toSet()
                val target = devices.flatMap { o -> o.session.view.value.overview.waiting }.filter { it.to == d.key && it.id !in known }.randomOrNull(random)
                what = "markReceived again ${target?.id}"
                if (target == null) Unit else d.session.markReceived(target.from, target.amount)
            }
            payments && roll < 96 -> {
                val target = v.overview.duplicates.filter { it.payerEntry.to == d.key }.randomOrNull(random)
                what = "cancelDuplicate ${target?.payerEntry?.id}"
                if (target == null) Unit else d.session.cancelDuplicate(target.payerEntry.id)
            }
            else -> { nameCounter++; what = "updateProfile"; d.session.updateProfile("${d.name} v$nameCounter", "98765 %05d".format(10_000 + nameCounter)) }
        }
        note("${d.name}: $what -> ${result.toString().take(60)}")
    }

    /**
     * A cheating (or broken) phone gives another phone a different version of one of its own events: same author and counter, different
     * content, really signed. Counters alone cannot show it (S1), so this checks that the fingerprints do, and that in the end every phone
     * holds both versions and ignores the event, so everyone still agrees.
     */
    private fun fork(d: SimDevice) {
        val mine = d.env.groups.envelopes(d.group).envelopes.filter { it.author == d.key }
        val target = mine.randomOrNull(random) ?: return
        val ring = d.env.secrets.loadKeyring(d.group).keyring
        val key = ring[target.epoch] ?: return
        val original = (owntally.crypto.EventSealing.open(target, ring) as? owntally.crypto.OpenResult.Opened)?.event ?: return
        val body = when (val b = original.body) {
            is owntally.model.EventBody.ExpenseAdded -> b.copy(description = b.description + "!")
            is owntally.model.EventBody.ProfileUpdated -> b.copy(phone = "98765 99999")
            else -> return
        }
        val second = owntally.model.Event(original.version, original.author, original.counter, original.after, original.time, body)
        val envelope = owntally.crypto.EventSealing.seal(d.env.identity.groupIdentity(d.slot)!!, d.group, target.epoch, key, second)
        val victim = (devices - d).random(random)
        victim.env.groups.appendEnvelope(d.group, envelope)
        victim.session.refresh()
        forks++
        note("${d.name} hands ${victim.name} a different version of ${d.name}#${original.counter}")
    }

    private suspend fun oneSync(a: SimDevice, b: SimDevice): Pair<SyncResult, SyncResult> {
        syncs++
        val seconds = (System.nanoTime() - startedAt) / 1_000_000_000
        if (seconds > 90) {
            val events = devices.maxOf { it.env.groups.rawEnvelopes(it.group).size }
            fail("this scenario is taking more than 90 seconds (after $syncs syncs and $operations operations); the largest log has $events envelopes")
        }
        val (la, lb) = network.link(a.key, b.key)
        val room = GroupRoom.fromGroupId(a.group.bytes())
        val sa = a.syncStore()
        val sb = b.syncStore()
        val (ra, rb) = coroutineScope {
            val x = async { SyncEngine(room, sa, access = owntally.data.session.MembershipAccess(a.session, sa)).run(la, SyncRole.INITIATOR).also { endIfNotDone(it, la) } }
            val y = async { SyncEngine(room, sb, access = owntally.data.session.MembershipAccess(b.session, sb)).run(lb, SyncRole.RESPONDER).also { endIfNotDone(it, lb) } }
            x.await() to y.await()
        }
        outcomes.merge(ra.outcome, 1, Int::plus)
        outcomes.merge(rb.outcome, 1, Int::plus)
        a.session.refresh()
        b.session.refresh()
        for (d in listOf(a, b)) d.session.lastDutyResult?.let { if (it !is owntally.data.session.AddResult.Added) { note("${d.name}: the owner's automatic key change was refused: $it"); dutyRefusals.merge(it.toString().take(60), 1, Int::plus) } }
        note("sync ${a.name}->${b.name}: ${ra.outcome}/${rb.outcome} stored ${ra.stored}/${rb.stored}")
        return ra to rb
    }

    private suspend fun endIfNotDone(r: SyncResult, link: Link) {
        if (r.outcome != SyncOutcome.COMPLETED) link.close()
    }

    /** Reliable syncs between every pair, again and again, until a whole round changes nothing. Returns how many rounds it took. */
    private suspend fun settle(scope: CoroutineScope, label: String): Int {
        delay(60_000) // anything still in flight finishes or times out
        for (round in 1..12) {
            var changed = false
            for (i in devices.indices) for (j in i + 1 until devices.size) {
                val (ra, rb) = oneSync(devices[i], devices[j])
                if (ra.outcome != SyncOutcome.COMPLETED || rb.outcome != SyncOutcome.COMPLETED || ra.stored > 0 || rb.stored > 0) changed = true
            }
            if (!changed) return round
        }
        throw SimFailure("seed $seed: the phones did not settle after 12 reliable rounds ($label)\n${log.takeLast(40).joinToString("\n")}", null)
    }

    // ---- what must be true at the end -----------------------------------------------------------------------------------

    private fun fail(what: String): Nothing = throw SimFailure("seed $seed: $what\n--- last operations ---\n${log.takeLast(400).joinToString("\n")}", null)

    private fun check(devices: List<SimDevice>) {
        fun content(d: SimDevice): Set<String> = d.syncStore().let { s ->
            s.summary().entries.keys.flatMap { s.versionsOf(it) }.map { b -> b.joinToString("") { "%02x".format(it) } }.toSet()
        }
        // 0. Once everything has settled, no owner's phone still wants to change the key, and none changed it again and again.
        for (d in devices) {
            if (d.session.keyChangeStillNeeded()) fail("${d.name} is the owner and still wants to change the key after everything settled")
            if (d.session.dutyRotations > 25) fail("${d.name} changed the key by itself ${d.session.dutyRotations} times in one scenario")
            maxRotations = maxOf(maxRotations, d.session.dutyRotations)
        }
        // 1. Nothing was damaged on any phone.
        for (d in devices) {
            val v = d.session.view.value
            if (v.counts.damaged != 0) fail("${d.name} has ${v.counts.damaged} damaged envelopes")
        }
        // 2. Find the members at the end from a phone that can read the whole log.
        val readers = devices.filter { d -> d.session.view.value.let { it.counts.waitingForKey.isEmpty() && it.counts.unreadable == 0 } }
        val ref = readers.firstOrNull { d -> d.key in d.session.view.value.state.currentMembers }?.session?.view?.value ?: fail("no member can read the whole log")
        val memberKeys = ref.state.currentMembers.keys
        val members = devices.filter { it.key in memberKeys }
        val others = devices - members.toSet()
        // 3. Every member holds the same envelopes, and has the same state, balances and plan. Someone who left or was removed holds only
        //    part of the log (they are sent little or nothing), and nothing that the members lack (what they wrote was handed over).
        val reference = content(members[0])
        for (d in members.drop(1)) {
            val c = content(d)
            if (c != reference) fail("member ${d.name} holds ${c.size} envelopes and ${members[0].name} holds ${reference.size}: ${(c - reference).size} only here, ${(reference - c).size} only there")
            if (d.syncStore().summary() != members[0].syncStore().summary()) fail("the summaries of ${d.name} and ${members[0].name} differ")
        }
        // (Another version of an event the members already hold is allowed: someone who is no longer a member takes no part in comparing
        // fingerprints, so a forged second version handed only to them never reaches the members, and does not need to.)
        val memberIds = reference.map { hex -> Envelope.decode(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }).let { it.author to it.counter } }.toSet()
        for (d in others) {
            val extra = (content(d) - reference).filter { hex ->
                Envelope.decode(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }).let { it.author to it.counter } !in memberIds
            }
            if (extra.isNotEmpty()) fail("${d.name} is no longer a member but holds ${extra.size} envelopes the members lack")
        }
        // 4. Payments (M8): the balances add up to nothing, every payment on the list is between members, and a duplicate cancelled by its receiver
        //    is no longer a duplicate; the same on every member (checked below with the list itself).
        if (ref.balances.values.fold(Money.ZERO) { x, y -> x + y } != Money.ZERO) fail("the balances do not add up to zero")
        for (dup in ref.overview.duplicates) if (dup.payerEntry.to in memberKeys) fail("a payment recorded twice is still on the list after its receiver cancelled it: ${dup.payerEntry.id}")
        for (p in ref.overview.waiting + ref.overview.confirmed + ref.overview.disputed) {
            if (p.from !in memberKeys || p.to !in memberKeys) fail("a payment on the list is with someone who is not a member: ${p.id}")
        }
        for (d in members) {
            val v = d.session.view.value
            if (d !in readers) fail(
                "${d.name} is a member but cannot read all of the log: waiting for epochs ${v.counts.waitingForKey}, unreadable ${v.counts.unreadable}\n" +
                    dump(d) + "\n" + devices.joinToString("\n") { x -> "${x.name} (${x.key.toHex().take(6)}): epoch ${x.session.view.value.state.epoch}, waiting ${x.session.view.value.counts.waitingForKey}" },
            )
            if (v.state != ref.state) {
                val odd = (ref.state.ignored.toSet() xor v.state.ignored.toSet()).map { it.id }
                val detail = odd.joinToString("\n") { id ->
                    devices.joinToString(" ") { x ->
                        val stored = x.env.groups.rawEnvelopes(x.group).map { Envelope.decode(it) }.filter { it.author == id.author && it.counter == id.counter }
                        "${x.name}: stored ${stored.size}, reason ${x.session.view.value.state.ignored.firstOrNull { it.id == id }?.reason}"
                    }
                }
                fail("the state on ${d.name} differs from a reference member: ${diff(ref.state, v.state)}\n$detail")
            }
            if (v.balances != ref.balances) fail("the balances on ${d.name} differ")
            if (v.plan != ref.plan) fail("the settlement plan on ${d.name} differs")
            if (v.overview != ref.overview) fail("the payment list on ${d.name} differs from a reference member's")
        }
    }

    /** The events one phone can open, in the order the rules apply them, with what the rules decided about each. For failure messages. */
    private fun dump(d: SimDevice): String {
        val ring = d.env.secrets.loadKeyring(d.group).keyring
        val log = owntally.model.EventLog()
        for (e in d.env.groups.envelopes(d.group).envelopes) (owntally.crypto.EventSealing.open(e, ring) as? owntally.crypto.OpenResult.Opened)?.let { log.append(it.event) }
        val state = owntally.model.replay(log)
        val names = devices.associate { it.key to it.name }
        val ignored = state.ignored.associate { it.id to it.reason }
        return "${d.name}: epochStarts ${state.epochStarts.mapValues { "${names[it.value.author]}#${it.value.counter}" }}\n" + log.applicationOrder().joinToString("\n") { o ->
            val e = o.event
            "  ${names[e.author]}#${e.counter} after ${e.after.mapKeys { names[it.key] }} ${e.body::class.simpleName} ${ignored[e.id] ?: "ok"}"
        }
    }

    /** Which parts of two states differ, field by field (a printed copy differs in set order even when the states are equal). */
    private fun diff(x: owntally.model.GroupState, y: owntally.model.GroupState): String {
        val parts = ArrayList<String>()
        fun <K, V> mapDiff(name: String, p: Map<K, V>, q: Map<K, V>) {
            val keys = (p.keys + q.keys).filter { p[it] != q[it] }
            if (keys.isNotEmpty()) parts += "$name differs at ${keys.take(3).joinToString { k -> "$k: ${p[k]} | ${q[k]}" }.take(700)}"
        }
        if (x.name != y.name) parts += "name"
        mapDiff("members", x.members, y.members)
        if (x.owner != y.owner) parts += "owner ${x.owner} | ${y.owner}"
        if (x.pendingOffer != y.pendingOffer) parts += "pendingOffer"
        if (x.declinedOffer != y.declinedOffer) parts += "declinedOffer"
        if (x.epoch != y.epoch) parts += "epoch ${x.epoch} | ${y.epoch}"
        mapDiff("epochStarts", x.epochStarts, y.epochStarts)
        mapDiff("expenses", x.expenses, y.expenses)
        mapDiff("settlements", x.settlements, y.settlements)
        mapDiff("removals", x.removals, y.removals)
        if (x.ignored.toSet() != y.ignored.toSet()) parts += "ignored: only left ${(x.ignored.toSet() - y.ignored.toSet()).take(4)}, only right ${(y.ignored.toSet() - x.ignored.toSet()).take(4)}"
        if (x.heldBack != y.heldBack) parts += "heldBack"
        return parts.joinToString("; ").ifEmpty { "(equal field by field; the order of the ignored list differs?)" }
    }
}
