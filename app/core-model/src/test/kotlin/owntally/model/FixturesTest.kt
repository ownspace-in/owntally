package owntally.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Loads the JSON fixtures in src/test/resources/fixtures, replays them and checks the numbers worked out by hand. */
class FixturesTest {
    private class Fixture(val json: JsonObject) {
        val reader = JsonEvents(json.getValue("keys").jsonObject.mapValues { PublicKey.fromHex(it.value.jsonPrimitive.content) })
        val keys get() = reader.keys
        val expect: JsonObject = json.getValue("expect").jsonObject
        val events: List<Event> = json.getValue("events").jsonArray.map { reader.event(it.jsonObject) }

        fun balances(name: String): Map<String, Long> = expect.getValue(name).jsonObject.mapValues { it.value.jsonPrimitive.long }
        fun strings(name: String): List<String> = expect.getValue(name).jsonArray.map { it.jsonPrimitive.content }
        fun name(k: PublicKey) = reader.names.getValue(k)
    }

    private fun load(name: String): Fixture {
        val text = FixturesTest::class.java.getResource("/fixtures/$name")!!.readText()
        return Fixture(Json.parseToJsonElement(text).jsonObject)
    }

    private fun GroupState.namedBalances(f: Fixture) = balances(this).entries.associate { f.name(it.key) to it.value.paise }

    private fun check(f: Fixture, events: List<Event>) {
        val state = replay(logOf(events))
        assertEquals(f.strings("currentMembers").toSet(), state.currentMembers.keys.map { f.name(it) }.toSet())
        assertEquals(f.strings("removed").toSet(), state.members.filterValues { it.removed }.keys.map { f.name(it) }.toSet())
        assertEquals(f.expect.getValue("owner").jsonPrimitive.content, f.name(state.owner!!))
        assertEquals(f.expect.getValue("epoch").jsonPrimitive.long, state.epoch)
        assertEquals(f.balances("balances"), state.namedBalances(f))
        val plan = settlementPlan(balances(state)).map { Triple(f.name(it.from), f.name(it.to), it.amount.paise) }
        val expectedPlan = f.expect.getValue("plan").jsonArray.map { p -> p.jsonArray.let { Triple(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content, it[2].jsonPrimitive.long) } }
        assertEquals(expectedPlan, plan)
        val ignored = state.ignored.map { Triple(f.name(it.id.author), it.id.counter, it.reason.name) }.toSet()
        val expectedIgnored = f.expect.getValue("ignored").jsonArray.map { i -> i.jsonArray.let { Triple(it[0].jsonPrimitive.content, it[1].jsonPrimitive.long, it[2].jsonPrimitive.content) } }.toSet()
        assertEquals(expectedIgnored, ignored)
        f.expect["roles"]?.jsonObject?.forEach { (name, role) ->
            assertEquals(role.jsonPrimitive.content, state.members.getValue(f.keys.getValue(name)).role.name, name)
        }
    }

    @Test
    fun theTripFixtureGivesTheHandCalculatedBalancesAndPlan() {
        val f = load("trip.json")
        check(f, f.events)
    }

    @Test
    fun theTripBalancesBeforeTheRemovalMatchTheHandCalculation() {
        val f = load("trip.json")
        val n = f.expect.getValue("eventsBeforeRemoval").jsonPrimitive.int
        val before = replay(logOf(f.events.take(n)))
        assertEquals(f.balances("balancesBeforeRemoval"), before.namedBalances(f))
        // The disputed payment and the deleted expense stay in history but do not count.
        assertEquals(4, before.expenses.size)
        assertEquals(3, before.settlements.size)
    }

    @Test
    fun theAuthorizationFixtureIgnoresExactlyTheBrokenEvents() {
        val f = load("authorization.json")
        check(f, f.events)
    }

    @Test
    fun theResultDoesNotDependOnTheOrderTheEventsArrive() {
        for (name in listOf("trip.json", "authorization.json")) {
            val f = load(name)
            check(f, f.events.reversed())
            for (seed in 1L..25L) check(f, f.events.shuffled(Random(seed)))
        }
    }

    @Test
    fun aGapInTheEventsHoldsBackEverythingThatDependsOnThem() {
        val f = load("trip.json")
        // Drop Asha's event 7 (the hotel): everything after it from Asha waits, nothing is invented.
        val without = f.events.filter { !(f.name(it.author) == "asha" && it.counter == 7L) }
        val state = replay(logOf(without))
        assertEquals(true, state.heldBack.isNotEmpty())
        assertEquals(0, state.expenses.values.count { it.description == "Hotel" })
    }
}
