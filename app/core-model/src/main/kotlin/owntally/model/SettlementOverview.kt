package owntally.model

/** A payment the payer recorded and a payment the receiver recorded that look like the same one (M8, decision P3). */
data class DuplicatePayment(val payerEntry: Settlement, val receiverEntry: Settlement)

/**
 * What Settle up lists for a group (M8, decisions P1 to P6), worked out from the state alone, so every phone shows the same.
 * [toMake] is the plan (balances already count every payment that is not disputed, so it shrinks as payments are marked); [waiting] are
 * payments marked by their payer and not yet confirmed; [disputed] were refused by their receiver (they do not count); [confirmed] are
 * done; [duplicates] are pairs that look like one payment recorded twice. Payments with someone who was removed or left are left out
 * (they do not count either). Each list is in the order of the events' ids.
 */
data class SettleOverview(
    val toMake: List<Payment>,
    val waiting: List<Settlement>,
    val disputed: List<Settlement>,
    val confirmed: List<Settlement>,
    val duplicates: List<DuplicatePayment>,
)

fun settleOverview(state: GroupState): SettleOverview {
    val current = state.currentMembers.keys
    val visible = state.settlements.values.filter { it.from in current && it.to in current }.sortedBy { it.id }
    val waiting = visible.filter { it.status == SettlementStatus.PAID_MARKED }
    val confirmed = visible.filter { it.status == SettlementStatus.CONFIRMED }
    return SettleOverview(
        toMake = settlementPlan(balances(state)),
        waiting = waiting,
        disputed = visible.filter { it.status == SettlementStatus.DISPUTED },
        confirmed = confirmed,
        duplicates = duplicatesOf(waiting, confirmed),
    )
}

/**
 * A payer's entry still waiting and a confirmed entry for the same two people and the same amount: the receiver most likely recorded the
 * payment too, before the payer's entry reached them. Each confirmed entry excuses one waiting entry, the earliest first, so every phone
 * finds the same pairs. (A confirmed entry may also be a payment the payer recorded and the receiver confirmed; then it is never one
 * of these, because that one is no longer waiting.)
 */
private fun duplicatesOf(waiting: List<Settlement>, confirmed: List<Settlement>): List<DuplicatePayment> {
    val free = confirmed.toMutableList()
    val pairs = ArrayList<DuplicatePayment>()
    for (w in waiting) {
        val match = free.firstOrNull { it.from == w.from && it.to == w.to && it.amount == w.amount } ?: continue
        free.remove(match)
        pairs += DuplicatePayment(w, match)
    }
    return pairs
}
