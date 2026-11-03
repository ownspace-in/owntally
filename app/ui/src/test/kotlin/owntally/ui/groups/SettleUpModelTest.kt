package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import owntally.model.Money
import owntally.model.settlementPlan

class SettleUpModelTest {
    private val w = TestWorld("Asha")
    private val s = w.sessions.session(w.newGroup("Goa Trip"))
    private val me = s.view.value.me
    private val rohan = w.addFriend(s, "Rohan", 2_000_001)
    private val meera = w.addFriend(s, "Meera", 2_000_002)

    @Test fun nothingToPay() {
        val m = SettleUpModel.build(s.view.value)
        assertTrue(m.payments.isEmpty())
        assertEquals("Everyone is settled up.", m.footer)
        assertEquals("Goa Trip · whole group", m.subtitle)
    }

    @Test fun theRowsAreExactlyTheCoresPlanInOrder() {
        w.expense(s, "Dinner", w.rupees(900), me, me, rohan, meera)
        w.expense(s, "Villa", w.rupees(600), meera, me, rohan, meera, day = 20_001)
        w.expense(s, "Cab", w.rupees(250), rohan, me, rohan, meera, day = 20_002)
        val view = s.view.value
        val plan = settlementPlan(view.balances)
        val m = SettleUpModel.build(view)
        assertEquals(plan.size, m.payments.size)
        for ((row, p) in m.payments.zip(plan)) {
            assertEquals(owntally.model.format.MoneyFormat.format(p.amount), row.amount)
            assertEquals(p.from, row.from)
            assertEquals(p.to, row.to)
            assertEquals(p.amount, row.money)
        }
        // everyone's balance is cleared by the rows shown
        val cleared = view.balances.toMutableMap()
        for (r in m.payments) {
            cleared[r.from] = cleared.getValue(r.from) + r.money
            cleared[r.to] = cleared.getValue(r.to) - r.money
        }
        assertTrue(cleared.values.all { it == Money.ZERO })
    }

    @Test fun youAndNamesAndWhoPaysWhom() {
        w.expense(s, "Dinner", w.rupees(900), me, me, rohan, meera) // Rohan and Meera each owe me 300
        val m = SettleUpModel.build(s.view.value)
        assertEquals(setOf("Rohan → You", "Meera → You"), m.payments.map { it.title }.toSet())
        assertTrue(m.payments.all { it.role == PaymentRole.TO_ME })
        assertEquals(setOf("98765 00001", "98765 00002"), m.payments.map { it.phone }.toSet())
        assertTrue(m.payments.all { it.amount == "₹300" })
    }

    @Test fun whenIOweIAmTheOneWhoPays() {
        w.expense(s, "Villa", w.rupees(900), rohan, me, rohan, meera)
        val m = SettleUpModel.build(s.view.value)
        val mine = m.payments.single { it.from == me }
        assertEquals(PaymentRole.FROM_ME, mine.role)
        assertEquals("You → Rohan", mine.title)
        assertEquals("98765 00001", mine.phone)
    }

    @Test fun paymentsBetweenOthersAreShownToo() {
        w.expense(s, "Dinner", w.rupees(900), rohan, rohan, meera) // Meera owes Rohan 450, nobody owes me
        val m = SettleUpModel.build(s.view.value)
        val row = m.payments.single()
        assertEquals(PaymentRole.BETWEEN_OTHERS, row.role)
        assertEquals("Meera → Rohan", row.title)
        assertEquals("", SettleUpModel.build(s.view.value).footer, "I am in nobody's payment and am not listed as \"You needs no payments\"")
    }

    @Test fun footerNamesThoseWhoNeedNoPayments() {
        w.expense(s, "Dinner", w.rupees(900), me, me, rohan) // Meera is not on it
        var m = SettleUpModel.build(s.view.value)
        assertEquals("Meera needs no payments.", m.footer)
        w.expense(s, "Both", w.rupees(100), me, me) // still only Meera
        m = SettleUpModel.build(s.view.value)
        assertEquals("Meera needs no payments.", m.footer)
    }

    @Test fun footerWithSeveralPeopleWhoNeedNoPayments() {
        val w2 = TestWorld("Asha")
        val s2 = w2.sessions.session(w2.newGroup("G"))
        val me2 = s2.view.value.me
        val ben = w2.addFriend(s2, "Ben", 2_000_001)
        w2.addFriend(s2, "Meera", 2_000_002)
        w2.addFriend(s2, "Kabir", 2_000_003)
        w2.expense(s2, "Tea", w2.rupees(100), me2, me2, ben)
        assertEquals("Kabir and Meera need no payments.", SettleUpModel.build(s2.view.value).footer)
        w2.addFriend(s2, "Isha", 2_000_004)
        assertEquals("Isha, Kabir and Meera need no payments.", SettleUpModel.build(s2.view.value).footer)
    }

    @Test fun footerWithTwoPeople() {
        val w2 = TestWorld("Asha")
        val s2 = w2.sessions.session(w2.newGroup("G"))
        w2.addFriend(s2, "Kabir", 2_000_001)
        w2.addFriend(s2, "Isha", 2_000_002)
        // nobody has paid anything: the plan is empty
        assertEquals("Everyone is settled up.", SettleUpModel.build(s2.view.value).footer)
    }

    @Test fun actionsAreOffUntilPaymentsCanBeRecorded() {
        assertTrue(!SettleUpModel.ACTIONS_ENABLED)
        assertEquals("Recording payments arrives in a later update. For now this is the list of who should pay whom.", SettleUpModel.ACTIONS_NOTE)
    }
}

class HowItWorksTest {
    @Test fun fourCardsAndANote() {
        assertEquals(4, HowItWorks.cards.size)
        assertEquals(listOf("1", "2", "3", "4"), HowItWorks.cards.map { it.number })
        assertTrue(HowItWorks.cards.all { it.title.isNotBlank() && it.body.isNotBlank() })
        assertTrue(HowItWorks.note.body.contains("12-word recovery phrase"))
    }

    @Test fun neverClaimsMoneyIsMovedOrCheckedByTheApp() {
        val text = (HowItWorks.cards.map { it.body } + HowItWorks.note.body).joinToString(" ").lowercase()
        assertTrue("never holds or moves money" in text)
        assertTrue("opens your upi app" !in text, "the app does not open UPI apps yet")
        assertTrue("fewest" !in text, "the plan is a short list, not always the fewest (DESIGN.md)")
    }
}
