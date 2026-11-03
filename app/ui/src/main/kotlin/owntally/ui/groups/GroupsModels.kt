package owntally.ui.groups

import owntally.data.session.GroupSummary
import owntally.data.session.GroupView
import owntally.model.Expense
import owntally.model.GroupId
import owntally.model.Money
import owntally.model.PublicKey
import owntally.model.Role
import owntally.model.Split
import owntally.model.format.BalanceKind
import owntally.model.format.BalanceView
import owntally.model.format.MoneyFormat
import owntally.model.sharesOf
import owntally.model.totalSpent

/** What the Groups screen shows, worked out from the stored data. No Android code here, so it is tested on the JVM. */
data class GroupCardModel(
    val id: GroupId,
    val name: String,
    val initials: String,
    val colourIndex: Int,
    val subtitle: String,
    val balance: BalanceView,
    val readable: Boolean,
)

data class GroupsListModel(val owed: BalanceView, val owe: BalanceView, val cards: List<GroupCardModel>)

private fun plural(n: Int, one: String, many: String) = if (n == 1) "$n $one" else "$n $many"

object GroupsModels {
    /** Two letters for the group's icon: the first letters of the first two words, or the first two letters of one word. */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val letters = when {
            words.isEmpty() -> ""
            words.size == 1 -> words[0].codePoints().limit(2).toArray().joinToString("") { String(Character.toChars(it)) }
            else -> words.take(2).joinToString("") { String(Character.toChars(it.codePointAt(0))) }
        }
        return letters.uppercase()
    }

    fun list(summaries: List<GroupSummary>): GroupsListModel {
        val readable = summaries.filter { it.readable }
        val owed = readable.map { it.myBalance }.filter { it.isPositive() }.fold(Money.ZERO) { a, b -> a + b }
        val owe = readable.map { it.myBalance }.filter { it.isNegative() }.fold(Money.ZERO) { a, b -> a + -b }
        return GroupsListModel(
            owed = MoneyFormat.describe(owed),
            owe = MoneyFormat.describe(-owe),
            cards = summaries.map {
                GroupCardModel(
                    id = it.groupId,
                    name = it.name,
                    initials = initials(it.name),
                    colourIndex = it.colourIndex,
                    subtitle = if (it.readable) {
                        "${plural(it.memberCount, "member", "members")} · ${plural(it.expenseCount, "expense", "expenses")}"
                    } else {
                        "Waiting for the group key"
                    },
                    balance = MoneyFormat.describe(it.myBalance),
                    readable = it.readable,
                )
            },
        )
    }
}

/** One line under the balance card: "Rohan owes ₹1,850", "Meera is owed ₹300", or "Kabir & Isha" with "settled". */
data class MemberLine(val text: String, val amount: String?, val kind: BalanceKind)

data class ExpenseRow(
    val id: owntally.model.EventId,
    val description: String,
    val subtitle: String,
    /** "you lent", "you owe", or null for a deleted expense or one that does not involve me. */
    val caption: String?,
    val amount: String,
    val kind: BalanceKind,
    val deleted: Boolean,
)

data class GroupScreenModel(
    val name: String,
    val roleText: String,
    val balance: BalanceView,
    val totalSpent: String,
    val memberLines: List<MemberLine>,
    val expenses: List<ExpenseRow>,
    /** Everyone except me has a zero balance (or there is nobody else yet). */
    val allSettled: Boolean,
)

object GroupScreenModels {
    private fun nameOf(view: GroupView, key: PublicKey) = view.state.members[key]?.name?.takeIf { it.isNotBlank() } ?: "Someone"

    fun roleText(role: Role?) = when (role) {
        Role.OWNER -> "You are the owner"
        Role.ADMIN -> "You are an admin"
        else -> "You are a member"
    }

    private fun joinNames(names: List<String>) = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
    }

    fun build(view: GroupView): GroupScreenModel {
        val current = view.state.currentMembers
        val others = current.keys.filter { it != view.me }
        val lines = ArrayList<MemberLine>()
        val settled = ArrayList<String>()
        for (key in others.sortedBy { nameOf(view, it).lowercase() }) {
            val b = view.balances[key] ?: Money.ZERO
            when {
                b.isPositive() -> lines += MemberLine("${nameOf(view, key)} is owed", MoneyFormat.format(b), BalanceKind.OWED)
                b.isNegative() -> lines += MemberLine("${nameOf(view, key)} owes", MoneyFormat.format(-b), BalanceKind.OWES)
                else -> settled += nameOf(view, key)
            }
        }
        if (settled.isNotEmpty()) lines += MemberLine(joinNames(settled), "settled", BalanceKind.SETTLED)
        return GroupScreenModel(
            name = view.state.name ?: "Group",
            roleText = roleText(view.state.members[view.me]?.role),
            balance = MoneyFormat.describe(view.balances[view.me] ?: Money.ZERO),
            totalSpent = MoneyFormat.format(totalSpent(view.state)),
            memberLines = lines,
            expenses = view.state.expenses.values
                .sortedWith(compareByDescending<Expense> { it.day }.thenByDescending { it.id.counter }.thenBy { it.id.author })
                .map { row(view, it) },
            allSettled = others.all { (view.balances[it] ?: Money.ZERO) == Money.ZERO },
        )
    }

    private fun row(view: GroupView, e: Expense): ExpenseRow {
        val ways = when (val s = e.split) {
            is Split.Equal -> s.participants.size
            is Split.Exact -> s.amounts.size
        }
        val payer = if (e.paidBy == view.me) "You" else nameOf(view, e.paidBy)
        val shares = sharesOf(view.state, e)
        if (shares == null) {
            val by = e.deletedBy?.let { nameOf(view, it) }
            return ExpenseRow(
                e.id, e.description, if (by == null) "Deleted · not counted" else "Deleted by $by · not counted",
                null, MoneyFormat.format(e.total), BalanceKind.SETTLED, deleted = true,
            )
        }
        val mine = shares[view.me] ?: Money.ZERO
        val net = (if (e.paidBy == view.me) e.total else Money.ZERO) - mine
        val d = MoneyFormat.describe(net)
        return ExpenseRow(
            e.id, e.description, "$payer paid ${MoneyFormat.format(e.total)} · split $ways ways",
            when (d.kind) {
                BalanceKind.OWED -> "you lent"
                BalanceKind.OWES -> "you owe"
                BalanceKind.SETTLED -> "not involved"
            },
            d.text, d.kind, deleted = false,
        )
    }
}

/** The bottom bar. All four tabs are always there; Sync is a placeholder until M4 and says so on its screen. */
enum class Tab(val label: String, val enabled: Boolean) {
    GROUPS("Groups", true), SETTLE("Settle", true), SYNC("Sync", false), ABOUT("About", true),
}

object SyncPlaceholder {
    const val TEXT = "Syncing with friends arrives in a later update. For now your data is stored only on this phone."
}

/** The Create group form: a name, a colour for the icon (kept only on this phone), and rupees as the only currency for now. */
data class CreateGroupModel(val name: String = "", val colourIndex: Int = 0) {
    val trimmedName: String get() = name.trim()
    val canCreate: Boolean get() = trimmedName.isNotEmpty() && trimmedName.length <= MAX_NAME
    val problem: String? get() = if (trimmedName.length > MAX_NAME) "Use at most $MAX_NAME letters" else null

    fun withName(text: String) = copy(name = text.take(INPUT_LIMIT))
    fun withColour(index: Int) = if (index in 0 until COLOURS) copy(colourIndex = index) else this

    companion object {
        const val MAX_NAME = 40
        const val INPUT_LIMIT = 60
        const val COLOURS = owntally.data.session.GroupLooks.PALETTE_SIZE
        const val CURRENCY = "INR"
    }
}
