package owntally.model

data class Payment(val from: PublicKey, val to: PublicKey, val amount: Money)

/**
 * The suggested payments that clear every balance (PROTOCOL.md section 6). Repeatedly the member owed most is matched with
 * the member who owes most; ties go to the smaller key. At most (members with a balance) minus 1 payments; not always the
 * absolute minimum. The same balances always give the same list.
 */
fun settlementPlan(balances: Map<PublicKey, Money>): List<Payment> {
    require(balances.values.fold(Money.ZERO) { x, y -> x + y } == Money.ZERO) { "balances must sum to zero" }
    val left = java.util.TreeMap<PublicKey, Money>()
    for ((k, v) in balances) if (v != Money.ZERO) left[k] = v
    val plan = ArrayList<Payment>()
    while (left.isNotEmpty()) {
        // maxByOrNull/minByOrNull keep the first of equal values, and the map iterates in key order.
        val creditor = left.entries.maxByOrNull { it.value }!!
        val debtor = left.entries.minByOrNull { it.value }!!
        val amount = minOf(creditor.value, -debtor.value)
        plan += Payment(debtor.key, creditor.key, amount)
        val creditorKey = creditor.key
        val debtorKey = debtor.key
        val newCredit = left.getValue(creditorKey) - amount
        val newDebt = left.getValue(debtorKey) + amount
        if (newCredit == Money.ZERO) left.remove(creditorKey) else left[creditorKey] = newCredit
        if (newDebt == Money.ZERO) left.remove(debtorKey) else left[debtorKey] = newDebt
    }
    return plan
}
