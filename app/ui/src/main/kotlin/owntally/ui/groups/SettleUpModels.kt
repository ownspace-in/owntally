package owntally.ui.groups

import owntally.data.session.GroupView
import owntally.model.DisputeReason
import owntally.model.EventId
import owntally.model.Money
import owntally.model.PublicKey
import owntally.model.Settlement
import owntally.model.format.AmountProblem
import owntally.model.format.MoneyFormat
import owntally.model.format.MoneyInput
import owntally.model.format.ParsedAmount

enum class PaymentRole { FROM_ME, TO_ME, BETWEEN_OTHERS }

/** What a person can do on a payment of the plan (M8, decision P1). */
enum class PaymentAction { COPY_NUMBER, CALL, I_PAID, SEND_REMINDER, MARK_RECEIVED }

/** A payment still to be made: the plan, row for row. */
data class PaymentRow(
    val from: PublicKey,
    val to: PublicKey,
    val money: Money,
    val amount: String,
    val title: String,
    /** The phone number of the person on the other side (the one I pay, or the one who pays me), as they entered it. */
    val phone: String?,
    val role: PaymentRole,
    /** The name of the person on the other side, for the reminder. */
    val otherName: String = "",
    val actions: List<PaymentAction> = emptyList(),
)

/** A payment marked as paid and not yet confirmed. [canRespond]: I am the one who received it, so I can confirm it or say it did not arrive. */
data class WaitingRow(
    val id: EventId, val from: PublicKey, val to: PublicKey, val money: Money, val amount: String, val title: String,
    val role: PaymentRole, val message: String, val canRespond: Boolean, val payerName: String,
)

/** A payment its receiver said did not arrive (or was a duplicate). It does not count; the plan shows the amount again. [canConfirm]: I am the receiver and may still confirm it. */
data class DisputedRow(
    val id: EventId, val from: PublicKey, val to: PublicKey, val money: Money, val amount: String, val title: String,
    val role: PaymentRole, val message: String, val canConfirm: Boolean,
)

/** The receiver's one-tap fix for a payment recorded twice (decision P3). Only built for the receiver. */
data class DuplicateRow(val payerEntry: EventId, val message: String, val buttonLabel: String)

/** Settle up for the whole group: the list of the core ([owntally.model.settleOverview]) with what each person can do. */
data class SettleUpModel(
    val subtitle: String,
    val payments: List<PaymentRow>,
    val footer: String,
    val groupName: String = "",
    val waiting: List<WaitingRow> = emptyList(),
    val disputed: List<DisputedRow> = emptyList(),
    val duplicates: List<DuplicateRow> = emptyList(),
) {
    companion object {
        const val INTRO = "A short list of payments that clear everyone. Pay however you like, outside OwnTally, then mark it here. OwnTally never moves or checks money."
        const val NOTE = "If you owe someone, you'll see I paid here, with their number to copy or call. The plan updates if an expense is added or deleted."

        /** The words the receiver's answer has for the payer, and for the receiver about their own answer (no pronouns: a name never says one). */
        private fun reasonForPayer(name: String, reason: DisputeReason?) = when (reason) {
            DisputeReason.NOTHING_RECEIVED -> "$name says nothing was received."
            DisputeReason.DIFFERENT_AMOUNT -> "$name says the amount is different."
            DisputeReason.NOT_CHECKED -> "$name has not checked the account yet."
            DisputeReason.DUPLICATE -> "$name recorded the same payment, so yours no longer counts."
            null -> "$name did not accept this payment."
        }

        private fun reasonForReceiver(payer: String, reason: DisputeReason?) = when (reason) {
            DisputeReason.NOTHING_RECEIVED -> "You told $payer nothing was received."
            DisputeReason.DIFFERENT_AMOUNT -> "You told $payer the amount is different."
            DisputeReason.NOT_CHECKED -> "You told $payer you had not checked the account yet."
            DisputeReason.DUPLICATE -> "You stopped $payer's entry counting: it is the same payment you recorded."
            null -> "You did not accept this payment."
        }

        fun build(view: GroupView): SettleUpModel {
            fun name(k: PublicKey) = if (k == view.me) "You" else view.state.members[k]?.name?.takeIf { it.isNotBlank() } ?: "Someone"
            fun roleOf(from: PublicKey, to: PublicKey) = when {
                from == view.me -> PaymentRole.FROM_ME
                to == view.me -> PaymentRole.TO_ME
                else -> PaymentRole.BETWEEN_OTHERS
            }
            val o = view.overview
            val rows = o.toMake.map { p ->
                val role = roleOf(p.from, p.to)
                val other = if (role == PaymentRole.FROM_ME) p.to else p.from
                val phone = if (role == PaymentRole.BETWEEN_OTHERS) view.state.members[p.to]?.phone else view.state.members[other]?.phone
                val actions = when (role) {
                    PaymentRole.FROM_ME -> if (phone.isNullOrBlank()) listOf(PaymentAction.I_PAID) else listOf(PaymentAction.COPY_NUMBER, PaymentAction.CALL, PaymentAction.I_PAID)
                    PaymentRole.TO_ME -> listOf(PaymentAction.SEND_REMINDER, PaymentAction.MARK_RECEIVED)
                    PaymentRole.BETWEEN_OTHERS -> emptyList()
                }
                PaymentRow(p.from, p.to, p.amount, MoneyFormat.format(p.amount), "${name(p.from)} → ${name(p.to)}", phone, role, name(other), actions)
            }
            val waiting = o.waiting.map { s ->
                val role = roleOf(s.from, s.to)
                val amount = MoneyFormat.format(s.amount)
                val message = when (role) {
                    PaymentRole.FROM_ME -> "You marked $amount as paid. Waiting for ${name(s.to)} to confirm."
                    PaymentRole.TO_ME -> "${name(s.from)} marked $amount as paid. Waiting for you to confirm."
                    PaymentRole.BETWEEN_OTHERS -> "${name(s.from)} marked $amount as paid to ${name(s.to)}. Waiting for ${name(s.to)} to confirm."
                }
                WaitingRow(s.id, s.from, s.to, s.amount, amount, "${name(s.from)} → ${name(s.to)}", role, message, role == PaymentRole.TO_ME, name(s.from))
            }
            val disputed = o.disputed.map { s ->
                val role = roleOf(s.from, s.to)
                val message = if (role == PaymentRole.TO_ME) reasonForReceiver(name(s.from), s.disputeReason) else reasonForPayer(name(s.to), s.disputeReason)
                DisputedRow(s.id, s.from, s.to, s.amount, MoneyFormat.format(s.amount), "${name(s.from)} → ${name(s.to)}", role, message, role == PaymentRole.TO_ME)
            }
            val duplicates = o.duplicates.filter { it.payerEntry.to == view.me }.map { d ->
                val payer = name(d.payerEntry.from); val amount = MoneyFormat.format(d.payerEntry.amount)
                DuplicateRow(d.payerEntry.id, "This looks like the same payment recorded twice: $payer marked $amount as paid and so did you.", "Same payment: ignore $payer's")
            }

            val inPlan = o.toMake.flatMap { listOf(it.from, it.to) }.toSet()
            val free = view.state.currentMembers.keys.filter { it != view.me && it !in inPlan }.map { name(it) }.sorted()
            val confirmed = o.confirmed.size
            val parts = ArrayList<String>()
            when {
                o.toMake.isEmpty() -> parts += "Everyone is settled up."
                free.size == 1 -> parts += "${free[0]} needs no payments."
                free.size > 1 -> parts += "${free.dropLast(1).joinToString(", ")} and ${free.last()} need no payments."
            }
            if (confirmed == 1) parts += "1 payment already confirmed." else if (confirmed > 1) parts += "$confirmed payments already confirmed."
            return SettleUpModel(
                "${view.state.name ?: "Group"} · whole group", rows, parts.joinToString(" "), view.state.name ?: "Group", waiting, disputed, duplicates,
            )
        }

        /** The text of a reminder the receiver sends through the share sheet (decision P6): the name and the amount, never the group or a number. */
        fun reminderText(payerName: String, amount: String) =
            "Hi $payerName, OwnTally says you owe me $amount. Pay however suits you, then mark it as paid in the app."
    }
}

/** What the amount box says about what was typed (decision P2); [planned] is what the plan asks for (decision P5: more is allowed, with a warning). */
data class AmountBoxState(val money: Money?, val error: String?, val warning: String?) {
    val canConfirm: Boolean get() = money != null && error == null

    companion object {
        const val MORE_THAN_PLANNED = "This is more than the plan asks for. The extra will show as owed back to you by the group."

        fun check(text: String, planned: Money?, locale: java.util.Locale? = null): AmountBoxState =
            when (val a = MoneyInput.parse(text, locale)) {
                ParsedAmount.Empty -> AmountBoxState(null, null, null)
                is ParsedAmount.Invalid -> AmountBoxState(
                    null,
                    when (a.problem) {
                        AmountProblem.TOO_LARGE -> "That amount is too large"
                        AmountProblem.TOO_MANY_DECIMALS -> "Use at most 2 digits after the point"
                        else -> "Type the amount in numbers, like 2400 or 2400.50"
                    },
                    null,
                )
                is ParsedAmount.Valid -> when {
                    !a.money.isPositive() -> AmountBoxState(null, "The amount must be more than ₹0", null)
                    planned != null && a.money > planned -> AmountBoxState(a.money, null, MORE_THAN_PLANNED)
                    else -> AmountBoxState(a.money, null, null)
                }
            }
    }
}

/** The dialer link for a number a person typed (spaces, dashes and brackets removed, a leading + kept). The dialer opens; nothing is dialled. */
object PhoneLinks {
    fun telUri(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return "tel:" + (if (phone.trim().startsWith("+")) "+" else "") + digits
    }
}

/** What to tell the person when a payment could not be written (the other phone's answer may have arrived first). Null when it worked. */
object SettleMessages {
    fun of(result: owntally.data.session.AddResult): String? = when (result) {
        is owntally.data.session.AddResult.Added -> null
        is owntally.data.session.AddResult.Refused -> when (result.reason) {
            owntally.model.IgnoreReason.ALREADY_DONE -> "That payment was already answered. The list shows how it stands."
            owntally.model.IgnoreReason.NOT_ALLOWED -> "You can't do that on this payment."
            owntally.model.IgnoreReason.INVALID, owntally.model.IgnoreReason.UNKNOWN_TARGET -> "That payment or person is no longer in the group."
            else -> "That could not be saved."
        }
        is owntally.data.session.AddResult.Invalid -> "Check the amount and try again."
        owntally.data.session.AddResult.NoKey -> "This phone cannot write to the group yet. Open Sync and try again."
    }
}
