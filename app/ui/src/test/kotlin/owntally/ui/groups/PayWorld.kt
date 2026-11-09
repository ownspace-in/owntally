package owntally.ui.groups

import owntally.data.session.Counts
import owntally.data.session.GroupView
import owntally.model.DisputeReason
import owntally.model.EncryptionKey
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.EventId
import owntally.model.EventLog
import owntally.model.Money
import owntally.model.PublicKey
import owntally.model.Split
import owntally.model.balances
import owntally.model.replay
import owntally.model.settleOverview
import owntally.model.settlementPlan

/**
 * Test helper for the settling screens (M8): a linear history written by several people, seen from any one of them. Asha owns the group;
 * Rohan, Meera and Kabir are members. (The session tests of the data module cover several real phones; these tests are about what is shown.)
 */
class PayWorld {
    val asha = key(1); val rohan = key(2); val meera = key(3); val kabir = key(4)
    private val events = mutableListOf<Event>()
    private val last = mutableMapOf<PublicKey, Long>()

    private fun key(n: Int) = PublicKey(ByteArray(32).also { it[0] = n.toByte() })
    private fun enc(n: Int) = EncryptionKey(ByteArray(32) { n.toByte() })

    fun emit(author: PublicKey, body: EventBody, seen: Map<PublicKey, Long>? = null): EventId {
        val counter = (last[author] ?: 0L) + 1
        val after = (seen ?: last.toMap()).toMutableMap()
        if (counter > 1) after[author] = counter - 1 else after.remove(author)
        events += Event(1, author, counter, after, counter * 1000, body)
        last[author] = counter
        return EventId(author, counter)
    }

    fun snapshot(): Map<PublicKey, Long> = last.toMap()

    init {
        emit(asha, EventBody.GroupCreated(owntally.model.GroupIds.create(asha, ByteArray(8) { 9 }), "Goa Trip", "INR", enc(1)))
        emit(asha, EventBody.ProfileUpdated("Asha", "98765 00000"))
        emit(asha, EventBody.MemberJoined(rohan, enc(2), "Rohan", "98765 43210"))
        emit(asha, EventBody.MemberJoined(meera, enc(3), "Meera", "98765 00002"))
        emit(asha, EventBody.MemberJoined(kabir, enc(4), "Kabir", "98765 00003"))
    }

    fun rupees(n: Long) = Money(n * 100)

    /** Asha paid for everyone: each of the other three owes her a quarter. */
    fun dinner(total: Long = 4_000) = emit(asha, EventBody.ExpenseAdded("Dinner", rupees(total), asha, 20_000, Split.Equal(setOf(asha, rohan, meera, kabir))))
    fun markPaid(from: PublicKey, to: PublicKey, rupees: Long) = emit(from, EventBody.SettlementRecorded(from, to, rupees(rupees)))
    fun markReceived(from: PublicKey, to: PublicKey, rupees: Long, seen: Map<PublicKey, Long>? = null) = emit(to, EventBody.SettlementRecorded(from, to, rupees(rupees)), seen)
    fun confirm(by: PublicKey, id: EventId) = emit(by, EventBody.SettlementConfirmed(id))
    fun dispute(by: PublicKey, id: EventId, reason: DisputeReason) = emit(by, EventBody.PaymentDisputed(id, reason))

    fun viewFor(me: PublicKey): GroupView {
        val log = EventLog().also { it.merge(events) }
        val s = replay(log)
        val b = balances(s)
        return GroupView(state = s, me = me, balances = b, plan = settlementPlan(b), overview = settleOverview(s), counts = Counts(events.size, emptySet(), 0, 0), unreadableKeyEpochs = emptySet())
    }
}
