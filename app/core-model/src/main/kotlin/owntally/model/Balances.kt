package owntally.model

/**
 * Splits [total] equally among [participants] (PROTOCOL.md section 5). Everyone pays `total / n`; the `total % n` leftover
 * paise go one each to the participants starting at position `counter mod n` in ascending key order, wrapping around.
 * [counter] is the counter of the event that created the expense, so the starting point moves from expense to expense.
 */
fun equalShares(total: Money, participants: Collection<PublicKey>, counter: Long): Map<PublicKey, Money> {
    val sorted = participants.toSortedSet().toList()
    require(sorted.isNotEmpty()) { "nobody to split among" }
    val n = sorted.size
    val base = Math.floorDiv(total.paise, n.toLong())
    val leftover = Math.floorMod(total.paise, n.toLong()).toInt()
    val start = Math.floorMod(counter, n.toLong()).toInt()
    val extra = (0 until leftover).map { sorted[(start + it) % n] }.toSet()
    return sorted.associateWith { Money(base + if (it in extra) 1 else 0) }
}

/**
 * What each current member is owed (positive) or owes (negative). Always sums to zero.
 * Removed members are taken out as if they had never been in the group (PROTOCOL.md section 5).
 */
fun balances(state: GroupState): Map<PublicKey, Money> = balances(state, emptySet())

/** As [balances], but also treating [alsoRemoved] as removed. Used to preview a removal. */
internal fun balances(state: GroupState, alsoRemoved: Set<PublicKey>): Map<PublicKey, Money> {
    val gone = state.members.filterValues { it.removed }.keys + alsoRemoved
    val result = java.util.TreeMap<PublicKey, Money>()
    for (key in state.members.keys) if (key !in gone) result[key] = Money.ZERO

    fun add(key: PublicKey, amount: Money) {
        result[key] = result.getValue(key) + amount
    }

    for (expense in state.expenses.values) {
        if (expense.deleted) continue
        val shares = sharesFor(expense, gone) ?: continue
        add(expense.paidBy, expense.total)
        for ((member, share) in shares) add(member, -share)
    }
    for (s in state.settlements.values) {
        if (s.status == SettlementStatus.DISPUTED) continue
        if (s.from in gone || s.to in gone) continue
        add(s.from, s.amount)
        add(s.to, -s.amount)
    }
    return result
}

/** The share each remaining participant pays, or null when the expense does not count at all. */
internal fun sharesFor(expense: Expense, gone: Set<PublicKey>): Map<PublicKey, Money>? {
    if (expense.paidBy in gone) return null
    val remaining = expense.split.participantKeys.filter { it !in gone }
    if (remaining.isEmpty()) return null
    val counter = expense.id.counter
    return when (val split = expense.split) {
        is Split.Equal -> equalShares(expense.total, remaining, counter)
        is Split.Exact -> {
            // The removed members' shares are re-split equally among the rest, so the payer is repaid in full.
            val dropped = split.amounts.filterKeys { it in gone }.values.fold(Money.ZERO) { x, y -> x + y }
            val entered = remaining.associateWith { split.amounts.getValue(it) }
            if (dropped == Money.ZERO) entered
            else {
                val extra = equalShares(dropped, remaining, counter)
                entered.mapValues { (k, v) -> v + extra.getValue(k) }
            }
        }
    }
}

/**
 * What each member pays for [expense], counted the same way as the balances (so a removed member's share is divided among the
 * others), or null when the expense does not count at all: it was deleted, or its payer was removed, or nobody who took part
 * is left. The shares always add up to the expense's total.
 */
fun sharesOf(state: GroupState, expense: Expense): Map<PublicKey, Money>? {
    if (expense.deleted) return null
    return sharesFor(expense, state.members.filterValues { it.removed }.keys)
}

/** The total of the expenses that count (see [sharesOf]). */
fun totalSpent(state: GroupState): Money =
    state.expenses.values.filter { sharesOf(state, it) != null }.fold(Money.ZERO) { sum, e -> sum + e.total }
