package owntally.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Loads the JSON fixtures in src/test/resources/fixtures, replays them and checks the numbers worked out by hand. */
class FixturesTest {
    private class Fixture(val json: JsonObject) {
        val keys: Map<String, PublicKey> = json.getValue("keys").jsonObject.mapValues { PublicKey.fromHex(it.value.jsonPrimitive.content) }
        val names: Map<PublicKey, String> = keys.entries.associate { it.value to it.key }
        val expect: JsonObject = json.getValue("expect").jsonObject

        private fun key(name: String) = keys.getValue(name)
        private fun id(e: JsonElement) = e.jsonArray.let { EventId(key(it[0].jsonPrimitive.content), it[1].jsonPrimitive.long) }
        private fun money(e: JsonElement) = Money(e.jsonPrimitive.long)

        private fun split(o: JsonObject): Split = when (val type = o.getValue("type").jsonPrimitive.content) {
            "Equal" -> Split.Equal(o.getValue("participants").jsonArray.map { key(it.jsonPrimitive.content) }.toSet())
            "Exact" -> Split.Exact(o.getValue("amounts").jsonObject.entries.associate { key(it.key) to money(it.value) })
            else -> error("unknown split $type")
        }

        private fun body(o: JsonObject): EventBody {
            fun s(f: String) = o.getValue(f).jsonPrimitive.content
            fun k(f: String) = key(s(f))
            return when (val type = s("type")) {
                "GroupCreated" -> EventBody.GroupCreated(GroupId(hex(s("groupId"))), s("name"), s("currency"))
                "ProfileUpdated" -> EventBody.ProfileUpdated(s("name"), s("phone"))
                "MemberJoined" -> EventBody.MemberJoined(k("member"), s("name"), s("phone"))
                "ExpenseAdded" -> EventBody.ExpenseAdded(s("description"), money(o.getValue("total")), k("paidBy"), o.getValue("day").jsonPrimitive.long, split(o.getValue("split").jsonObject))
                "ExpenseDeleted" -> EventBody.ExpenseDeleted(id(o.getValue("expense")))
                "SettlementRecorded" -> EventBody.SettlementRecorded(k("from"), k("to"), money(o.getValue("amount")))
                "SettlementConfirmed" -> EventBody.SettlementConfirmed(id(o.getValue("settlement")))
                "PaymentDisputed" -> EventBody.PaymentDisputed(id(o.getValue("settlement")), DisputeReason.valueOf(s("reason")))
                "AdminAdded" -> EventBody.AdminAdded(k("member"))
                "AdminRemoved" -> EventBody.AdminRemoved(k("member"))
                "OwnershipOffered" -> EventBody.OwnershipOffered(k("to"))
                "OwnershipOfferCancelled" -> EventBody.OwnershipOfferCancelled
                "OwnershipAccepted" -> EventBody.OwnershipAccepted
                "MemberRemoved" -> EventBody.MemberRemoved(k("member"))
                "Unknown" -> EventBody.Unknown(s("name"))
                else -> error("unknown body $type")
            }
        }

        private fun hex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        val events: List<Event> = json.getValue("events").jsonArray.map { e ->
            val o = e.jsonObject
            Event(
                o.getValue("version").jsonPrimitive.int,
                key(o.getValue("author").jsonPrimitive.content),
                o.getValue("counter").jsonPrimitive.long,
                o.getValue("after").jsonObject.entries.associate { key(it.key) to it.value.jsonPrimitive.long },
                o.getValue("time").jsonPrimitive.long,
                body(o.getValue("body").jsonObject),
            )
        }

        fun balances(name: String): Map<String, Long> = expect.getValue(name).jsonObject.mapValues { it.value.jsonPrimitive.long }
        fun strings(name: String): List<String> = expect.getValue(name).jsonArray.map { it.jsonPrimitive.content }
        fun name(k: PublicKey) = names.getValue(k)
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
