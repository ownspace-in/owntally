package owntally.model

data class BeforeAfter(val member: PublicKey, val before: Money, val after: Money)

/** What the admin sees before confirming a removal. */
data class RemovalPreview(
    /** For every remaining member, their balance before and after, in key order. */
    val changes: List<BeforeAfter>,
    /** Expenses that stop counting at all (the removed member paid them, or nobody else took part). */
    val excludedExpenses: List<EventId>,
    /** Expenses that still count but are shared out differently without the removed member. */
    val resplitExpenses: List<EventId>,
    /** Payments that stop counting because they involve the removed member. */
    val excludedSettlements: List<EventId>,
)

/** Shows the effect of removing [member] without changing anything. */
fun previewRemoval(state: GroupState, member: PublicKey): RemovalPreview {
    require(state.currentMembers.containsKey(member)) { "not a current member" }
    val gone = state.members.filterValues { it.removed }.keys
    val before = balances(state)
    val after = balances(state, setOf(member))
    val changes = after.keys.map { BeforeAfter(it, before.getValue(it), after.getValue(it)) }

    val live = state.expenses.values.filter { !it.deleted && sharesFor(it, gone) != null }
    val excluded = live.filter { sharesFor(it, gone + member) == null }
    val resplit = live.filter { it !in excluded && member in it.split.participantKeys }
    val payments = state.settlements.values.filter {
        it.status != SettlementStatus.DISPUTED && (it.from == member || it.to == member) && it.from !in gone && it.to !in gone
    }
    return RemovalPreview(changes, excluded.map { it.id }, resplit.map { it.id }, payments.map { it.id })
}
