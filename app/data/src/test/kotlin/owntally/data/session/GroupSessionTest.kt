package owntally.data.session

import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import owntally.crypto.EventSealing
import owntally.crypto.GroupIdentity
import owntally.crypto.GroupKey
import owntally.crypto.OpenResult
import owntally.data.repo.AppendResult
import owntally.data.repo.TestEnv
import owntally.model.Envelope
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.EventId
import owntally.model.EventLog
import owntally.model.GroupId
import owntally.model.IgnoreReason
import owntally.model.Money
import owntally.model.Payment
import owntally.model.PublicKey
import owntally.model.Split
import owntally.model.balances
import owntally.model.replay
import owntally.model.settlementPlan

class GroupSessionTest {
    private val env = TestEnv().also { it.firstRun("Asha", "98765 43210") }
    private val group: GroupId = env.groups.createGroup("Goa Trip")
    private val session = newSession()
    private val seed = (env.secrets.loadMasterSeed() as owntally.data.secrets.SecretLoad.Found).value
    private val ben = owntally.crypto.KeyDerivation.groupIdentity(seed, 100)
    private val chitra = owntally.crypto.KeyDerivation.groupIdentity(seed, 101)
    private val outsider = owntally.crypto.KeyDerivation.groupIdentity(seed, 102)
    private val owner get() = session.view.value.me

    private fun newSession() = GroupSession(group, env.groups, env.secrets, env.identity, env.clock)
    private fun rupees(n: Long) = Money(n * 100)

    private fun join(who: GroupIdentity, name: String) =
        assertAdded(session.append(EventBody.MemberJoined(who.publicKey, who.encryptionKey, name, "98765 00000")))

    private fun assertAdded(r: AddResult): EventId = (r as AddResult.Added).id

    /** An event written on another phone and received here: sealed by that member and stored, then the session refreshed. */
    private var memberCounters = mutableMapOf<PublicKey, Long>()

    private fun receiveFrom(who: GroupIdentity, body: EventBody, epoch: Long = 1, key: GroupKey? = null): Envelope {
        val ring = env.secrets.loadKeyring(group).keyring
        val log = EventLog().also { l -> env.groups.envelopes(group).envelopes.forEach { e -> (EventSealing.open(e, ring) as? OpenResult.Opened)?.let { l.append(it.event) } } }
        val counter = (memberCounters[who.publicKey] ?: 0L) + 1
        memberCounters[who.publicKey] = counter
        val after = log.summary().toMutableMap().also { if (counter > 1) it[who.publicKey] = counter - 1 else it.remove(who.publicKey) }
        val event = Event(1, who.publicKey, counter, after, 9, body)
        val envelope = EventSealing.seal(who, group, epoch, key ?: ring[epoch]!!, event)
        assertEquals(AppendResult.Added, env.groups.appendEnvelope(group, envelope))
        session.refresh()
        return envelope
    }

    private fun expense(paidBy: PublicKey, total: Money, vararg split: PublicKey, description: String = "Dinner") =
        session.addExpense(description, total, paidBy, 20_000, Split.Equal(split.toSet()))

    // --- the state ---------------------------------------------------------------------------

    @Test
    fun aNewSessionShowsTheGroupAsCreated() {
        val v = session.view.value
        assertEquals("Goa Trip", v.state.name)
        assertEquals(v.me, v.state.owner)
        assertEquals(mapOf(v.me to Money.ZERO), v.balances)
        assertEquals(emptyList(), v.plan)
        assertEquals(Counts(opened = 2, waitingForKey = emptySet(), unreadable = 0, damaged = 0), v.counts)
    }

    @Test
    fun theLocalUserIsTheOwnerWithTheDefaultProfile() {
        val v = session.view.value
        val me = v.state.members.getValue(v.me)
        assertEquals("Asha", me.name)
        assertEquals(owntally.model.Role.OWNER, me.role)
    }

    // --- adding and deleting expenses give the core's balances -----------------------------------

    @Test
    fun addedExpensesGiveTheBalancesOfTheCore() {
        join(ben, "Ben")
        join(chitra, "Chitra")
        val me = owner
        assertAdded(expense(me, rupees(900), me, ben.publicKey, chitra.publicKey, description = "Hotel"))
        assertAdded(expense(ben.publicKey, rupees(600), me, ben.publicKey, chitra.publicKey, description = "Taxi"))
        val v = session.view.value
        // Hand calculation (rupees): Hotel 300 each, Taxi 200 each. Asha +900 -300 -200 = +400, Ben +600 -300 -200 = +100, Chitra -500.
        assertEquals(rupees(400), v.balances.getValue(me))
        assertEquals(rupees(100), v.balances.getValue(ben.publicKey))
        assertEquals(-rupees(500), v.balances.getValue(chitra.publicKey))
        assertEquals(listOf(Payment(chitra.publicKey, me, rupees(400)), Payment(chitra.publicKey, ben.publicKey, rupees(100))), v.plan)
    }

    @Test
    fun theViewEqualsWhatTheCoreComputesFromTheStoredEvents() {
        join(ben, "Ben")
        assertAdded(expense(owner, rupees(1001), owner, ben.publicKey))
        val ring = env.secrets.loadKeyring(group).keyring
        val log = EventLog().also { l -> env.groups.envelopes(group).envelopes.forEach { e -> l.append((EventSealing.open(e, ring) as OpenResult.Opened).event) } }
        val state = replay(log)
        assertEquals(state, session.view.value.state)
        assertEquals(balances(state), session.view.value.balances)
        assertEquals(settlementPlan(balances(state)), session.view.value.plan)
    }

    @Test
    fun deletingAnExpenseTakesItOutOfTheBalancesButKeepsItInTheHistory() {
        join(ben, "Ben")
        val id = assertAdded(expense(owner, rupees(900), owner, ben.publicKey))
        assertEquals(rupees(450), session.view.value.balances.getValue(owner))
        assertAdded(session.deleteExpense(id))
        val v = session.view.value
        assertEquals(Money.ZERO, v.balances.getValue(owner))
        assertEquals(emptyList(), v.plan)
        assertTrue(v.state.expenses.getValue(id).deleted)
        assertEquals(1, v.state.expenses.size)
    }

    @Test
    fun eachNewEventGetsTheNextCounterAndSeesEverythingBefore() {
        join(ben, "Ben")
        assertAdded(expense(owner, rupees(10), owner))
        assertAdded(expense(owner, rupees(20), owner))
        val ring = env.secrets.loadKeyring(group).keyring
        val mine = env.groups.envelopes(group).envelopes.map { (EventSealing.open(it, ring) as OpenResult.Opened).event }.filter { it.author == owner }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), mine.map { it.counter })
        assertEquals(emptyList(), session.view.value.state.ignored)
        assertEquals(setOf(owner), mine.last().after.keys)
        assertEquals(4L, mine.last().after.getValue(owner))
    }

    // --- the core's rules decide, before anything is stored ----------------------------------------

    @Test
    fun anExpenseNamingSomeoneWhoIsNotAMemberIsRefusedAndNothingIsStored() {
        val before = env.groups.envelopes(group).envelopes.size
        assertEquals(AddResult.Refused(IgnoreReason.INVALID), expense(owner, rupees(100), owner, outsider.publicKey))
        assertEquals(AddResult.Refused(IgnoreReason.INVALID), expense(outsider.publicKey, rupees(100), owner))
        assertEquals(before, env.groups.envelopes(group).envelopes.size)
        assertEquals(0, session.view.value.state.expenses.size)
    }

    @Test
    fun deletingSomethingThatDoesNotExistIsRefused() {
        assertEquals(AddResult.Refused(IgnoreReason.UNKNOWN_TARGET), session.deleteExpense(EventId(owner, 99)))
    }

    @Test
    fun deletingTwiceIsRefusedTheSecondTime() {
        val id = assertAdded(expense(owner, rupees(10), owner))
        assertAdded(session.deleteExpense(id))
        assertEquals(AddResult.Refused(IgnoreReason.ALREADY_DONE), session.deleteExpense(id))
    }

    @Test
    fun aPlainMemberCannotAdminOnlyThings() {
        join(ben, "Ben")
        // Ben's own phone would refuse this, and here too: the owner's session will not write an event the core would ignore.
        val benEnvelope = receiveFrom(ben, EventBody.ExpenseAdded("Ben's", rupees(50), ben.publicKey, 1, Split.Equal(setOf(ben.publicKey))))
        assertEquals(1, session.view.value.state.expenses.size)
        val benExpense = EventId(ben.publicKey, benEnvelope.counter)
        // The owner is an admin and may delete Ben's entry.
        assertAdded(session.deleteExpense(benExpense))
        // A plain member deleting the owner's entry is ignored by the core when it arrives.
        val ownersExpense = assertAdded(expense(owner, rupees(10), owner))
        receiveFrom(ben, EventBody.ExpenseDeleted(ownersExpense))
        val v = session.view.value
        assertEquals(IgnoreReason.NOT_ALLOWED, v.state.ignored.single { it.id.author == ben.publicKey && it.id.counter == 2L }.reason)
        assertEquals(false, v.state.expenses.getValue(ownersExpense).deleted)
    }

    @Test
    fun anInvalidExpenseThrowsBeforeAnythingIsTouched() {
        assertFailsWith<IllegalArgumentException> { session.addExpense("", rupees(10), owner, 1, Split.Equal(setOf(owner))) }
        assertFailsWith<IllegalArgumentException> { session.addExpense("x", Money.ZERO, owner, 1, Split.Equal(setOf(owner))) }
        assertEquals(2, env.groups.envelopes(group).envelopes.size)
    }

    // --- envelopes that cannot be opened ---------------------------------------------------------

    @Test
    fun damagedStoredBytesAreCountedAndHidden() {
        env.events.addRawForTest(group, ByteArray(30) { 7 })
        session.refresh()
        assertEquals(1, session.view.value.counts.damaged)
        assertEquals(2, session.view.value.counts.opened)
    }

    @Test
    fun anEnvelopeFromAnEpochWhoseKeyIsNotHereIsKeptAndShownAsWaiting() {
        join(ben, "Ben")
        val epoch2Key = GroupKey.generate()
        receiveFrom(ben, EventBody.ExpenseAdded("Later", rupees(70), ben.publicKey, 1, Split.Equal(setOf(ben.publicKey))), epoch = 2, key = epoch2Key)
        val v = session.view.value
        assertEquals(setOf(2L), v.counts.waitingForKey)
        assertEquals(0, v.state.expenses.size, "not shown")
        assertEquals(4, env.groups.envelopes(group).envelopes.size, "but kept")
    }

    @Test
    fun whenTheKeyArrivesTheWaitingEnvelopeIsOpenedAndApplied() {
        join(ben, "Ben")
        val epoch2Key = GroupKey.generate()
        receiveFrom(ben, EventBody.ExpenseAdded("Later", rupees(70), ben.publicKey, 1, Split.Equal(setOf(ben.publicKey, owner))), epoch = 2, key = epoch2Key)
        assertEquals(0, session.view.value.state.expenses.size, "still waiting")
        env.secrets.saveGroupKey(group, 2, epoch2Key)
        session.refresh()
        val v = session.view.value
        assertEquals(emptySet(), v.counts.waitingForKey)
        assertEquals(1, v.state.expenses.size)
        assertEquals(rupees(35), v.balances.getValue(ben.publicKey)) // paid 70, own share 35
        assertEquals(-rupees(35), v.balances.getValue(owner))
    }

    @Test
    fun anEnvelopeSealedWithTheWrongKeyIsKeptAndCountedAsUnreadable() {
        join(ben, "Ben")
        receiveFrom(ben, EventBody.ProfileUpdated("Benny", "98765 11111"), epoch = 1, key = GroupKey.generate()) // right epoch, wrong key
        val v = session.view.value
        assertEquals(1, v.counts.unreadable)
        assertEquals("Ben", v.state.members.getValue(ben.publicKey).name, "the unreadable update changed nothing")
        assertEquals(4, env.groups.envelopes(group).envelopes.size)
    }

    @Test
    fun unreadableThingsAreCountedOnceNotAgainOnEveryRefresh() {
        join(ben, "Ben")
        receiveFrom(ben, EventBody.ProfileUpdated("Benny", "98765 11111"), epoch = 1, key = GroupKey.generate())
        repeat(5) { session.refresh() }
        assertEquals(1, session.view.value.counts.unreadable)
    }

    @Test
    fun withoutTheKeyOfTheCurrentEpochNothingNewCanBeWritten() {
        env.secrets.deleteGroup(group) // for example the Keystore key was lost
        val fresh = newSession()
        assertEquals(0, fresh.view.value.counts.opened)
        assertEquals(setOf(1L), fresh.view.value.counts.waitingForKey)
        assertEquals(AddResult.NoKey, fresh.addExpense("Dinner", rupees(10), PublicKey(ByteArray(32)), 1, Split.Equal(setOf(PublicKey(ByteArray(32))))))
        assertEquals(2, env.groups.envelopes(group).envelopes.size)
    }

    // --- keys of later epochs ----------------------------------------------------------------------

    private fun rotate(): GroupKey {
        val next = GroupKey.generate()
        env.secrets.saveGroupKey(group, 2, next) // the person who rotates already holds the new key
        val sealed = owntally.crypto.KeyRotation.seal(group, 2, next, owntally.crypto.KeyRotation.recipients(session.view.value.state))
        assertAdded(session.append(EventBody.KeyRotated(2, sealed)))
        return next
    }

    @Test
    fun afterARotationNewEventsAreSealedWithTheNewKeyOnly() {
        val k2 = rotate()
        assertEquals(2L, session.view.value.state.epoch)
        assertAdded(expense(owner, rupees(10), owner))
        val last = env.groups.envelopes(group).envelopes.last()
        assertEquals(2L, last.epoch)
        val ring1 = env.secrets.loadKeyring(group).keyring.let { r -> owntally.crypto.GroupKeyring(group).also { it.add(1, r[1]!!) } }
        val ring2 = owntally.crypto.GroupKeyring(group).also { it.add(2, k2) }
        assertTrue(EventSealing.open(last, ring2) is OpenResult.Opened)
        assertEquals(OpenResult.NeedKey(2), EventSealing.open(last, ring1), "someone with only the old key cannot read it")
    }

    @Test
    fun ifTheCurrentEpochsKeyIsMissingNothingIsWrittenEvenThoughTheOldKeyIsStillThere() {
        rotate()
        env.blobs.delete("key-${group.toHex()}-2")
        val before = env.groups.envelopes(group).envelopes.size
        assertEquals(AddResult.NoKey, expense(owner, rupees(10), owner))
        assertEquals(before, env.groups.envelopes(group).envelopes.size)
        assertEquals(setOf(1L), env.secrets.loadKeyring(group).keyring.epochs())
    }

    // --- restart and flow ------------------------------------------------------------------------

    @Test
    fun aNewSessionOnTheSameStorageShowsTheSameState() {
        join(ben, "Ben")
        join(chitra, "Chitra")
        assertAdded(expense(owner, rupees(1000), owner, ben.publicKey, chitra.publicKey))
        val id = assertAdded(expense(ben.publicKey, rupees(300), owner, ben.publicKey))
        assertAdded(session.deleteExpense(id))
        val restarted = newSession()
        assertEquals(session.view.value, restarted.view.value)
        assertEquals(session.view.value.balances, restarted.view.value.balances)
        assertEquals(session.view.value.plan, restarted.view.value.plan)
    }

    @Test
    fun theRestartedSessionContinuesTheCounters() {
        assertAdded(expense(owner, rupees(10), owner))
        val restarted = newSession()
        assertAdded(restarted.addExpense("More", rupees(20), owner, 1, Split.Equal(setOf(owner))))
        assertEquals(emptyList(), restarted.view.value.state.ignored)
        assertEquals(2, restarted.view.value.state.expenses.size)
    }

    @Test
    fun theFlowEmitsEachNewState() = runBlocking<Unit> {
        val seen = mutableListOf<Int>()
        val job = launch(Dispatchers.Unconfined) { session.view.collect { seen += it.state.expenses.size } }
        assertAdded(expense(owner, rupees(10), owner))
        assertAdded(expense(owner, rupees(20), owner))
        job.cancel()
        assertEquals(listOf(0, 1, 2), seen)
    }

    @Test
    fun manyThreadsAddingTogetherAllGetDifferentCountersAndAllLand() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..24).map { n -> pool.submit<AddResult> { expense(owner, rupees(n.toLong()), owner, description = "E$n") } }.map { it.get() }
            assertTrue(results.all { it is AddResult.Added })
            val v = session.view.value
            assertEquals(24, v.state.expenses.size)
            assertEquals(emptyList(), v.state.ignored)
            assertEquals(26L, v.state.expenses.keys.maxOf { it.counter })
        } finally {
            pool.shutdown()
        }
    }
}
