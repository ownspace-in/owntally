package owntally.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ReplayTest {
    private val a = key(1)
    private val b = key(2)
    private val c = key(3)
    private val d = key(4)
    private val e = key(5)
    private val all = Split.Equal(setOf(a, b, c, d))

    private fun Sim.reasonOf(event: Event) = event.reason(state())

    // --- group and members -------------------------------------------------------------------

    @Test
    fun creatorBecomesOwnerAndMembersJoin() {
        val state = standardGroup().state()
        assertEquals(a, state.owner)
        assertEquals("Goa Trip", state.name)
        assertEquals(Role.OWNER, state.members.getValue(a).role)
        assertEquals("Asha", state.members.getValue(a).name)
        assertEquals(setOf(a, b, c, d), state.currentMembers.keys)
        assertEquals(emptyList(), state.ignored)
    }

    @Test
    fun aSecondGroupCreatedIsIgnored() {
        val s = standardGroup()
        val second = s.emit(b, EventBody.GroupCreated(GROUP, "Other", "INR", ENC), seen = emptyMap())
        assertEquals(IgnoreReason.ALREADY_DONE, s.reasonOf(second))
        assertEquals("Goa Trip", s.state().name)
        // One that has seen the group's events cannot be a creation at all.
        val seenSome = s.emit(c, EventBody.GroupCreated(GROUP, "Third", "INR", ENC))
        assertEquals(IgnoreReason.INVALID, s.reasonOf(seenSome))
    }

    @Test
    fun groupCreatedMustBeTheFirstEventWithNothingSeen() {
        val s = Sim()
        s.emit(a, EventBody.ProfileUpdated("x", PHONE))
        val late = s.emit(a, EventBody.GroupCreated(GROUP, "Late", "INR", ENC))
        assertEquals(IgnoreReason.INVALID, s.reasonOf(late))
        assertNull(s.state().owner)
    }

    @Test
    fun ownerAndAdminCanAdmitMembersButPlainMembersCannot() {
        val s = standardGroup()
        s.emit(a, EventBody.AdminAdded(b))
        val byAdmin = s.join(b, e, "Eve")
        val byMember = s.join(c, key(6), "Fay")
        val state = s.state()
        assertTrue(e in state.currentMembers)
        assertNull(byAdmin.reason(state))
        assertEquals(IgnoreReason.NOT_ALLOWED, byMember.reason(state))
        assertTrue(key(6) !in state.members)
    }

    @Test
    fun admittingACurrentMemberOrAFormerMemberIsRejected() {
        val s = standardGroup()
        val again = s.join(a, b, "Ben again")
        s.remove(a, c)
        val rejoin = s.join(a, c, "Chitra again")
        val state = s.state()
        assertEquals(IgnoreReason.ALREADY_DONE, again.reason(state))
        assertEquals(IgnoreReason.NOT_ALLOWED, rejoin.reason(state))
        assertTrue(state.members.getValue(c).removed)
    }

    @Test
    fun aGroupHasAtMostTwentyMembers() {
        val s = Sim()
        s.emit(a, EventBody.GroupCreated(GROUP, "Big", "INR", ENC))
        val joins = (2..21).map { s.join(a, key(it), "M$it") }
        val state = s.state()
        assertEquals(20, state.currentMembers.size)
        assertNull(joins[18].reason(state)) // member number 20
        assertEquals(IgnoreReason.NOT_ALLOWED, joins[19].reason(state))
    }

    @Test
    fun aMemberCanUpdateTheirOwnProfile() {
        val s = standardGroup()
        s.emit(b, EventBody.ProfileUpdated("Benny", "99999 11111"))
        assertEquals("Benny", s.state().members.getValue(b).name)
        assertEquals("99999 11111", s.state().members.getValue(b).phone)
    }

    @Test
    fun peopleOutsideTheGroupCannotWrite() {
        val s = standardGroup()
        val outsider = s.expense(e, rupees(10), e, Split.Equal(setOf(e)))
        assertEquals(IgnoreReason.NOT_MEMBER, s.reasonOf(outsider))
    }

    // --- expenses ----------------------------------------------------------------------------

    @Test
    fun anyMemberAddsAnExpenseWithMemberPayerAndParticipants() {
        val s = standardGroup()
        val ok = s.expense(c, rupees(900), a, all)
        val state = s.state()
        assertNull(ok.reason(state))
        assertEquals(rupees(900), state.expenses.getValue(ok.id).total)
        assertEquals(c, state.expenses.getValue(ok.id).creator)
    }

    @Test
    fun anExpenseNamingANonMemberIsRejected() {
        val s = standardGroup()
        val badPayer = s.expense(a, rupees(10), e, Split.Equal(setOf(a)))
        val badParticipant = s.expense(a, rupees(10), a, Split.Equal(setOf(a, e)))
        assertEquals(IgnoreReason.INVALID, s.reasonOf(badPayer))
        assertEquals(IgnoreReason.INVALID, s.reasonOf(badParticipant))
        assertTrue(s.state().expenses.isEmpty())
    }

    @Test
    fun creatorOrAdminCanDeleteButOtherMembersCannot() {
        val s = standardGroup()
        val mine = s.expense(b, rupees(10), b, all)
        val other = s.expense(b, rupees(20), b, all)
        val third = s.expense(b, rupees(30), b, all)
        val byOther = s.emit(c, EventBody.ExpenseDeleted(mine.id))
        val byCreator = s.emit(b, EventBody.ExpenseDeleted(mine.id))
        val byOwner = s.emit(a, EventBody.ExpenseDeleted(other.id))
        val state = s.state()
        assertEquals(IgnoreReason.NOT_ALLOWED, byOther.reason(state))
        assertNull(byCreator.reason(state))
        assertNull(byOwner.reason(state))
        assertTrue(state.expenses.getValue(mine.id).deleted)
        assertEquals(b, state.expenses.getValue(mine.id).deletedBy)
        assertTrue(state.expenses.getValue(other.id).deleted)
        assertTrue(!state.expenses.getValue(third.id).deleted)
        assertEquals(3, state.expenses.size) // deleted entries stay in history
    }

    @Test
    fun deletingTwiceOrAnUnknownTargetIsIgnored() {
        val s = standardGroup()
        val x = s.expense(a, rupees(10), a, all)
        s.emit(a, EventBody.ExpenseDeleted(x.id))
        val twice = s.emit(a, EventBody.ExpenseDeleted(x.id))
        val unknown = s.emit(a, EventBody.ExpenseDeleted(EventId(d, 40)))
        assertEquals(IgnoreReason.ALREADY_DONE, s.reasonOf(twice))
        assertEquals(IgnoreReason.UNKNOWN_TARGET, s.reasonOf(unknown))
    }

    // --- settlements -------------------------------------------------------------------------

    @Test
    fun aPaymentRecordedByThePayerIsMarkedPaidAndByTheReceiverIsConfirmed() {
        val s = standardGroup()
        val byPayer = s.emit(c, EventBody.SettlementRecorded(c, a, rupees(400)))
        val byReceiver = s.emit(a, EventBody.SettlementRecorded(b, a, rupees(100)))
        val state = s.state()
        assertEquals(SettlementStatus.PAID_MARKED, state.settlements.getValue(byPayer.id).status)
        assertEquals(SettlementStatus.CONFIRMED, state.settlements.getValue(byReceiver.id).status)
    }

    @Test
    fun aThirdPartyOrANonMemberCannotRecordAPayment() {
        val s = standardGroup()
        val third = s.emit(d, EventBody.SettlementRecorded(c, a, rupees(5)))
        val toOutsider = s.emit(c, EventBody.SettlementRecorded(c, e, rupees(5)))
        assertEquals(IgnoreReason.NOT_ALLOWED, s.reasonOf(third))
        assertEquals(IgnoreReason.INVALID, s.reasonOf(toOutsider))
        assertTrue(s.state().settlements.isEmpty())
    }

    @Test
    fun onlyTheReceiverConfirmsOrDisputes() {
        val s = standardGroup()
        val pay = s.emit(c, EventBody.SettlementRecorded(c, a, rupees(400)))
        val confirmByPayer = s.emit(c, EventBody.SettlementConfirmed(pay.id))
        val disputeByPayer = s.emit(c, EventBody.PaymentDisputed(pay.id, DisputeReason.NOTHING_RECEIVED))
        val disputeByReceiver = s.emit(a, EventBody.PaymentDisputed(pay.id, DisputeReason.DIFFERENT_AMOUNT))
        val state = s.state()
        assertEquals(IgnoreReason.NOT_ALLOWED, confirmByPayer.reason(state))
        assertEquals(IgnoreReason.NOT_ALLOWED, disputeByPayer.reason(state))
        assertNull(disputeByReceiver.reason(state))
        assertEquals(SettlementStatus.DISPUTED, state.settlements.getValue(pay.id).status)
        assertEquals(DisputeReason.DIFFERENT_AMOUNT, state.settlements.getValue(pay.id).disputeReason)
    }

    @Test
    fun aDisputedPaymentCanStillBeConfirmedAndAConfirmedOneCannotBeDisputed() {
        val s = standardGroup()
        val pay = s.emit(c, EventBody.SettlementRecorded(c, a, rupees(400)))
        s.emit(a, EventBody.PaymentDisputed(pay.id, DisputeReason.NOT_CHECKED))
        val confirm = s.emit(a, EventBody.SettlementConfirmed(pay.id))
        val disputeAgain = s.emit(a, EventBody.PaymentDisputed(pay.id, DisputeReason.NOT_CHECKED))
        val confirmAgain = s.emit(a, EventBody.SettlementConfirmed(pay.id))
        val state = s.state()
        assertNull(confirm.reason(state))
        assertEquals(IgnoreReason.ALREADY_DONE, disputeAgain.reason(state))
        assertEquals(IgnoreReason.ALREADY_DONE, confirmAgain.reason(state))
        assertEquals(SettlementStatus.CONFIRMED, state.settlements.getValue(pay.id).status)
    }

    // --- roles and ownership -----------------------------------------------------------------

    @Test
    fun onlyTheOwnerAddsAndRemovesAdmins() {
        val s = standardGroup()
        val add = s.emit(a, EventBody.AdminAdded(b))
        val byAdmin = s.emit(b, EventBody.AdminAdded(c))
        val byMember = s.emit(d, EventBody.AdminAdded(d))
        val removeByAdmin = s.emit(b, EventBody.AdminRemoved(b))
        val state = s.state()
        assertNull(add.reason(state))
        assertEquals(IgnoreReason.NOT_ALLOWED, byAdmin.reason(state))
        assertEquals(IgnoreReason.NOT_ALLOWED, byMember.reason(state))
        assertEquals(IgnoreReason.NOT_ALLOWED, removeByAdmin.reason(state))
        assertEquals(Role.ADMIN, state.members.getValue(b).role)
        s.emit(a, EventBody.AdminRemoved(b))
        assertEquals(Role.MEMBER, s.state().members.getValue(b).role)
    }

    @Test
    fun adminRulesRejectWrongTargets() {
        val s = standardGroup()
        val notAMember = s.emit(a, EventBody.AdminAdded(e))
        val removeFromMember = s.emit(a, EventBody.AdminRemoved(b))
        s.emit(a, EventBody.AdminAdded(b))
        val addAgain = s.emit(a, EventBody.AdminAdded(b))
        val removeOwner = s.emit(a, EventBody.AdminRemoved(a))
        val state = s.state()
        assertEquals(IgnoreReason.UNKNOWN_TARGET, notAMember.reason(state))
        assertEquals(IgnoreReason.ALREADY_DONE, removeFromMember.reason(state))
        assertEquals(IgnoreReason.ALREADY_DONE, addAgain.reason(state))
        assertEquals(IgnoreReason.NOT_ALLOWED, removeOwner.reason(state))
    }

    @Test
    fun ownershipMovesOnlyWhenOfferedByTheOwnerAndAcceptedByTheNewOwner() {
        val s = standardGroup()
        val offer = s.emit(a, EventBody.OwnershipOffered(b))
        val wrongAccept = s.emit(c, EventBody.OwnershipAccepted)
        assertEquals(a, s.state().owner)
        assertEquals(b, s.state().pendingOffer)
        val accept = s.emit(b, EventBody.OwnershipAccepted)
        val state = s.state()
        assertNull(offer.reason(state))
        assertEquals(IgnoreReason.NOT_ALLOWED, wrongAccept.reason(state))
        assertNull(accept.reason(state))
        assertEquals(b, state.owner)
        assertEquals(Role.OWNER, state.members.getValue(b).role)
        assertEquals(Role.ADMIN, state.members.getValue(a).role)
        assertNull(state.pendingOffer)
    }

    @Test
    fun onlyTheOwnerCanOfferAndTheOfferGoesToAnotherCurrentMember() {
        val s = standardGroup()
        s.emit(a, EventBody.AdminAdded(b))
        val byAdmin = s.emit(b, EventBody.OwnershipOffered(c))
        val toSelf = s.emit(a, EventBody.OwnershipOffered(a))
        val toOutsider = s.emit(a, EventBody.OwnershipOffered(e))
        val acceptNothing = s.emit(c, EventBody.OwnershipAccepted)
        val state = s.state()
        assertEquals(IgnoreReason.NOT_ALLOWED, byAdmin.reason(state))
        assertEquals(IgnoreReason.INVALID, toSelf.reason(state))
        assertEquals(IgnoreReason.INVALID, toOutsider.reason(state))
        assertEquals(IgnoreReason.NOT_ALLOWED, acceptNothing.reason(state))
        assertEquals(a, state.owner)
    }

    @Test
    fun aNewOfferReplacesTheOldAndTheOwnerCanCancel() {
        val s = standardGroup()
        s.emit(a, EventBody.OwnershipOffered(b))
        s.emit(a, EventBody.OwnershipOffered(c))
        assertEquals(c, s.state().pendingOffer)
        val staleAccept = s.emit(b, EventBody.OwnershipAccepted)
        assertEquals(IgnoreReason.NOT_ALLOWED, s.reasonOf(staleAccept))
        s.emit(a, EventBody.OwnershipOfferCancelled)
        assertNull(s.state().pendingOffer)
        val cancelAgain = s.emit(a, EventBody.OwnershipOfferCancelled)
        assertEquals(IgnoreReason.ALREADY_DONE, s.reasonOf(cancelAgain))
    }

    // --- removal -----------------------------------------------------------------------------

    @Test
    fun ownerCanRemoveAnyoneButThemselves() {
        val s = standardGroup()
        s.emit(a, EventBody.AdminAdded(b))
        val removeAdmin = s.remove(a, b)
        val removeMember = s.remove(a, c)
        val removeSelf = s.remove(a, a)
        val state = s.state()
        assertNull(removeAdmin.reason(state))
        assertNull(removeMember.reason(state))
        assertEquals(IgnoreReason.NOT_ALLOWED, removeSelf.reason(state))
        assertEquals(setOf(a, d), state.currentMembers.keys)
    }

    @Test
    fun adminsRemoveMembersButNotOtherAdminsOrTheOwner() {
        val s = standardGroup()
        s.emit(a, EventBody.AdminAdded(b))
        s.emit(a, EventBody.AdminAdded(c))
        val removeMember = s.remove(b, d)
        val removeAdmin = s.remove(b, c)
        val removeOwner = s.remove(b, a)
        val state = s.state()
        assertNull(removeMember.reason(state))
        assertEquals(IgnoreReason.NOT_ALLOWED, removeAdmin.reason(state))
        assertEquals(IgnoreReason.NOT_ALLOWED, removeOwner.reason(state))
        assertEquals(setOf(a, b, c), state.currentMembers.keys)
    }

    @Test
    fun membersCanLeaveButNotRemoveOthersAndTheOwnerCannotLeave() {
        val s = standardGroup()
        val removeOther = s.remove(b, c)
        val leave = s.remove(b, b)
        val ownerLeaves = s.remove(a, a)
        val removeStranger = s.remove(a, e)
        val state = s.state()
        assertEquals(IgnoreReason.NOT_ALLOWED, removeOther.reason(state))
        assertNull(leave.reason(state))
        assertEquals(IgnoreReason.NOT_ALLOWED, ownerLeaves.reason(state))
        assertEquals(IgnoreReason.UNKNOWN_TARGET, removeStranger.reason(state))
        assertEquals(a, state.owner)
        assertTrue(a in state.currentMembers)
    }

    @Test
    fun removalClearsAPendingOfferToThatMember() {
        val s = standardGroup()
        s.emit(a, EventBody.OwnershipOffered(b))
        s.remove(a, b)
        assertNull(s.state().pendingOffer)
    }

    @Test
    fun eventsWrittenAfterTheRemovalAreIgnored() {
        val s = standardGroup()
        s.expense(c, rupees(100), c, all)
        s.remove(a, c)
        val later = s.expense(c, rupees(500), c, all)
        val state = s.state()
        assertEquals(IgnoreReason.AFTER_REMOVAL, later.reason(state))
        assertEquals(1, state.expenses.size)
    }

    @Test
    fun eventsTheRemoverHadSeenStayAndNewerOnesAreIgnoredEvenIfOrderedBeforeTheRemoval() {
        val s = standardGroup()
        s.emit(a, EventBody.AdminAdded(d))
        val kept = s.expense(c, rupees(100), c, all)
        val newer = s.expense(c, rupees(500), c, all)
        // d removes c having seen only c's first expense. d's key sorts after c's, so c's newer event is
        // applied first and must be cancelled afterwards.
        val removal = s.remove(d, c, seen = s.snapshot().toMutableMap().also { it[c] = kept.counter })
        val order = s.log().applicationOrder().map { it.id }
        assertTrue(order.indexOf(newer.id) < order.indexOf(removal.id), "scenario must put the newer event first")
        val state = s.state()
        assertNull(removal.reason(state))
        assertNull(kept.reason(state))
        assertEquals(IgnoreReason.AFTER_REMOVAL, newer.reason(state))
        assertTrue(kept.id in state.expenses)
        assertTrue(newer.id !in state.expenses)
        assertEquals(kept.counter, state.removals.getValue(c).lastSeenCounter)
    }

    @Test
    fun aRemovedMembersHistoryStaysInTheState() {
        val s = standardGroup()
        val x = s.expense(c, rupees(100), c, all)
        s.remove(a, c)
        val state = s.state()
        assertTrue(x.id in state.expenses)
        assertEquals("Chitra", state.members.getValue(c).name)
        assertEquals(setOf(c), state.removals.keys)
    }

    // --- key epochs (docs/CRYPTO.md section 6) -------------------------------------------------

    private fun keysFor(vararg members: PublicKey) = members.associateWith { sealed(it.bytes()[0].toInt()) }

    @Test
    fun theFirstEpochIsOneAndMembersKeepTheirEncryptionKeys() {
        val state = standardGroup().state()
        assertEquals(1L, state.epoch)
        assertEquals(encKeyFor(a), state.members.getValue(a).encryptionKey)
        assertEquals(encKeyFor(c), state.members.getValue(c).encryptionKey)
    }

    @Test
    fun removingSomeoneStartsTheNextEpochWithAKeyForEachRemainingMember() {
        val s = standardGroup()
        val removal = s.emit(a, EventBody.MemberRemoved(c, 2, keysFor(a, b, d)))
        val state = s.state()
        assertNull(removal.reason(state))
        assertEquals(2L, state.epoch)
        assertTrue(state.members.getValue(c).removed)
    }

    @Test
    fun aRemovalWithTheWrongEpochOrTheWrongKeysIsInvalidAndChangesNothing() {
        val s = standardGroup()
        val sameEpoch = s.emit(a, EventBody.MemberRemoved(c, 1, keysFor(a, b, d)))
        val skipsAnEpoch = s.emit(a, EventBody.MemberRemoved(c, 3, keysFor(a, b, d)))
        val missingMember = s.emit(a, EventBody.MemberRemoved(c, 2, keysFor(a, b)))
        val includesRemoved = s.emit(a, EventBody.MemberRemoved(c, 2, keysFor(a, b, c, d)))
        val includesStranger = s.emit(a, EventBody.MemberRemoved(c, 2, keysFor(a, b, d, e)))
        val noKeys = s.emit(a, EventBody.MemberRemoved(c, 2, emptyMap()))
        val state = s.state()
        for (bad in listOf(sameEpoch, skipsAnEpoch, missingMember, includesRemoved, includesStranger, noKeys)) {
            assertEquals(IgnoreReason.INVALID, bad.reason(state), bad.toString())
        }
        assertEquals(1L, state.epoch)
        assertEquals(setOf(a, b, c, d), state.currentMembers.keys)
    }

    @Test
    fun aLeaveKeepsTheEpochAndCarriesNoKeys() {
        val s = standardGroup()
        val leave = s.emit(c, EventBody.MemberRemoved(c, 1, emptyMap()))
        val state = s.state()
        assertNull(leave.reason(state))
        assertEquals(1L, state.epoch)
        assertTrue(state.members.getValue(c).removed)
    }

    @Test
    fun aLeaveThatTriesToRotateOrCarriesKeysIsInvalid() {
        val s = standardGroup()
        val rotates = s.emit(c, EventBody.MemberRemoved(c, 2, keysFor(a, b, d)))
        val keysOnly = s.emit(c, EventBody.MemberRemoved(c, 1, keysFor(a)))
        val state = s.state()
        assertEquals(IgnoreReason.INVALID, rotates.reason(state))
        assertEquals(IgnoreReason.INVALID, keysOnly.reason(state))
        assertTrue(c in state.currentMembers)
    }

    @Test
    fun anAdminRotatesTheKeyAfterALeave() {
        val s = standardGroup()
        s.remove(c, c)
        val rotate = s.rotate(a)
        val state = s.state()
        assertNull(rotate.reason(state))
        assertEquals(2L, state.epoch)
        assertEquals(setOf(a, b, d), state.currentMembers.keys)
    }

    @Test
    fun onlyAnAdminCanRotateAndOnlyToTheNextEpochForExactlyTheCurrentMembers() {
        val s = standardGroup()
        s.emit(a, EventBody.AdminAdded(b))
        val byMember = s.emit(c, EventBody.KeyRotated(2, keysFor(a, b, c, d)))
        val byAdmin = s.emit(b, EventBody.KeyRotated(2, keysFor(a, b, c, d)))
        val repeated = s.emit(b, EventBody.KeyRotated(2, keysFor(a, b, c, d)))
        val missing = s.emit(a, EventBody.KeyRotated(3, keysFor(a, b, c)))
        val stranger = s.emit(a, EventBody.KeyRotated(3, keysFor(a, b, c, d, e)))
        val state = s.state()
        assertEquals(IgnoreReason.NOT_ALLOWED, byMember.reason(state))
        assertNull(byAdmin.reason(state))
        assertEquals(IgnoreReason.INVALID, repeated.reason(state))
        assertEquals(IgnoreReason.INVALID, missing.reason(state))
        assertEquals(IgnoreReason.INVALID, stranger.reason(state))
        assertEquals(2L, state.epoch)
    }

    @Test
    fun theStateRecordsWhichEventStartedEachEpoch() {
        val s = standardGroup()
        assertEquals(emptyMap(), s.state().epochStarts)
        val removal = s.remove(a, c)
        val rotation = s.rotate(a)
        val ignoredRotation = s.emit(b, EventBody.KeyRotated(4, keysFor(a, b, d)))
        val state = s.state()
        assertEquals(mapOf(2L to removal.id, 3L to rotation.id), state.epochStarts)
        assertEquals(IgnoreReason.NOT_ALLOWED, ignoredRotation.reason(state))
        // A leave starts no epoch.
        val t = standardGroup()
        t.remove(b, b)
        assertEquals(emptyMap(), t.state().epochStarts)
    }

    @Test
    fun aRotationAfterARemovalMovesToEpochThree() {
        val s = standardGroup()
        s.remove(a, c)
        s.rotate(a)
        assertEquals(3L, s.state().epoch)
    }

    @Test
    fun twoRemovalsWrittenAtTheSameTimeLeaveOnlyTheFirstInOrderAndTheOtherMustBeReissued() {
        val s = standardGroup()
        s.emit(a, EventBody.AdminAdded(b))
        val seen = s.snapshot()
        // Both admins prepare their removal from the same state: epoch 2, keys for everyone left.
        val first = s.emit(a, EventBody.MemberRemoved(c, 2, keysFor(a, b, d)), seen)
        val second = s.emit(b, EventBody.MemberRemoved(d, 2, keysFor(a, b, c)), seen)
        val state = s.state()
        assertNull(first.reason(state))
        assertEquals(IgnoreReason.INVALID, second.reason(state))
        assertEquals(setOf(a, b, d), state.currentMembers.keys)
        assertEquals(2L, state.epoch)
    }

    // --- odd input ---------------------------------------------------------------------------

    @Test
    fun unknownTypesAndNewerVersionsAreKeptWithNoEffect() {
        val s = standardGroup()
        val unknown = s.emit(a, EventBody.Unknown(200, ByteArray(0)))
        val newer = s.emit(a, EventBody.ProfileUpdated("Changed", PHONE), version = 2)
        val after = s.emit(a, EventBody.ProfileUpdated("Final", PHONE))
        val state = s.state()
        assertEquals(IgnoreReason.UNKNOWN_TYPE, unknown.reason(state))
        assertEquals(IgnoreReason.UNKNOWN_TYPE, newer.reason(state))
        assertNull(after.reason(state))
        assertEquals("Final", state.members.getValue(a).name)
    }

    @Test
    fun twoDifferentEventsWithTheSameIdAreBothIgnored() {
        val s = standardGroup()
        val real = s.expense(b, rupees(100), b, all)
        val forged = Event(1, b, real.counter, real.after, real.time, EventBody.ExpenseAdded("Forged", rupees(999), b, 1, all))
        val state = replay(logOf(s.events + forged))
        assertEquals(IgnoreReason.DUPLICATE_ID, state.ignored.single { it.id == real.id }.reason)
        assertTrue(real.id !in state.expenses)
    }

    @Test
    fun eventsWithMissingDependenciesAreHeldBackAndDoNothing() {
        val s = standardGroup()
        val x = s.expense(b, rupees(100), b, all)
        val withoutJoinOfBen = s.events.filter { !(it.author == a && it.counter == 3L) }
        val state = replay(logOf(withoutJoinOfBen))
        assertTrue(x.id in state.heldBack)
        assertTrue(x.id !in state.expenses)
        val full = replay(s.log())
        assertEquals(emptyList(), full.heldBack)
        assertTrue(x.id in full.expenses)
    }

    // --- order does not matter ---------------------------------------------------------------

    @Test
    fun replayingInAnyArrivalOrderGivesTheSameState() = runBlocking<Unit> {
        checkAll(1000, Arb.long()) { seed ->
            val events = randomScenario(Random(seed))
            val reference = replay(logOf(events))
            assertEquals(reference, replay(logOf(events.shuffled(Random(seed + 7)))))
        }
    }

    @Test
    fun theRandomScenariosActuallyExerciseAcceptedAndRejectedEvents() {
        var accepted = 0
        var rejected = 0
        var removals = 0
        for (seed in 1L..200L) {
            val state = replay(logOf(randomScenario(Random(seed))))
            rejected += state.ignored.size
            removals += state.removals.size
            accepted += state.expenses.size + state.settlements.size
        }
        assertTrue(accepted > 200, "accepted $accepted")
        assertTrue(rejected > 200, "rejected $rejected")
        assertTrue(removals > 20, "removals $removals")
    }
}
