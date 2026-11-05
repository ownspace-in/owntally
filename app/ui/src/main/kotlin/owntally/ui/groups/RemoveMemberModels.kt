package owntally.ui.groups

import owntally.data.session.GroupView
import owntally.model.Permissions
import owntally.model.PublicKey
import owntally.model.balances
import owntally.model.format.BalanceKind
import owntally.model.format.MoneyFormat
import owntally.model.previewRemoval

data class RemoveRow(val key: PublicKey, val label: String, val now: String, val nowKind: BalanceKind, val after: String, val afterKind: BalanceKind?)

data class LeftOutBox(val summary: String, val payments: List<String>)

/** The "Remove Kabir?" screen (design/13). The table comes from the core's [previewRemoval], so it cannot differ from what removal does. */
data class RemoveMemberModel(val title: String, val intro: List<String>, val rows: List<RemoveRow>, val alsoLeftOut: LeftOutBox?, val button: String)

object RemoveMemberModels {
    private fun kind(m: owntally.model.Money) = MoneyFormat.describe(m).kind

    /** Null when this phone may not remove that person (not the owner, or themselves) or they are not in the group. The words never guess a pronoun. */
    fun build(view: GroupView, target: PublicKey): RemoveMemberModel? {
        val state = view.state
        if (target == view.me || !Permissions.removeOthers(Permissions.roleOf(state, view.me))) return null
        val member = state.currentMembers[target] ?: return null
        val name = member.name.ifBlank { "Someone" }
        fun label(k: PublicKey) = if (k == view.me) "You" else state.members[k]?.name?.takeIf { it.isNotBlank() } ?: "Someone"

        val preview = previewRemoval(state, target)
        val rows = preview.changes.sortedWith(compareBy({ it.member != view.me }, { label(it.member).lowercase() })).map {
            RemoveRow(it.member, label(it.member), MoneyFormat.signed(it.before), kind(it.before), MoneyFormat.signed(it.after), kind(it.after))
        } + balances(state).getValue(target).let { RemoveRow(target, name, MoneyFormat.signed(it), kind(it), "Removed", null) }

        val paidByThem = preview.excludedExpenses.count { state.expenses.getValue(it).paidBy == target }
        val onlyThem = preview.excludedExpenses.size - paidByThem
        val parts = buildList {
            if (paidByThem > 0) add("${plural(paidByThem, "expense")} $name paid")
            if (onlyThem > 0) add("${plural(onlyThem, "expense")} only $name took part in")
            if (preview.resplitExpenses.isNotEmpty()) add("$name's share of ${preview.resplitExpenses.size} other${if (preview.resplitExpenses.size == 1) "" else "s"}")
        }
        val payments = preview.excludedSettlements.map { id ->
            val p = state.settlements.getValue(id)
            val line = "${label(p.from)} paid ${label(p.to)} ${MoneyFormat.format(p.amount)}."
            if (p.from == target) "$line It no longer counts." else "$line ${label(p.from)} will owe that amount again."
        }
        val box = if (parts.isEmpty() && payments.isEmpty()) null else LeftOutBox(parts.joinToString(", "), payments)
        return RemoveMemberModel(
            "Remove $name?",
            listOf(
                "$name will no longer see or add expenses. The group will switch to a new key so they can't read new entries.",
                "The group is recalculated as if $name had never joined. $name's entries stay in the history but don't count.",
            ),
            rows, box, "Remove $name",
        )
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
}
