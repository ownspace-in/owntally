package owntally.ui.groups

import owntally.data.session.GroupView
import owntally.model.DisputeReason
import owntally.model.EventId
import owntally.model.SettlementStatus
import owntally.model.format.MoneyFormat

data class NotReceivedReason(val reason: DisputeReason, val label: String)

/**
 * "Payment not received" (design/15 and 15b) for a payment someone marked as paid to this person. [Form] asks what happened; [Sent] is what the
 * screen says once the answer is written (it follows the group's state, so it is the same after a restart); [Unavailable] is when the payment was
 * answered meanwhile (the other phone's answer arrived first) or is not this person's. No pronoun is used for anyone: a name never says one.
 * The mockup's Undo is not here: an event cannot be taken back, and the receiver can still confirm the payment later from Settle up.
 */
sealed interface NotReceivedModel {
    val screenTitle: String get() = "Payment not received"

    data class Form(
        val title: String, val amount: String, val markedAgo: String, val payerName: String,
        val reasons: List<NotReceivedReason>, val explanation: String, val tellLabel: String,
    ) : NotReceivedModel

    data class Sent(
        val title: String, val amount: String, val markedAgo: String, val bannerTitle: String, val bannerBody: String, val tip: String,
        val laterNote: String, val backLabel: String,
    ) : NotReceivedModel

    data class Unavailable(val message: String) : NotReceivedModel

    companion object {
        private val reasons = listOf(
            NotReceivedReason(DisputeReason.NOTHING_RECEIVED, "I haven't received anything"),
            NotReceivedReason(DisputeReason.DIFFERENT_AMOUNT, "The amount is different"),
            NotReceivedReason(DisputeReason.NOT_CHECKED, "I haven't checked my account yet"),
        )

        fun build(view: GroupView, settlement: EventId, nowSeconds: Long): NotReceivedModel {
            val s = view.state.settlements[settlement] ?: return Unavailable("This payment is no longer in the group.")
            if (s.to != view.me) return Unavailable("This payment is not one you received.")
            val payer = view.state.members[s.from]?.name?.takeIf { it.isNotBlank() } ?: "Someone"
            val title = "$payer → You"
            val amount = MoneyFormat.format(s.amount)
            val ago = "Marked as paid ${RelativeTime.ago(nowSeconds, s.recordedAtMillis / 1000)}"
            return when (s.status) {
                SettlementStatus.PAID_MARKED -> Form(
                    title, amount, ago, payer, reasons,
                    "$payer will see your answer and the payment goes back to \"Payments to make\". Nothing is deleted, and either of you can change it later.",
                    "Tell $payer",
                )
                SettlementStatus.DISPUTED -> Sent(
                    title, amount, ago, "$payer has been told",
                    "$payer will see this the next time $payer's phone syncs with yours. The payment shows as \"Not received\" until you both agree.",
                    "Tip: send $payer a reminder message so $payer knows to check, or mark it received once the money arrives.",
                    "If the money arrives later, tap Confirm received on Settle up.", "Back to settle up",
                )
                SettlementStatus.CONFIRMED -> Unavailable("This payment was already answered.")
            }
        }
    }
}
