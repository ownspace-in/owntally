package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import owntally.model.BeforeAfter
import owntally.model.EventBody
import owntally.model.PublicKey
import owntally.model.balances
import owntally.model.format.BalanceKind
import owntally.model.format.MoneyFormat
import owntally.model.previewRemoval

class RemoveMemberModelTest {
    private val w = TestWorld()
    private val s = w.sessions.session(w.newGroup("Goa Trip"))
    private val me get() = s.view.value.me
    private val rohan = w.addFriend(s, "Rohan", 1)
    private val meera = w.addFriend(s, "Meera", 2)
    private val kabir = w.addFriend(s, "Kabir", 3)

    private fun model(target: PublicKey = kabir) = RemoveMemberModels.build(s.view.value, target)!!

    @Test fun theTableEqualsThePreviewRowForRowWithYouFirstAndTheRemovedPersonLast() {
        w.expense(s, "Dinner", w.rupees(1200), me, me, rohan, meera, kabir)
        w.expense(s, "Taxi", w.rupees(400), kabir, me, rohan, kabir)
        val preview = previewRemoval(s.view.value.state, kabir)
        val m = model()
        assertEquals(listOf("You", "Meera", "Rohan", "Kabir"), m.rows.map { it.label })
        val byKey = preview.changes.associateBy { it.member }
        for (row in m.rows.dropLast(1)) {
            val c: BeforeAfter = byKey.getValue(row.key)
            assertEquals(MoneyFormat.signed(c.before), row.now)
            assertEquals(MoneyFormat.signed(c.after), row.after)
            assertEquals(MoneyFormat.describe(c.after).kind, row.afterKind)
        }
        val last = m.rows.last()
        assertEquals(MoneyFormat.signed(balances(s.view.value.state).getValue(kabir)), last.now)
        assertEquals("Removed", last.after)
        assertEquals(preview.changes.size + 1, m.rows.size, "everyone who stays, plus the person removed")
    }

    @Test fun theWordsDoNotGuessAPronoun() {
        val m = model()
        assertEquals("Remove Kabir?", m.title)
        assertEquals(
            listOf(
                "Kabir will no longer see or add expenses. The group will switch to a new key so they can't read new entries.",
                "The group is recalculated as if Kabir had never joined. Kabir's entries stay in the history but don't count.",
            ),
            m.intro,
        )
        assertEquals("Remove Kabir", m.button)
    }

    @Test fun theBoxCountsWhatIsLeftOut() {
        w.expense(s, "Dinner", w.rupees(900), kabir, me, rohan, kabir)
        w.expense(s, "Lunch", w.rupees(300), kabir, kabir, rohan)
        w.expense(s, "Hotel", w.rupees(600), me, me, rohan, kabir)
        w.expense(s, "Cab", w.rupees(80), me, me, rohan)
        val preview = previewRemoval(s.view.value.state, kabir)
        assertEquals(2, preview.excludedExpenses.size)
        assertEquals(1, preview.resplitExpenses.size)
        val box = model().alsoLeftOut!!
        assertEquals("2 expenses Kabir paid, Kabir's share of 1 other", box.summary)
    }

    @Test fun thePaymentLinesNameWhoPaidWhomAndWhoWillOweAgain() {
        w.expense(s, "Dinner", w.rupees(900), me, me, kabir)
        s.append(EventBody.SettlementRecorded(kabir, me, w.rupees(900)))
        s.append(EventBody.SettlementRecorded(me, kabir, w.rupees(50)))
        s.append(EventBody.SettlementRecorded(me, meera, w.rupees(70))) // not involving Kabir: not listed
        val lines = model().alsoLeftOut!!.payments
        assertEquals(
            listOf(
                "Kabir paid You ₹900. It no longer counts.",
                "You paid Kabir ₹50. You will owe that amount again.",
            ),
            lines,
        )
    }

    @Test fun whenNothingIsLeftOutThereIsNoBox() {
        assertNull(model().alsoLeftOut)
    }

    @Test fun anAlreadyRemovedOrUnknownPersonHasNoScreen() {
        s.removeMember(kabir)
        assertNull(RemoveMemberModels.build(s.view.value, kabir))
        assertNull(RemoveMemberModels.build(s.view.value, PublicKey(ByteArray(32) { 9 })))
    }

    @Test fun onlyTheOwnerGetsTheScreenAndNeverForThemselves() {
        assertNull(RemoveMemberModels.build(s.view.value, me))
        assertNull(RemoveMemberModels.build(s.view.value.copy(me = rohan), kabir), "a member")
        s.makeAdmin(rohan)
        assertNull(RemoveMemberModels.build(s.view.value.copy(me = rohan), kabir), "an admin")
    }

    @Test fun afterRemovingTheBalancesAreWhatTheTableSaid() {
        w.expense(s, "Dinner", w.rupees(1200), me, me, rohan, meera, kabir)
        val said = model().rows.dropLast(1).associate { it.key to it.after }
        s.removeMember(kabir)
        val now = s.view.value.balances.mapValues { MoneyFormat.signed(it.value) }
        for ((k, v) in said) assertEquals(v, now.getValue(k))
        assertEquals(BalanceKind.OWED, MoneyFormat.describe(s.view.value.balances.getValue(me)).kind)
    }
}
