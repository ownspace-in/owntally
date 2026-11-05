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
import owntally.model.equalShares
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

    /** The Settle tab: every group that can be opened, settled or not. */
    fun settleTabCards(cards: List<GroupCardModel>): List<GroupCardModel> = cards.filter { it.readable }

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
    /** Not counted because the person who paid was removed or left (shown greyed, not struck through). */
    val excluded: Boolean = false,
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
        if (shares == null && !e.deleted) {
            val gone = view.state.members[e.paidBy]
            val left = view.state.removals[e.paidBy]?.let { it.by == it.member } == true
            val who = gone?.name?.takeIf { it.isNotBlank() } ?: "Someone"
            return ExpenseRow(
                e.id, e.description, "Excluded: $who ${if (left) "left" else "was removed"}",
                null, MoneyFormat.format(e.total), BalanceKind.SETTLED, deleted = false, excluded = true,
            )
        }
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
            e.id, e.description, "$payer paid ${MoneyFormat.format(e.total)} · split ${if (ways == 1) "1 way" else "$ways ways"}",
            when (d.kind) {
                BalanceKind.OWED -> "you lent"
                BalanceKind.OWES -> "you owe"
                BalanceKind.SETTLED -> if (e.paidBy == view.me || mine.isPositive()) "no balance change" else "not involved"
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

enum class SplitMode { EQUAL, EXACT }

data class Person(val key: PublicKey, val label: String)

/**
 * The Add expense form. The shares shown are worked out with the core's own functions, using the counter the new event will get
 * (so the leftover paise are shown where they will land), never with a second copy of the rule.
 */
data class AddExpenseModel(
    val people: List<Person>,
    /** The counter of the event that will be written, from [owntally.data.session.GroupSession.nextCounter]. */
    val counter: Long,
    val today: Long,
    val me: PublicKey,
    val paidBy: PublicKey,
    val day: Long,
    val amountText: String = "",
    val description: String = "",
    val mode: SplitMode = SplitMode.EQUAL,
    val selected: Set<PublicKey> = people.map { it.key }.toSet(),
    val exactText: Map<PublicKey, String> = emptyMap(),
) {
    val trimmedDescription: String get() = description.trim()
    fun labelOf(key: PublicKey) = people.firstOrNull { it.key == key }?.label ?: "Someone"

    private val parsedAmount: owntally.model.format.ParsedAmount get() = owntally.model.format.MoneyInput.parse(amountText)
    private val total: Money? get() = (parsedAmount as? owntally.model.format.ParsedAmount.Valid)?.money?.takeIf { it.isPositive() }

    private sealed class Exact {
        data class Ok(val amounts: Map<PublicKey, Money>) : Exact()
        data class Bad(val key: PublicKey) : Exact()
    }

    private fun exact(): Exact {
        val out = LinkedHashMap<PublicKey, Money>()
        for (p in people) {
            when (val v = owntally.model.format.MoneyInput.parse(exactText[p.key].orEmpty())) {
                is owntally.model.format.ParsedAmount.Empty -> Unit
                is owntally.model.format.ParsedAmount.Invalid -> return Exact.Bad(p.key)
                is owntally.model.format.ParsedAmount.Valid -> if (v.money.isPositive()) out[p.key] = v.money
            }
        }
        return Exact.Ok(out)
    }

    private fun exactSum(): Money? = (exact() as? Exact.Ok)?.amounts?.values?.fold(Money.ZERO) { a, b -> a + b }

    /** What each person pays, as text, or "–" for someone not taking part. */
    val shareTexts: Map<PublicKey, String>
        get() {
            val t = total
            val shares: Map<PublicKey, Money> = when (mode) {
                SplitMode.EQUAL -> if (t != null && selected.isNotEmpty()) equalShares(t, selected, counter) else emptyMap()
                SplitMode.EXACT -> (exact() as? Exact.Ok)?.amounts ?: emptyMap()
            }
            return people.associate { it.key to (shares[it.key]?.let { m -> MoneyFormat.format(m) } ?: "–") }
        }

    /** Under an exact split: how much of the total is still to give out. */
    val exactStatus: String?
        get() {
            val t = total ?: return null
            val sum = exactSum() ?: return null
            return when {
                sum == t -> "All assigned"
                sum < t -> "${MoneyFormat.format(t - sum)} left to assign"
                else -> "${MoneyFormat.format(sum - t)} too much"
            }
        }

    val problem: String?
        get() {
            when (val a = parsedAmount) {
                is owntally.model.format.ParsedAmount.Invalid -> return when (a.problem) {
                    owntally.model.format.AmountProblem.TOO_LARGE -> "That amount is too large"
                    owntally.model.format.AmountProblem.TOO_MANY_DECIMALS -> "Use at most 2 digits after the point"
                    else -> "Type the amount in numbers, like 2400 or 2400.50"
                }
                is owntally.model.format.ParsedAmount.Valid -> if (!a.money.isPositive()) return "The amount must be more than ₹0"
                else -> Unit
            }
            if (trimmedDescription.length > MAX_DESCRIPTION) return "Use at most $MAX_DESCRIPTION letters"
            val t = total ?: return null
            if (mode == SplitMode.EQUAL) return if (selected.isEmpty()) "Choose at least one person" else null
            return when (val e = exact()) {
                is Exact.Bad -> "Check the amount for ${labelOf(e.key)}"
                is Exact.Ok -> when {
                    e.amounts.isEmpty() -> null
                    exactSum() != t -> "Amounts add up to ${MoneyFormat.format(exactSum()!!)}, not ${MoneyFormat.format(t)}"
                    else -> null
                }
            }
        }

    /** The split to write, or null if the form is not valid. */
    fun toSplit(): Split? {
        val t = total ?: return null
        return when (mode) {
            SplitMode.EQUAL -> if (selected.isEmpty()) null else Split.Equal(selected)
            SplitMode.EXACT -> (exact() as? Exact.Ok)?.amounts?.takeIf { it.isNotEmpty() && exactSum() == t }?.let { Split.Exact(it) }
        }
    }

    val canSave: Boolean
        get() = total != null && trimmedDescription.isNotEmpty() && trimmedDescription.length <= MAX_DESCRIPTION && toSplit() != null

    fun withAmount(text: String) = copy(amountText = text.take(AMOUNT_LIMIT))
    fun withDescription(text: String) = copy(description = text.take(INPUT_LIMIT))
    fun withMode(m: SplitMode) = copy(mode = m)
    fun toggle(key: PublicKey) = if (people.none { it.key == key }) this else copy(selected = if (key in selected) selected - key else selected + key)
    fun withExact(key: PublicKey, text: String) = if (people.none { it.key == key }) this else copy(exactText = exactText + (key to text.take(AMOUNT_LIMIT)))
    fun withPaidBy(key: PublicKey) = if (people.none { it.key == key }) this else copy(paidBy = key)
    fun earlier() = copy(day = day - 1)
    fun later() = if (day < today) copy(day = day + 1) else this

    companion object {
        const val MAX_DESCRIPTION = 80
        const val INPUT_LIMIT = 120
        const val AMOUNT_LIMIT = 16

        fun start(view: GroupView, counter: Long, today: Long): AddExpenseModel {
            val others = view.state.currentMembers.values.filter { it.key != view.me }
                .sortedBy { it.name.lowercase() }.map { Person(it.key, it.name.ifBlank { "Someone" }) }
            return AddExpenseModel(listOf(Person(view.me, "You")) + others, counter, today, view.me, view.me, today)
        }
    }
}

object DeleteRules {
    /** Creator or the group owner (not admins), and not already deleted (the core's rule, so the button is only shown when it would work). */
    fun canDelete(view: GroupView, e: Expense): Boolean {
        if (e.deleted) return false
        val role = view.state.members[view.me]?.takeIf { !it.removed }?.role ?: return false
        return e.creator == view.me || role == owntally.model.Role.OWNER
    }

    /**
     * True when someone on this expense (who paid or shares it) is in a payment that has been marked paid or received, so deleting
     * it moves amounts that people have settled by (owner decision, 4 Nov 2026). A disputed payment no longer counts, so it does not warn.
     */
    fun changesSettledAmounts(view: GroupView, e: Expense): Boolean {
        val involved = e.split.participantKeys + e.paidBy
        return view.state.settlements.values.any {
            (it.status == owntally.model.SettlementStatus.PAID_MARKED || it.status == owntally.model.SettlementStatus.CONFIRMED) &&
                (it.from in involved || it.to in involved)
        }
    }
}

enum class PaymentRole { FROM_ME, TO_ME, BETWEEN_OTHERS }

data class PaymentRow(
    val from: PublicKey,
    val to: PublicKey,
    val money: Money,
    val amount: String,
    val title: String,
    /** The phone number of the person on the other side (the one I pay, or the one who pays me), as they entered it. */
    val phone: String?,
    val role: PaymentRole,
)

/** Settle up for the whole group: the core's plan, row for row. */
data class SettleUpModel(val subtitle: String, val payments: List<PaymentRow>, val footer: String, val groupName: String = "") {
    companion object {
        const val INTRO = "A short list of payments that clear everyone. Pay however you like, outside OwnTally, then mark it here. OwnTally never moves or checks money."
        /** Marking payments as paid or received is not built yet, so those buttons are shown switched off. */
        const val ACTIONS_ENABLED = false
        const val ACTIONS_NOTE = "Recording payments arrives in a later update. Until then this is the list of who should pay whom."

        fun build(view: GroupView): SettleUpModel {
            fun name(k: PublicKey) = if (k == view.me) "You" else view.state.members[k]?.name?.takeIf { it.isNotBlank() } ?: "Someone"
            val rows = view.plan.map { p ->
                val role = when {
                    p.from == view.me -> PaymentRole.FROM_ME
                    p.to == view.me -> PaymentRole.TO_ME
                    else -> PaymentRole.BETWEEN_OTHERS
                }
                val other = if (role == PaymentRole.FROM_ME) p.to else p.from
                PaymentRow(
                    p.from, p.to, p.amount, MoneyFormat.format(p.amount), "${name(p.from)} → ${name(p.to)}",
                    if (role == PaymentRole.BETWEEN_OTHERS) view.state.members[p.to]?.phone else view.state.members[other]?.phone, role,
                )
            }
            val inPlan = view.plan.flatMap { listOf(it.from, it.to) }.toSet()
            val free = view.state.currentMembers.keys.filter { it != view.me && it !in inPlan }.map { name(it) }.sorted()
            val footer = when {
                view.plan.isEmpty() -> "Everyone is settled up."
                free.isEmpty() -> ""
                free.size == 1 -> "${free[0]} needs no payments."
                else -> "${free.dropLast(1).joinToString(", ")} and ${free.last()} need no payments."
            }
            return SettleUpModel("${view.state.name ?: "Group"} · whole group", rows, footer, view.state.name ?: "Group")
        }
    }
}

data class InfoCard(val number: String, val title: String, val body: String)

/** The How it works screen. Words follow design/06, corrected where the app does not do something yet or DESIGN.md says otherwise. */
object HowItWorks {
    val cards = listOf(
        InfoCard("1", "Everything lives on your phone", "Groups and expenses are saved in a local database. The app works fully in airplane mode."),
        InfoCard("2", "Every entry is a signed note", "Entries are never edited. A mistake is deleted and added again, so two phones can never conflict."),
        InfoCard("3", "Friends swap notes directly", "Join with a shared link or QR code, then sync nearby or over the internet. Each phone adds the notes it is missing, then works out the same balances."),
        InfoCard("4", "Settling is a handoff", "OwnTally suggests a short list of payments. You pay however you like, outside the app. It never holds or moves money."),
    )
    val note = InfoCard("", "Worth knowing", "If you lose your phone, your data goes with it unless you saved your 12-word recovery phrase. Other members can then send your groups back to you.")
}
