package owntally.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decision S7 (M5): an event is judged against what its AUTHOR had seen (its `after`), not against the state at its place in the
 * deterministic order, so an event that was valid for its author is valid on every phone, whichever of two concurrent events comes first.
 * The owner is key(1) Asha, then key(2) Ben, key(3) Chitra, key(4) Dev (see [standardGroup]); keys sort in that order.
 */
class ConcurrentRulesTest {
    private val a = key(1)
    private val b = key(2)
    private val c = key(3)
    private val d = key(4)
    private fun sealedFor(keys: Collection<PublicKey>) = keys.associateWith { sealed(it.bytes()[0].toInt()) }

    /** Dev (key 4) becomes the owner, so the owner's events sort AFTER Ben's and Chitra's; Asha is an admin. */
    private fun devOwns(): Sim = standardGroup().also { s ->
        s.emit(a, EventBody.OwnershipOffered(d))
        s.emit(d, EventBody.OwnershipAccepted)
    }

    // ---- a leave and a key change at the same time ---------------------------------------------------------------------

    @Test fun aKeyChangeSealedToSomeoneWhoLeftAtTheSameTimeIsValidWhicheverComesFirst() {
        for (ownerFirst in listOf(true, false)) {
            val s = if (ownerFirst) standardGroup() else devOwns()
            val owner = if (ownerFirst) a else d
            val seen = s.snapshot()
            val members = s.state().currentMembers.keys
            val leave = s.emit(b, EventBody.MemberRemoved(b, 1, emptyMap()), seen)
            val rotate = s.emit(owner, EventBody.KeyRotated(2, sealedFor(members)), seen) // the owner did not know Ben had left
            val st = s.state()
            assertNull(leave.reason(st), "the leave (owner first: $ownerFirst)")
            assertNull(rotate.reason(st), "the key change (owner first: $ownerFirst)")
            assertEquals(2L, st.epoch)
            assertEquals(rotate.id, st.epochStarts[2])
        }
    }

    @Test fun aLeaveWrittenBeforeTheKeyChangeWasSeenKeepsItsOldEpochAndIsValid() {
        val s = standardGroup()
        val seen = s.snapshot()
        s.rotate(a) // epoch 2, written first
        val leave = s.emit(b, EventBody.MemberRemoved(b, 1, emptyMap()), seen)
        assertNull(leave.reason(s.state()))
        assertTrue(b !in s.state().currentMembers)
    }

    @Test fun aLeaveWrittenAfterTheKeyChangeWasSeenMustNameTheNewEpoch() {
        val s = standardGroup()
        s.rotate(a)
        val stale = s.emit(b, EventBody.MemberRemoved(b, 1, emptyMap())) // Ben had seen epoch 2 but names epoch 1
        assertEquals(IgnoreReason.INVALID, stale.reason(s.state()))
        val right = s.emit(c, EventBody.MemberRemoved(c, 2, emptyMap()))
        assertNull(right.reason(s.state()))
    }

    // ---- a join and a key change at the same time ----------------------------------------------------------------------

    @Test fun aKeyChangeThatDoesNotCoverSomeoneAdmittedAtTheSameTimeIsValid() {
        for (adminFirst in listOf(true, false)) {
            val s = if (adminFirst) devOwns() else standardGroup() // admin Chitra (3) sorts before owner Dev (4); owner Asha (1) before Chitra
            val owner = if (adminFirst) d else a
            s.emit(owner, EventBody.AdminAdded(c))
            val seen = s.snapshot()
            val members = s.state().currentMembers.keys
            val join = s.emit(c, EventBody.MemberJoined(key(5), encKeyFor(key(5)), "Eve", PHONE), seen)
            val rotate = s.emit(owner, EventBody.KeyRotated(2, sealedFor(members)), seen) // sealed without Eve: the owner had not seen her admitted
            val st = s.state()
            assertNull(join.reason(st))
            assertNull(rotate.reason(st), "admin first: $adminFirst")
            assertTrue(key(5) in st.currentMembers)
            assertEquals(rotate.id, st.epochStarts[2])
        }
    }

    @Test fun aKeyChangeThatLeavesOutSomeoneTheOwnerKnewIsStillInvalid() {
        val s = standardGroup()
        val st = s.state()
        val bad = s.emit(a, EventBody.KeyRotated(2, sealedFor(st.currentMembers.keys - c)))
        assertEquals(IgnoreReason.INVALID, bad.reason(s.state()))
        val extra = s.emit(a, EventBody.KeyRotated(2, sealedFor(st.currentMembers.keys + key(9))))
        assertEquals(IgnoreReason.INVALID, extra.reason(s.state()), "someone who was never a member")
    }

    @Test fun aKeyChangeSealedToSomeoneTheOwnerHadSeenRemovedIsInvalid() {
        val s = standardGroup()
        s.remove(a, b)
        val st = s.state()
        val bad = s.emit(a, EventBody.KeyRotated(st.epoch + 1, sealedFor(st.currentMembers.keys + b)))
        assertEquals(IgnoreReason.INVALID, bad.reason(s.state()))
    }

    // ---- a removal of someone who has already gone -----------------------------------------------------------------------

    @Test fun anOwnersRemovalOfSomeoneWhoLeftAtTheSameTimeStillStartsTheNewKey() {
        for (ownerFirst in listOf(true, false)) {
            val s = if (ownerFirst) standardGroup() else devOwns()
            val owner = if (ownerFirst) a else d
            val seen = s.snapshot()
            val members = s.state().currentMembers.keys
            val leave = s.emit(b, EventBody.MemberRemoved(b, 1, emptyMap()), seen)
            val removal = s.emit(owner, EventBody.MemberRemoved(b, 2, sealedFor(members - b)), seen)
            val st = s.state()
            // Whichever is applied first wins: the owner's removal cancels a leave of Ben's it had not seen (AFTER_REMOVAL), and a leave
            // applied first leaves the removal to start the new key. Either way the removal is valid and Ben is gone.
            assertTrue(leave.reason(st) == null || leave.reason(st) == IgnoreReason.AFTER_REMOVAL, "owner first: $ownerFirst")
            assertNull(removal.reason(st), "owner first: $ownerFirst")
            assertEquals(2L, st.epoch)
            assertEquals(removal.id, st.epochStarts[2])
            assertEquals(1, st.removals.size, "Ben is removed once")
            assertEquals(true, st.members.getValue(b).removed)
        }
    }

    @Test fun aLaterKeyChangeThatLeavesOutSomeoneTheOwnerRemovedIsValidEvenIfTheirOwnLeaveWasAppliedFirst() {
        for (ownerFirst in listOf(true, false)) {
            val s = if (ownerFirst) standardGroup() else devOwns()
            val owner = if (ownerFirst) a else d
            val seen = s.snapshot()
            val members = s.state().currentMembers.keys
            s.emit(b, EventBody.MemberRemoved(b, 1, emptyMap()), seen) // Ben leaves, unseen by the owner
            s.emit(owner, EventBody.MemberRemoved(b, 2, sealedFor(members - b)), seen) // the owner removes Ben: epoch 2, not sealed to him
            // The owner has seen its own removal of Ben but not his leave; the next key change leaves Ben out, as the owner knows.
            val next = s.emit(owner, EventBody.KeyRotated(3, sealedFor(members - b)), seen = s.snapshot().filterKeys { it != b })
            val st = s.state()
            assertNull(next.reason(st), "owner first: $ownerFirst")
            assertEquals(3L, st.epoch)
        }
    }

    @Test fun removingSomeoneTheOwnerAlreadySawGoIsStillRefused() {
        val s = standardGroup()
        s.remove(a, b)
        val again = s.emit(a, EventBody.MemberRemoved(b, 3, sealedFor(s.state().currentMembers.keys)))
        assertEquals(IgnoreReason.UNKNOWN_TARGET, again.reason(s.state()))
    }

    // ---- entries that name someone who left at the same time ---------------------------------------------------------------

    @Test fun anExpenseNamingSomeoneWhoLeftAtTheSameTimeIsValidAndTheyAreTakenOutOfIt() {
        val s = standardGroup()
        val seen = s.snapshot()
        val leave = s.emit(b, EventBody.MemberRemoved(b, 1, emptyMap()), seen)
        val shared = s.emit(c, EventBody.ExpenseAdded("Lunch", Money(30_000), a, 20_000, Split.Equal(setOf(a, b, c))), seen)
        val paidByBen = s.emit(d, EventBody.ExpenseAdded("Taxi", Money(9_000), b, 20_000, Split.Equal(setOf(b, d))), seen)
        val st = s.state()
        assertNull(leave.reason(st))
        assertNull(shared.reason(st))
        assertNull(paidByBen.reason(st))
        // Ben is taken out as if never part of it: Lunch is shared by Asha and Chitra; what Ben paid does not count.
        assertEquals(mapOf(a to Money(15_000), c to Money(15_000)), sharesOf(st, st.expenses.getValue(shared.id)))
        assertNull(sharesOf(st, st.expenses.getValue(paidByBen.id)))
    }

    @Test fun anExpenseNamingSomeoneWhoHadAlreadyGoneForTheAuthorIsStillInvalid() {
        val s = standardGroup()
        s.remove(a, b)
        val x = s.emit(c, EventBody.ExpenseAdded("Lunch", Money(30_000), a, 20_000, Split.Equal(setOf(a, b, c))))
        assertEquals(IgnoreReason.INVALID, x.reason(s.state()))
    }

    @Test fun aPaymentNamingSomeoneWhoLeftAtTheSameTimeIsValid() {
        val s = standardGroup()
        val seen = s.snapshot()
        s.emit(b, EventBody.MemberRemoved(b, 1, emptyMap()), seen)
        val pay = s.emit(c, EventBody.SettlementRecorded(c, b, Money(5_000)), seen)
        assertNull(pay.reason(s.state()))
    }

    @Test fun anExpenseNamingSomeoneNotYetAdmittedForTheAuthorIsInvalidAndAnAdmittedOneIsNot() {
        val s = standardGroup()
        val seen = s.snapshot()
        val join = s.emit(a, EventBody.MemberJoined(key(5), encKeyFor(key(5)), "Eve", PHONE), seen)
        val unaware = s.emit(c, EventBody.ExpenseAdded("Lunch", Money(10_000), a, 20_000, Split.Equal(setOf(a, key(5)))), seen)
        assertNull(join.reason(s.state()))
        assertEquals(IgnoreReason.INVALID, unaware.reason(s.state()), "Chitra had not seen Eve admitted")
        val aware = s.emit(c, EventBody.ExpenseAdded("Lunch 2", Money(10_000), a, 20_000, Split.Equal(setOf(a, key(5)))))
        assertNotNull(s.state().expenses[aware.id])
    }

    // ---- the result never depends on the order ------------------------------------------------------------------------------

    @Test fun theStateIsTheSameWhateverOrderTheEventsArriveIn() {
        val s = devOwns()
        val seen = s.snapshot()
        val members = s.state().currentMembers.keys
        s.emit(b, EventBody.MemberRemoved(b, 1, emptyMap()), seen)
        s.emit(d, EventBody.KeyRotated(2, sealedFor(members)), seen)
        s.emit(c, EventBody.ExpenseAdded("x", Money(100), b, 20_000, Split.Equal(setOf(b, c))), seen)
        val expected = s.state()
        repeat(20) { n -> assertEquals(expected, replay(logOf(s.events.shuffled(kotlin.random.Random(n))))) }
    }
}
