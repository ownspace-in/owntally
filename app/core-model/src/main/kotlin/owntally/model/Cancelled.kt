package owntally.model

/** A late entry of a removed member that replay cancelled, described well enough for the owner to see it and re-enter it (M4). */
sealed class CancelledEntry {
    abstract val id: EventId
    abstract val author: PublicKey

    data class Expense(
        override val id: EventId, override val author: PublicKey,
        val description: String, val total: Money, val paidBy: PublicKey, val day: Long, val split: Split,
    ) : CancelledEntry()

    data class Payment(override val id: EventId, override val author: PublicKey, val from: PublicKey, val to: PublicKey, val amount: Money) : CancelledEntry()

    /** Anything else (a profile change, a deletion, and so on): only its kind is shown. */
    data class Other(override val id: EventId, override val author: PublicKey, val kind: String) : CancelledEntry()
}

/**
 * The events a removed or departed member wrote after the cut-off in their removal, which replay cancelled for good
 * (`AFTER_REMOVAL`; clarification P5), in event order. Group them by [CancelledEntry.author] to show them per person. The list can
 * grow after the removal, when a late event reaches this phone.
 */
fun cancelledEntries(log: EventLog, state: GroupState): List<CancelledEntry> {
    val cancelled = state.ignored.filter { it.reason == IgnoreReason.AFTER_REMOVAL }.map { it.id }.toSet()
    if (cancelled.isEmpty()) return emptyList()
    return log.allEvents().filter { it.id in cancelled }.map { e ->
        when (val body = e.body) {
            is EventBody.ExpenseAdded -> CancelledEntry.Expense(e.id, e.author, body.description, body.total, body.paidBy, body.day, body.split)
            is EventBody.SettlementRecorded -> CancelledEntry.Payment(e.id, e.author, body.from, body.to, body.amount)
            else -> CancelledEntry.Other(e.id, e.author, body::class.simpleName ?: "Event")
        }
    }
}
