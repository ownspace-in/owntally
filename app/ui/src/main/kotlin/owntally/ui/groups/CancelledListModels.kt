package owntally.ui.groups

import owntally.data.session.GroupView
import owntally.model.CancelledEntry
import owntally.model.EventId
import owntally.model.Role
import owntally.model.PublicKey
import owntally.model.format.MoneyFormat

data class CancelledExpenseRow(val id: EventId, val description: String, val detail: String, val entry: CancelledEntry.Expense)

/** What a removed or departed person wrote after they went, which the rules cancelled (D5b). Owner only. */
data class CancelledListModel(val name: String, val heading: String, val expenses: List<CancelledExpenseRow>, val others: List<String>, val empty: String?)

object CancelledListModels {
    /** Null unless this phone is the owner's and [member] is someone who has been removed or has left. */
    fun build(view: GroupView, member: PublicKey): CancelledListModel? {
        val state = view.state
        if (state.members[state.owner]?.takeIf { !it.removed }?.key != view.me || state.members[view.me]?.role != Role.OWNER) return null
        val person = state.members[member]?.takeIf { it.removed } ?: return null
        val name = person.name.ifBlank { "Someone" }
        fun label(k: PublicKey) = if (k == view.me) "You" else state.members[k]?.name?.takeIf { it.isNotBlank() } ?: "Someone"
        val mine = view.cancelled.filter { it.author == member }
        val expenses = mine.filterIsInstance<CancelledEntry.Expense>().map {
            CancelledExpenseRow(it.id, it.description, "${MoneyFormat.format(it.total)} · paid by ${label(it.paidBy)}", it)
        }
        val payments = mine.count { it is CancelledEntry.Payment }
        val other = mine.count { it is CancelledEntry.Other }
        val others = listOfNotNull(
            if (payments > 0) (if (payments == 1) "1 payment" else "$payments payments") else null,
            if (other > 0) (if (other == 1) "1 other event" else "$other other events") else null,
        )
        return CancelledListModel(
            name, "Late entries not counted: ${mine.size}", expenses, others, if (mine.isEmpty()) "Nothing was written after this person went." else null,
        )
    }
}
