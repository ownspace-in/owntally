package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import owntally.model.DisputeReason
import owntally.model.EventId

/** M8, T8.5: "Payment not received" (design/15 and 15b), from the group's state: the form, what it says after, and when it is no longer there. */
class NotReceivedModelTest {
    private val w = PayWorld()
    private val now = 1_000_000L

    private fun forAsha(id: EventId) = NotReceivedModel.build(w.viewFor(w.asha), id, now)

    @Test fun aPaymentMarkedAsPaidShowsTheFormWithThreeReasonsAndTheFirstChosen() {
        w.dinner(); val paid = w.markPaid(w.rohan, w.asha, 1_000)
        val m = assertIs<NotReceivedModel.Form>(forAsha(paid))
        assertEquals("Rohan → You", m.title)
        assertEquals("₹1,000", m.amount)
        assertEquals("Marked as paid ${RelativeTime.ago(now, 1)}", m.markedAgo)
        assertEquals(listOf("I haven't received anything", "The amount is different", "I haven't checked my account yet"), m.reasons.map { it.label })
        assertEquals(listOf(DisputeReason.NOTHING_RECEIVED, DisputeReason.DIFFERENT_AMOUNT, DisputeReason.NOT_CHECKED), m.reasons.map { it.reason })
        assertEquals("Tell Rohan", m.tellLabel)
        assertEquals("Rohan will see your answer and the payment goes back to \"Payments to make\". Nothing is deleted, and either of you can change it later.", m.explanation)
    }

    @Test fun afterTheAnswerIsWrittenTheScreenSaysWhoHasBeenToldInWordsWithoutPronouns() {
        w.dinner(); val paid = w.markPaid(w.rohan, w.asha, 1_000); w.dispute(w.asha, paid, DisputeReason.DIFFERENT_AMOUNT)
        val m = assertIs<NotReceivedModel.Sent>(forAsha(paid))
        assertEquals("Rohan has been told", m.bannerTitle)
        assertEquals("Rohan will see this the next time Rohan's phone syncs with yours. The payment shows as \"Not received\" until you both agree.", m.bannerBody)
        assertEquals("Tip: send Rohan a reminder message so Rohan knows to check, or mark it received once the money arrives.", m.tip)
        assertEquals("Back to settle up", m.backLabel)
        val all = listOf(m.bannerBody, m.tip, m.bannerTitle).joinToString(" ").lowercase()
        assertTrue(listOf(" she ", " he ", " her ", " his ", "she'll", "he'll").none { it in " $all " }, "no pronoun is guessed")
    }

    @Test fun thereIsNoUndoBecauseAnEventCannotBeTakenBack() {
        w.dinner(); val paid = w.markPaid(w.rohan, w.asha, 1_000); w.dispute(w.asha, paid, DisputeReason.NOTHING_RECEIVED)
        val m = assertIs<NotReceivedModel.Sent>(forAsha(paid))
        assertTrue("Undo" !in listOf(m.bannerTitle, m.bannerBody, m.tip, m.backLabel))
        assertEquals("If the money arrives later, tap Confirm received on Settle up.", m.laterNote)
    }

    @Test fun aPaymentAlreadyConfirmedOrNotYoursOrUnknownIsNotThere() {
        w.dinner(); val paid = w.markPaid(w.rohan, w.asha, 1_000); w.confirm(w.asha, paid)
        assertEquals("This payment was already answered.", assertIs<NotReceivedModel.Unavailable>(forAsha(paid)).message)
        val other = w.markPaid(w.meera, w.asha, 1_000)
        assertEquals("This payment is not one you received.", assertIs<NotReceivedModel.Unavailable>(NotReceivedModel.build(w.viewFor(w.kabir), other, now)).message)
        assertEquals("This payment is no longer in the group.", assertIs<NotReceivedModel.Unavailable>(forAsha(EventId(w.asha, 999))).message)
    }
}
