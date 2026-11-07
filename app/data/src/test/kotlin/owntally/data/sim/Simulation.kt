package owntally.data.sim

import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
class Simulation(private val seed: Long, private val steps: Int = 70) {
    private val random = Random(seed)
    private val log = ArrayList<String>()
    private val outcomes = java.util.EnumMap<SyncOutcome, Int>(SyncOutcome::class.java)
    private var syncs = 0
    private var operations = 0
    private var nameCounter = 0
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
        val rounds = settle(scope, "final")
        check(devices)
        return report(faults, rounds)
    }

    private fun report(faults: SimFaults, rounds: Int) = SimReport(
        seed, devices.size, faults, operations, syncs, outcomes.toMap(), network.messagesLost, network.linksBroken, rounds,
        devices[0].syncStore().summary().entries.values.sumOf { it.counter.toInt() }, log.toList(),
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
            else -> { nameCounter++; what = "updateProfile"; d.session.updateProfile("${d.name} v$nameCounter", "98765 %05d".format(10_000 + nameCounter)) }
        }
        note("${d.name}: $what -> ${result.toString().take(60)}")
    }

    private suspend fun oneSync(a: SimDevice, b: SimDevice): Pair<SyncResult, SyncResult> {
        syncs++
        val (la, lb) = network.link(a.key, b.key)
        val room = GroupRoom.fromGroupId(a.group.bytes())
        val sa = a.syncStore()
        val sb = b.syncStore()
        val (ra, rb) = coroutineScope {
            val x = async { SyncEngine(room, sa).run(la, SyncRole.INITIATOR).also { endIfNotDone(it, la) } }
            val y = async { SyncEngine(room, sb).run(lb, SyncRole.RESPONDER).also { endIfNotDone(it, lb) } }
            x.await() to y.await()
        }
        outcomes.merge(ra.outcome, 1, Int::plus)
        outcomes.merge(rb.outcome, 1, Int::plus)
        a.session.refresh()
        b.session.refresh()
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
        // 1. Every phone holds the same envelopes.
        fun content(d: SimDevice): Set<String> = d.syncStore().let { s ->
            s.summary().entries.keys.flatMap { s.versionsOf(it) }.map { b -> b.joinToString("") { "%02x".format(it) } }.toSet()
        }
        val reference = content(devices[0])
        for (d in devices.drop(1)) {
            val c = content(d)
            if (c != reference) fail("${d.name} holds ${c.size} envelopes and ${devices[0].name} holds ${reference.size}: ${(c - reference).size} only here, ${(reference - c).size} only there")
            if (d.syncStore().summary() != devices[0].syncStore().summary()) fail("the summaries of ${d.name} and ${devices[0].name} differ")
        }
        // 2. Nothing was lost or damaged on any phone.
        for (d in devices) {
            val v = d.session.view.value
            if (v.counts.damaged != 0) fail("${d.name} has ${v.counts.damaged} damaged envelopes")
        }
        // 3. Every phone that can read the whole log computes the same state, balances and plan.
        val readers = devices.filter { d -> d.session.view.value.let { it.counts.waitingForKey.isEmpty() && it.counts.unreadable == 0 } }
        if (readers.isEmpty()) fail("no phone can read the whole log")
        val ref = readers[0].session.view.value
        for (d in readers.drop(1)) {
            val v = d.session.view.value
            if (v.state != ref.state) fail("the state on ${d.name} differs from ${readers[0].name}: ${diff(ref.state.toString(), v.state.toString())}")
            if (v.balances != ref.balances) fail("the balances on ${d.name} differ from ${readers[0].name}")
            if (v.plan != ref.plan) fail("the settlement plan on ${d.name} differs from ${readers[0].name}")
        }
        // 4. Everyone who is a member at the end can read the whole log (they got every key that was sent to them).
        for (m in ref.state.currentMembers.keys) {
            val d = devices.first { it.key == m }
            if (d !in readers) fail(
                "${d.name} is a member but cannot read all of the log: waiting for epochs ${d.session.view.value.counts.waitingForKey}, unreadable ${d.session.view.value.counts.unreadable}\n" +
                    dump(d) + "\n" + devices.joinToString("\n") { x -> "${x.name} (${x.key.toHex().take(6)}): epoch ${x.session.view.value.state.epoch}, ignored ${x.session.view.value.state.ignored.filter { it.reason != owntally.model.IgnoreReason.AFTER_REMOVAL }}, waiting ${x.session.view.value.counts.waitingForKey}" },
            )
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

    private fun diff(a: String, b: String): String {
        val i = a.zip(b).indexOfFirst { it.first != it.second }.let { if (it < 0) minOf(a.length, b.length) else it }
        return "first difference at $i: ...${a.drop(maxOf(0, i - 40)).take(120)} | ...${b.drop(maxOf(0, i - 40)).take(120)}"
    }
}
