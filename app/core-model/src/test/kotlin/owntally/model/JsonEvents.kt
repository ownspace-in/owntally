package owntally.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

fun hexToBytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** Reads the events written in the JSON fixtures (names such as "asha" stand for 32-byte keys listed under "keys"). */
class JsonEvents(val keys: Map<String, PublicKey>) {
    val names: Map<PublicKey, String> = keys.entries.associate { it.value to it.key }

    private fun key(name: String) = keys.getValue(name)
    private fun id(e: JsonElement) = e.jsonArray.let { EventId(key(it[0].jsonPrimitive.content), it[1].jsonPrimitive.long) }
    private fun money(e: JsonElement) = Money(e.jsonPrimitive.long)

    private fun split(o: JsonObject): Split = when (val type = o.getValue("type").jsonPrimitive.content) {
        "Equal" -> Split.Equal(o.getValue("participants").jsonArray.map { key(it.jsonPrimitive.content) }.toSet())
        "Exact" -> Split.Exact(o.getValue("amounts").jsonObject.entries.associate { key(it.key) to money(it.value) })
        else -> error("unknown split $type")
    }

    private fun sealedKeys(o: JsonObject) = o.getValue("sealedKeys").jsonObject.entries.associate { key(it.key) to SealedKey(hexToBytes(it.value.jsonPrimitive.content)) }

    fun body(o: JsonObject): EventBody {
        fun s(f: String) = o.getValue(f).jsonPrimitive.content
        fun k(f: String) = key(s(f))
        fun enc() = EncryptionKey.fromHex(s("encryptionKey"))
        return when (val type = s("type")) {
            "GroupCreated" -> EventBody.GroupCreated(GroupId(hexToBytes(s("groupId"))), s("name"), s("currency"), enc())
            "ProfileUpdated" -> EventBody.ProfileUpdated(s("name"), s("phone"))
            "MemberJoined" -> EventBody.MemberJoined(k("member"), enc(), s("name"), s("phone"))
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
            "MemberRemoved" -> EventBody.MemberRemoved(k("member"), o.getValue("newEpoch").jsonPrimitive.long, sealedKeys(o))
            "KeyRotated" -> EventBody.KeyRotated(o.getValue("newEpoch").jsonPrimitive.long, sealedKeys(o))
            "Unknown" -> EventBody.Unknown(o.getValue("tag").jsonPrimitive.int, hexToBytes(s("content")))
            else -> error("unknown body $type")
        }
    }

    fun event(o: JsonObject) = Event(
        o.getValue("version").jsonPrimitive.int,
        key(o.getValue("author").jsonPrimitive.content),
        o.getValue("counter").jsonPrimitive.long,
        o.getValue("after").jsonObject.entries.associate { key(it.key) to it.value.jsonPrimitive.long },
        o.getValue("time").jsonPrimitive.long,
        body(o.getValue("body").jsonObject),
    )
}
