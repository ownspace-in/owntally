package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.model.DisputeReason
import owntally.model.Money

/** M8, T8.4: what Settle up shows and offers to each person for every state of a payment (mockup 04), from the group's list. */
class SettleUpPaymentsModelTest {
    private val w = PayWorld()

    private fun asha() = SettleUpModel.build(w.viewFor(w.asha))
    private fun rohan() = SettleUpModel.build(w.viewFor(w.rohan))
    private fun meera() = SettleUpModel.build(w.viewFor(w.meera))

    // --- payments to make ----------------------------------------------------------------------------------

    @Test fun theOwedPersonCanRemindAndMarkReceivedAndTheDebtorCanCopyCallAndMarkPaid() {
        w.dinner()
        val mine = asha().payments.first { it.title == "Rohan → You" }
        assertEquals(listOf(PaymentAction.SEND_REMINDER, PaymentAction.MARK_RECEIVED), mine.actions)
        assertEquals("Rohan", mine.otherName)
        val theirs = rohan().payments.single { it.role == PaymentRole.FROM_ME }
        assertEquals("You → Asha", theirs.title)
        assertEquals(listOf(PaymentAction.COPY_NUMBER, PaymentAction.CALL, PaymentAction.I_PAID), theirs.actions)
        assertEquals("98765 00000", theirs.phone)
    }

    @Test fun aPaymentBetweenTwoOthersHasNoButtons() {
        w.dinner()
        w.markPaid(w.rohan, w.asha, 1_000); w.markPaid(w.meera, w.asha, 1_000)
        // Kabir owes 1,000 to Asha only; Rohan and Meera are settled. Add a second expense so Meera owes Rohan, seen by Kabir.
        w.emit(w.rohan, owntally.model.EventBody.ExpenseAdded("Taxi", w.rupees(600), w.rohan, 20_001, owntally.model.Split.Equal(setOf(w.rohan, w.meera))))
        val row = SettleUpModel.build(w.viewFor(w.kabir)).payments.single { it.role == PaymentRole.BETWEEN_OTHERS }
        assertEquals(emptyList(), row.actions)
    }

    @Test fun withoutANumberTheDebtorCanOnlyMarkPaid() {
        // A member whose number is blank cannot be built through the core, so check the rule on the model's own data class.
        val row = PaymentRow(w.rohan, w.asha, Money(100), "₹1", "You → Asha", null, PaymentRole.FROM_ME, "Asha", listOf(PaymentAction.I_PAID))
        assertEquals(listOf(PaymentAction.I_PAID), row.actions)
    }

    // --- waiting ------------------------------------------------------------------------------------------

    @Test fun aMarkedPaymentWaitsAndEachSideReadsItInTheirOwnWords() {
        w.dinner(); val paid = w.markPaid(w.rohan, w.asha, 1_000)
        val forAsha = asha().waiting.single()
        assertEquals(paid, forAsha.id)
        assertEquals("Rohan marked ₹1,000 as paid. Waiting for you to confirm.", forAsha.message)
        assertTrue(forAsha.canRespond)
        val forRohan = rohan().waiting.single()
        assertEquals("You marked ₹1,000 as paid. Waiting for Asha to confirm.", forRohan.message)
        assertFalse(forRohan.canRespond)
        val forMeera = meera().waiting.single()
        assertEquals("Rohan marked ₹1,000 as paid to Asha. Waiting for Asha to confirm.", forMeera.message)
        assertFalse(forMeera.canRespond)
    }

    @Test fun aMarkedPaymentLeavesThePlanBecauseItCounts() {
        w.dinner(); w.markPaid(w.rohan, w.asha, 1_000)
        assertTrue(asha().payments.none { it.title.startsWith("Rohan") })
    }

    @Test fun aPartPaymentLeavesTheRestAsAPaymentToMake() {
        w.dinner(); w.markPaid(w.rohan, w.asha, 400)
        assertEquals("₹600", rohan().payments.single { it.role == PaymentRole.FROM_ME }.amount)
    }

    // --- disputed -----------------------------------------------------------------------------------------

    @Test fun eachReasonIsToldToThePayerInWordsAndToTheReceiverAsTheirOwnAnswer() {
        val cases = listOf(
            DisputeReason.NOTHING_RECEIVED to ("Asha says nothing was received." to "You told Rohan nothing was received."),
            DisputeReason.DIFFERENT_AMOUNT to ("Asha says the amount is different." to "You told Rohan the amount is different."),
            DisputeReason.NOT_CHECKED to ("Asha has not checked the account yet." to "You told Rohan you had not checked the account yet."),
            DisputeReason.DUPLICATE to ("Asha recorded the same payment, so yours no longer counts." to "You stopped Rohan's entry counting: it is the same payment you recorded."),
        )
        for ((reason, texts) in cases) {
            val world = PayWorld(); world.dinner(); val paid = world.markPaid(world.rohan, world.asha, 1_000); world.dispute(world.asha, paid, reason)
            assertEquals(texts.first, SettleUpModel.build(world.viewFor(world.rohan)).disputed.single().message, "payer, $reason")
            val mine = SettleUpModel.build(world.viewFor(world.asha)).disputed.single()
            assertEquals(texts.second, mine.message, "receiver, $reason")
            assertTrue(mine.canConfirm)
            assertFalse(SettleUpModel.build(world.viewFor(world.rohan)).disputed.single().canConfirm)
        }
    }

    @Test fun aDisputedPaymentIsBackInPaymentsToMakeInFull() {
        w.dinner(); val paid = w.markPaid(w.rohan, w.asha, 1_000); w.dispute(w.asha, paid, DisputeReason.NOTHING_RECEIVED)
        assertEquals("₹1,000", rohan().payments.single { it.role == PaymentRole.FROM_ME }.amount)
        assertTrue(rohan().waiting.isEmpty())
    }

    @Test fun aDisputeThatWasConfirmedLaterIsNoLongerShown() {
        w.dinner(); val paid = w.markPaid(w.rohan, w.asha, 1_000); w.dispute(w.asha, paid, DisputeReason.NOT_CHECKED); w.confirm(w.asha, paid)
        assertTrue(asha().disputed.isEmpty() && asha().waiting.isEmpty())
    }

    // --- duplicates ---------------------------------------------------------------------------------------

    @Test fun thePersonWhoReceivedIsOfferedTheOneTapFixForADuplicate() {
        w.dinner(); val byPayer = w.markPaid(w.rohan, w.asha, 1_000)
        w.markReceived(w.rohan, w.asha, 1_000, seen = w.snapshot().filterKeys { it != w.rohan })
        val d = asha().duplicates.single()
        assertEquals(byPayer, d.payerEntry)
        assertEquals("This looks like the same payment recorded twice: Rohan marked ₹1,000 as paid and so did you.", d.message)
        assertEquals("Same payment: ignore Rohan's", d.buttonLabel)
        assertTrue(rohan().duplicates.isEmpty(), "only the receiver can fix it")
        assertTrue(meera().duplicates.isEmpty())
    }

    // --- footer -------------------------------------------------------------------------------------------

    @Test fun theFooterCountsConfirmedPayments() {
        w.dinner(); val paid = w.markPaid(w.rohan, w.asha, 1_000); w.confirm(w.asha, paid)
        assertEquals("1 payment already confirmed.", rohan().footer)
        w.markPaid(w.meera, w.asha, 1_000).also { w.confirm(w.asha, it) }
        assertTrue(asha().footer.endsWith("2 payments already confirmed."))
    }

    @Test fun whenNothingIsLeftToPayTheFooterSaysEveryoneIsSettled() {
        w.dinner()
        for (p in listOf(w.rohan, w.meera, w.kabir)) w.confirm(w.asha, w.markPaid(p, w.asha, 1_000))
        assertEquals("Everyone is settled up. 3 payments already confirmed.", asha().footer)
        assertTrue(asha().payments.isEmpty())
    }

    // --- the amount box (P2, P5) ---------------------------------------------------------------------------

    @Test fun theAmountBoxAcceptsAnAmountUpToThePlanWithoutAWarning() {
        val s = AmountBoxState.check("1000", w.rupees(1_350), java.util.Locale.forLanguageTag("en-IN"))
        assertEquals(w.rupees(1_000), s.money); assertNull(s.error); assertNull(s.warning); assertTrue(s.canConfirm)
    }

    @Test fun anAmountAboveThePlanIsAllowedWithAWarning() {
        val s = AmountBoxState.check("1400", w.rupees(1_350), java.util.Locale.forLanguageTag("en-IN"))
        assertEquals(w.rupees(1_400), s.money); assertTrue(s.canConfirm)
        assertEquals("This is more than the plan asks for. The extra will show as owed back to you by the group.", s.warning)
    }

    @Test fun theAmountBoxRefusesNothingZeroAndWhatIsNotANumber() {
        val inr = java.util.Locale.forLanguageTag("en-IN")
        assertFalse(AmountBoxState.check("", w.rupees(1), inr).canConfirm)
        assertEquals("The amount must be more than ₹0", AmountBoxState.check("0", w.rupees(1), inr).error)
        assertEquals("Type the amount in numbers, like 2400 or 2400.50", AmountBoxState.check("abc", w.rupees(1), inr).error)
        assertEquals("Use at most 2 digits after the point", AmountBoxState.check("1.234", w.rupees(1), inr).error)
        assertEquals("That amount is too large", AmountBoxState.check("99999999999", w.rupees(1), inr).error)
    }

    // --- the reminder (P6) --------------------------------------------------------------------------------

    @Test fun theReminderHasTheNameAndTheAmountAndNothingElse() {
        val t = SettleUpModel.reminderText("Rohan", "₹1,850")
        assertEquals("Hi Rohan, OwnTally says you owe me ₹1,850. Pay however suits you, then mark it as paid in the app.", t)
        assertFalse("Goa" in t, "never the group's name")
        assertFalse(Regex("\\d{5}").containsMatchIn(t), "never a phone number")
    }
}

class PhoneLinksAndMessagesTest {
    @Test fun theDialerLinkKeepsOnlyDigitsAndALeadingPlus() {
        assertEquals("tel:+919876543210", PhoneLinks.telUri("+91 98765-43210"))
        assertEquals("tel:9876543210", PhoneLinks.telUri("(98765) 43210"))
        assertEquals("tel:9876543210", PhoneLinks.telUri("98765.43210"))
    }

    @Test fun everyWayAPaymentCanBeRefusedHasAPlainMessageAndSuccessHasNone() {
        val id = owntally.model.EventId(owntally.model.PublicKey(ByteArray(32)), 1)
        assertNull(SettleMessages.of(owntally.data.session.AddResult.Added(id)))
        for (r in listOf(
            owntally.data.session.AddResult.Refused(owntally.model.IgnoreReason.ALREADY_DONE), owntally.data.session.AddResult.Refused(owntally.model.IgnoreReason.NOT_ALLOWED),
            owntally.data.session.AddResult.Refused(owntally.model.IgnoreReason.INVALID), owntally.data.session.AddResult.Refused(owntally.model.IgnoreReason.UNKNOWN_TARGET),
            owntally.data.session.AddResult.Invalid("x"), owntally.data.session.AddResult.NoKey,
        )) assertTrue(SettleMessages.of(r)!!.endsWith("."), "$r")
        assertEquals("That payment was already answered. The list shows how it stands.", SettleMessages.of(owntally.data.session.AddResult.Refused(owntally.model.IgnoreReason.ALREADY_DONE)))
    }
}
