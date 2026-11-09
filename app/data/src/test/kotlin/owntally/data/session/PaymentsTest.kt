package owntally.data.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import owntally.data.repo.TestEnv
import owntally.data.store.GroupRow
import owntally.data.store.StoredEnvelope
import owntally.model.DisputeReason
import owntally.model.EventId
import owntally.model.GroupId
import owntally.model.IgnoreReason
import owntally.model.Money
import owntally.model.PublicKey
import owntally.model.SettlementStatus
import owntally.model.Split

/** M8, T8.3: recording payments through the session, with phones that pass stored envelopes to each other. Asha paid 3,000 for the three of them. */
class PaymentsTest {
    private class Phone(val env: TestEnv, val group: GroupId, val slot: Long) {
        val key: PublicKey get() = env.identity.groupIdentity(slot)!!.publicKey
        val session: GroupSession by lazy { GroupSession(group, env.groups, env.secrets, env.identity, env.clock) }
        val view get() = session.view.value
        fun receiveFrom(other: Phone) {
            other.env.groups.envelopes(group).envelopes.forEach { env.groups.appendEnvelope(group, it) }
            session.refresh()
        }
    }

    private val ownerEnv = TestEnv().also { it.firstRun("Asha", "98765 43210") }
    private val group: GroupId = ownerEnv.groups.createGroup("Goa Trip")
    private val asha = Phone(ownerEnv, group, ownerEnv.groups.group(group)!!.keySlot)

    private fun joined(name: String, n: Int): Phone {
        val env = TestEnv().also { it.firstRun(name, "98765 0000$n") }
        val slot = env.identity.reserveSlot(emptyList())
        val identity = env.identity.groupIdentity(slot)!!
        val stored = asha.env.groups.envelopes(group).envelopes.map { StoredEnvelope(it.author, it.counter, it.encode()) }
        env.events.addGroup(GroupRow(group, slot, 0), stored)
        env.secrets.saveGroupKey(group, 1, asha.env.secrets.loadKeyring(group).keyring[1]!!)
        assertIs<AddResult.Added>(asha.session.approveJoin(identity.publicKey, identity.encryptionKey, name, "98765 0000$n"))
        return Phone(env, group, slot).also { it.receiveFrom(asha) }
    }

    private val ben = joined("Ben", 1)
    private val chitra = joined("Chitra", 2)

    init {
        asha.session.refresh()
        assertIs<AddResult.Added>(asha.session.addExpense("Dinner", Money(300_000), asha.key, 20_000, Split.Equal(setOf(asha.key, ben.key, chitra.key))))
        for (p in listOf(ben, chitra)) p.receiveFrom(asha)
        asha.receiveFrom(ben); asha.receiveFrom(chitra)
    }

    private fun rupees(n: Long) = Money(n * 100)
    private fun id(r: AddResult) = assertIs<AddResult.Added>(r).id
    private fun all() { for (x in listOf(asha, ben, chitra)) for (y in listOf(asha, ben, chitra)) if (x !== y) x.receiveFrom(y) }

    @Test fun aPersonMarksThatTheyPaidAndItWaitsForTheOtherToConfirm() {
        val paid = id(ben.session.markPaid(asha.key, rupees(1_000)))
        assertEquals(SettlementStatus.PAID_MARKED, ben.view.state.settlements.getValue(paid).status)
        assertEquals(listOf(paid), ben.view.overview.waiting.map { it.id })
        assertTrue(ben.view.overview.toMake.none { it.from == ben.key }, "it counts at once")
    }

    @Test fun aPersonMarksThatTheyReceivedAndItIsConfirmedFromTheStart() {
        val got = id(asha.session.markReceived(ben.key, rupees(1_000)))
        assertEquals(SettlementStatus.CONFIRMED, asha.view.state.settlements.getValue(got).status)
        assertEquals(listOf(got), asha.view.overview.confirmed.map { it.id })
    }

    @Test fun theReceiverConfirmsAndNobodyElseMay() {
        val paid = id(ben.session.markPaid(asha.key, rupees(1_000))); asha.receiveFrom(ben); chitra.receiveFrom(ben)
        assertEquals(AddResult.Refused(IgnoreReason.NOT_ALLOWED), chitra.session.confirm(paid))
        assertEquals(AddResult.Refused(IgnoreReason.NOT_ALLOWED), ben.session.confirm(paid), "not the payer either")
        id(asha.session.confirm(paid))
        assertEquals(SettlementStatus.CONFIRMED, asha.view.state.settlements.getValue(paid).status)
        assertEquals(AddResult.Refused(IgnoreReason.ALREADY_DONE), asha.session.confirm(paid))
    }

    @Test fun theReceiverCanSayNotReceivedWithAReasonAndThePaymentGoesBackToThePlan() {
        val paid = id(ben.session.markPaid(asha.key, rupees(1_000))); asha.receiveFrom(ben)
        id(asha.session.dispute(paid, DisputeReason.DIFFERENT_AMOUNT))
        assertEquals(DisputeReason.DIFFERENT_AMOUNT, asha.view.overview.disputed.single().disputeReason)
        assertEquals(rupees(1_000), asha.view.overview.toMake.single { it.from == ben.key }.amount)
        ben.receiveFrom(asha)
        assertEquals(DisputeReason.DIFFERENT_AMOUNT, ben.view.overview.disputed.single().disputeReason, "the payer sees the reason")
    }

    @Test fun onlyTheReceiverMayDisputeAndOnlyAPaymentMarkedAsPaid() {
        val paid = id(ben.session.markPaid(asha.key, rupees(1_000))); asha.receiveFrom(ben); chitra.receiveFrom(ben)
        assertEquals(AddResult.Refused(IgnoreReason.NOT_ALLOWED), chitra.session.dispute(paid, DisputeReason.NOT_CHECKED))
        assertEquals(AddResult.Refused(IgnoreReason.NOT_ALLOWED), ben.session.dispute(paid, DisputeReason.NOT_CHECKED))
        id(asha.session.confirm(paid))
        assertEquals(AddResult.Refused(IgnoreReason.ALREADY_DONE), asha.session.dispute(paid, DisputeReason.NOTHING_RECEIVED), "a confirmed payment cannot be disputed")
    }

    @Test fun aDisputedPaymentCanBeConfirmedLater() {
        val paid = id(ben.session.markPaid(asha.key, rupees(1_000))); asha.receiveFrom(ben)
        id(asha.session.dispute(paid, DisputeReason.NOT_CHECKED))
        id(asha.session.confirm(paid))
        assertEquals(SettlementStatus.CONFIRMED, asha.view.state.settlements.getValue(paid).status)
    }

    @Test fun aPaymentMustBeToAnotherMemberAndAPositiveAmountWithinTheLimit() {
        assertEquals(AddResult.Invalid("a payment needs two different members"), ben.session.markPaid(ben.key, rupees(10)))
        assertIs<AddResult.Invalid>(ben.session.markPaid(asha.key, Money(0)))
        assertIs<AddResult.Invalid>(ben.session.markPaid(asha.key, Money(-5)))
        assertIs<AddResult.Invalid>(ben.session.markPaid(asha.key, Money(Money.MAX_AMOUNT.paise + 1)))
        assertEquals(0, ben.view.state.settlements.size)
    }

    @Test fun aPaymentToSomeoneWhoIsNotAMemberIsRefused() {
        val stranger = PublicKey(ByteArray(32) { 0x55 })
        assertEquals(AddResult.Refused(IgnoreReason.INVALID), ben.session.markPaid(stranger, rupees(10)))
    }

    @Test fun aPaymentAboveThePlanIsRecordedAndTheGroupOwesThePayerTheExtra() {
        id(ben.session.markPaid(asha.key, rupees(1_050)))
        val o = ben.view.overview
        assertTrue(o.toMake.none { it.from == ben.key })
        assertEquals(rupees(50), o.toMake.filter { it.to == ben.key }.fold(Money.ZERO) { x, p -> x + p.amount })
    }

    @Test fun theSamePaymentRecordedByBothSidesCanBeCancelledByTheReceiverWithOneCall() {
        val byPayer = id(ben.session.markPaid(asha.key, rupees(1_000)))
        id(asha.session.markReceived(ben.key, rupees(1_000)))   // before Ben's entry reached Asha
        all()
        assertEquals(1, asha.view.overview.duplicates.size)
        id(asha.session.cancelDuplicate(byPayer))
        all()
        for (p in listOf(asha, ben, chitra)) {
            assertEquals(DisputeReason.DUPLICATE, p.view.overview.disputed.single().disputeReason)
            assertTrue(p.view.overview.duplicates.isEmpty())
            assertTrue(p.view.overview.toMake.none { it.to == ben.key }, "the balances are right again on every phone")
        }
    }

    @Test fun cancelDuplicateRefusesAnythingThatIsNotAFoundDuplicateOrNotYours() {
        val lone = id(ben.session.markPaid(asha.key, rupees(1_000))); asha.receiveFrom(ben)
        assertIs<AddResult.Invalid>(asha.session.cancelDuplicate(lone), "no matching entry by the receiver: not a duplicate")
        id(asha.session.markReceived(ben.key, rupees(1_000))); ben.receiveFrom(asha)
        assertIs<AddResult.Invalid>(ben.session.cancelDuplicate(lone), "only the receiver may")
        assertIs<AddResult.Invalid>(asha.session.cancelDuplicate(EventId(asha.key, 999)), "no such payment")
    }

    @Test fun paymentsSurviveAReopenOfTheStoredLog() {
        val paid = id(ben.session.markPaid(asha.key, rupees(1_000)))
        val again = GroupSession(group, ben.env.groups, ben.env.secrets, ben.env.identity, ben.env.clock)
        assertEquals(listOf(paid), again.view.value.overview.waiting.map { it.id })
    }
}
