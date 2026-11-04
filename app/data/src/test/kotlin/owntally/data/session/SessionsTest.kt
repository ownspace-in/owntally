package owntally.data.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import owntally.crypto.KeyDerivation
import owntally.data.repo.TestEnv
import owntally.data.secrets.SecretLoad
import owntally.model.EventBody
import owntally.model.Money
import owntally.model.Split

class SessionsTest {
    private val env = TestEnv().also { it.firstRun("Asha", "98765 43210") }
    private fun sessions() = Sessions(env.groups, env.secrets, env.identity, env.clock, env.settings)
    private val sessions = sessions()
    private fun rupees(n: Long) = Money(n * 100)

    private fun friend(slot: Long) = KeyDerivation.groupIdentity((env.secrets.loadMasterSeed() as SecretLoad.Found).value, slot)

    @Test
    fun withNoGroupsThereAreNoSummaries() {
        assertEquals(emptyList(), sessions.summaries.value)
    }

    @Test
    fun aNewGroupIsListedWithItsFirstNumbers() {
        val id = sessions.createGroup("Goa Trip", "INR", 2)
        val s = sessions.summaries.value.single()
        assertEquals(id, s.groupId)
        assertEquals("Goa Trip", s.name)
        assertEquals(2, s.colourIndex)
        assertEquals(1, s.memberCount)
        assertEquals(0, s.expenseCount)
        assertEquals(Money.ZERO, s.myBalance)
        assertTrue(s.readable)
    }

    @Test
    fun groupsAreListedInTheOrderTheyWereCreated() {
        env.nowSeconds = 1_800_000_100
        val a = sessions.createGroup("A", "INR", 0)
        env.nowSeconds = 1_800_000_200
        val b = sessions.createGroup("B", "INR", 1)
        assertEquals(listOf(a, b), sessions.summaries.value.map { it.groupId })
    }

    @Test
    fun theSummaryFollowsWhatHappensInAGroupSession() {
        val id = sessions.createGroup("Goa Trip", "INR", 0)
        val session = sessions.session(id)
        val ben = friend(2_000_000)
        session.append(EventBody.MemberJoined(ben.publicKey, ben.encryptionKey, "Ben", "98765 00002"))
        val me = session.view.value.me
        session.addExpense("Hotel", rupees(900), me, 20_000, Split.Equal(setOf(me, ben.publicKey)))
        session.addExpense("Taxi", rupees(200), ben.publicKey, 20_000, Split.Equal(setOf(me, ben.publicKey)))
        val s = sessions.summaries.value.single()
        assertEquals(2, s.memberCount)
        assertEquals(2, s.expenseCount)
        assertEquals(rupees(350), s.myBalance) // paid 900, owes 450 + 100
        val deleted = session.view.value.state.expenses.keys.first()
        session.deleteExpense(deleted)
        assertEquals(1, sessions.summaries.value.single().expenseCount, "a deleted expense is not counted")
    }

    @Test
    fun aRemovedMemberIsNotCounted() {
        val id = sessions.createGroup("Goa Trip", "INR", 0)
        val session = sessions.session(id)
        val ben = friend(2_000_002)
        session.append(EventBody.MemberJoined(ben.publicKey, ben.encryptionKey, "Ben", "98765 00002"))
        assertEquals(2, sessions.summaries.value.single().memberCount)
        val next = owntally.crypto.GroupKey.generate()
        env.secrets.saveGroupKey(id, 2, next)
        val sealed = owntally.crypto.KeyRotation.seal(session.view.value.me, id, 2, next, owntally.crypto.KeyRotation.recipients(session.view.value.state, excluding = setOf(ben.publicKey)))
        session.append(EventBody.MemberRemoved(ben.publicKey, 2, sealed))
        assertEquals(1, sessions.summaries.value.single().memberCount)
    }

    @Test
    fun eachGroupHasItsOwnBalance() {
        val a = sessions.createGroup("A", "INR", 0)
        val b = sessions.createGroup("B", "INR", 1)
        val sa = sessions.session(a)
        val ben = friend(2_000_001)
        sa.append(EventBody.MemberJoined(ben.publicKey, ben.encryptionKey, "Ben", "98765 00002"))
        sa.addExpense("Dinner", rupees(100), sa.view.value.me, 1, Split.Equal(setOf(sa.view.value.me, ben.publicKey)))
        val byId = sessions.summaries.value.associateBy { it.groupId }
        assertEquals(rupees(50), byId.getValue(a).myBalance)
        assertEquals(Money.ZERO, byId.getValue(b).myBalance)
    }

    @Test
    fun theSameSessionIsReturnedEachTime() {
        val id = sessions.createGroup("A", "INR", 0)
        assertSame(sessions.session(id), sessions.session(id))
    }

    @Test
    fun theColourIsKeptAndDefaultsToOneDerivedFromTheGroup() {
        val chosen = sessions.createGroup("A", "INR", 3)
        assertEquals(3, sessions.colourOf(chosen))
        val defaulted = sessions.createGroup("B", "INR", null)
        val c = sessions.colourOf(defaulted)
        assertTrue(c in 0 until GroupLooks.PALETTE_SIZE)
        assertEquals(c, sessions().colourOf(defaulted), "the same every time")
        assertEquals(3, sessions().colourOf(chosen), "kept after a restart")
    }

    @Test
    fun aColourOutsideThePaletteIsRefusedAndNoGroupIsMade() {
        assertFailsWith<IllegalArgumentException> { sessions.createGroup("A", "INR", GroupLooks.PALETTE_SIZE) }
        assertFailsWith<IllegalArgumentException> { sessions.createGroup("A", "INR", -1) }
        assertEquals(emptyList(), env.groups.listGroups())
    }

    @Test
    fun aBadNameIsRefusedAndNoGroupIsMade() {
        assertFailsWith<IllegalArgumentException> { sessions.createGroup("", "INR", 0) }
        assertEquals(emptyList(), sessions.summaries.value)
    }

    @Test
    fun aGroupWhoseKeyIsMissingIsStillListedButMarkedUnreadable() {
        val id = sessions.createGroup("Goa Trip", "INR", 1)
        env.secrets.deleteGroup(id)
        val fresh = sessions().also { it.refresh() }
        val s = fresh.summaries.value.single()
        assertEquals(id, s.groupId)
        assertEquals(false, s.readable)
        assertEquals("", s.name)
        assertEquals(1, s.colourIndex, "the colour is a local setting, still there")
    }

    @Test
    fun aNewSessionsHolderOnTheSameStorageShowsTheSameSummaries() {
        val id = sessions.createGroup("Goa Trip", "INR", 4)
        val s = sessions.session(id)
        s.addExpense("Dinner", rupees(10), s.view.value.me, 1, Split.Equal(setOf(s.view.value.me)))
        assertEquals(sessions.summaries.value, sessions().also { it.refresh() }.summaries.value)
    }

    @Test
    fun constructionReadsNothingSoItIsSafeOnTheMainThread() {
        sessions.createGroup("Goa Trip", "INR", 4)
        val fresh = sessions()
        assertEquals(emptyList(), fresh.summaries.value, "empty until refresh() is called, which is where the database is read")
        fresh.refresh()
        assertEquals(1, fresh.summaries.value.size)
    }

    @Test
    fun refreshPicksUpGroupsMadeBehindItsBack() {
        sessions.createGroup("A", "INR", 0)
        env.groups.createGroup("Made directly")
        sessions.refresh()
        assertEquals(2, sessions.summaries.value.size)
    }

    @Test
    fun theFlowEmitsWhenAGroupChanges() = runBlocking<Unit> {
        val id = sessions.createGroup("A", "INR", 0)
        val seen = mutableListOf<Int>()
        val job = launch(Dispatchers.Unconfined) { sessions.summaries.collect { seen += it.single().expenseCount } }
        val s = sessions.session(id)
        s.addExpense("One", rupees(10), s.view.value.me, 1, Split.Equal(setOf(s.view.value.me)))
        s.addExpense("Two", rupees(10), s.view.value.me, 1, Split.Equal(setOf(s.view.value.me)))
        job.cancel()
        assertEquals(listOf(0, 1, 2), seen)
    }

    @Test
    fun deletingAGroupRemovesItFromTheList() {
        val a = sessions.createGroup("A", "INR", 0)
        val b = sessions.createGroup("B", "INR", 1)
        sessions.deleteGroup(a)
        assertEquals(listOf(b), sessions.summaries.value.map { it.groupId })
        assertEquals(emptyList(), env.groups.envelopes(a).envelopes)
    }


    /**
     * Android backup restores the database (events, group rows, settings) on a new phone, but not the keys, which live in the
     * Keystore and the no-backup folder. The groups must show as waiting for their key, nothing may break, and new groups must not
     * reuse a key slot.
     */
    @Test
    fun aRestoredDatabaseWithoutKeysShowsGroupsWaitingForTheirKey() {
        val oldId = sessions.createGroup("Goa Trip", "INR", 3)
        sessions.session(oldId).let { s -> s.addExpense("Dinner", rupees(10), s.view.value.me, 1, Split.Equal(setOf(s.view.value.me))) }
        val oldSlot = env.groups.listGroups().single().keySlot

        // The new phone: its own Keystore box and secret files, but the old phone's database.
        val phone = TestEnv()
        val identity = owntally.data.repo.IdentityRepository(phone.secrets, env.settings)
        val groups = owntally.data.repo.GroupRepository(phone.secrets, env.events, identity, phone.clock, java.security.SecureRandom())
        assertEquals(owntally.data.repo.IdentityState.NOT_CREATED, identity.state(), "no seed on the new phone, whatever the database holds")
        identity.completeFirstRun(identity.newPhrase(), "Asha", "98765 43210")
        val restored = Sessions(groups, phone.secrets, identity, phone.clock, env.settings)
        restored.refresh()

        val old = restored.summaries.value.single()
        assertEquals(oldId, old.groupId)
        assertEquals(false, old.readable, "waiting for the group key")
        assertEquals(3, old.colourIndex, "the colour setting came back with the database")
        assertEquals(owntally.data.session.AddResult.NoKey, restored.session(oldId).addExpense("x", rupees(1), restored.session(oldId).view.value.me, 1, Split.Equal(setOf(restored.session(oldId).view.value.me))))

        val fresh = restored.createGroup("New", "INR", null)
        assertEquals(2, restored.summaries.value.size)
        val slots = groups.listGroups().map { it.keySlot }
        assertEquals(slots.size, slots.toSet().size)
        assertTrue(groups.listGroups().first { it.groupId == fresh }.keySlot > oldSlot, "a slot is never reused")
        assertEquals(true, restored.summaries.value.first { it.groupId == fresh }.readable)
    }
}
