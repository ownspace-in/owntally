package owntally.data.repo

import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.crypto.EnvelopeSigning
import owntally.crypto.EventSealing
import owntally.crypto.KeyDerivation
import owntally.crypto.OpenResult
import owntally.data.secrets.AeadSecretBox
import owntally.data.secrets.SecretBox
import owntally.data.secrets.SecretStoreException
import owntally.data.secrets.newAead
import owntally.model.Envelope
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.EventId
import owntally.model.EventLog
import owntally.model.GroupId
import owntally.model.IgnoreReason
import owntally.model.Money
import owntally.model.Role
import owntally.model.Split
import owntally.model.replay

class GroupRepositoryTest {
    private val env = TestEnv().also { it.firstRun("Asha", "98765 43210") }

    /** Decrypts a group's stored envelopes with its stored keys and replays them. */
    private fun state(group: GroupId) = replay(
        EventLog().also { log ->
            val ring = env.secrets.loadKeyring(group).keyring
            for (e in env.groups.envelopes(group).envelopes) (EventSealing.open(e, ring) as OpenResult.Opened).let { log.append(it.event) }
        },
    )

    @Test
    fun aNewGroupHasItsFirstTwoEventsAndTheCreatorAsOwner() {
        val id = env.groups.createGroup("Goa Trip")
        val stored = env.groups.envelopes(id)
        assertEquals(2, stored.envelopes.size)
        assertEquals(0, stored.damaged)
        val state = state(id)
        assertEquals("Goa Trip", state.name)
        assertEquals("INR", state.currency)
        assertEquals(1L, state.epoch)
        val owner = state.members.getValue(state.owner!!)
        assertEquals(Role.OWNER, owner.role)
        assertEquals("Asha", owner.name)
        assertEquals("98765 43210", owner.phone)
        assertEquals(emptyList(), state.ignored)
    }

    @Test
    fun theGroupIsListedWithItsSlotAndTime() {
        env.nowSeconds = 1_800_000_100
        val first = env.groups.createGroup("Goa Trip")
        env.nowSeconds = 1_800_000_200
        val second = env.groups.createGroup("Flat 4B")
        val list = env.groups.listGroups()
        assertEquals(listOf(first, second), list.map { it.groupId })
        assertEquals(listOf(0L, 1L), list.map { it.keySlot })
        assertEquals(listOf(1_800_000_100L, 1_800_000_200L), list.map { it.createdAtSeconds })
    }

    @Test
    fun eachGroupUsesItsOwnSlotSoTheMemberKeyIsDifferentPerGroup() {
        val a = env.groups.createGroup("A")
        val b = env.groups.createGroup("B")
        assertNotEquals(state(a).owner, state(b).owner)
        val seed = (env.secrets.loadMasterSeed() as owntally.data.secrets.SecretLoad.Found).value
        assertEquals(KeyDerivation.groupIdentity(seed, 0).publicKey, state(a).owner)
        assertEquals(KeyDerivation.groupIdentity(seed, 1).publicKey, state(b).owner)
    }

    @Test
    fun theFirstGroupKeyIsSavedForEpochOne() {
        val id = env.groups.createGroup("Goa Trip")
        val ring = env.secrets.loadKeyring(id)
        assertEquals(setOf(1L), ring.keyring.epochs())
        assertEquals(emptySet(), ring.unreadableEpochs)
    }

    @Test
    fun theStoredEventsAreEncryptedNotReadable() {
        env.groups.createGroup("Secret Trip Name")
        val raw = env.events.rawEnvelopes().joinToString(" ") { String(it, Charsets.ISO_8859_1) }
        assertTrue("Secret Trip Name" !in raw && "98765 43210" !in raw)
    }

    @Test
    fun noGroupCanBeMadeBeforeFirstRun() {
        val fresh = TestEnv()
        assertFailsWith<IllegalStateException> { fresh.groups.createGroup("Goa Trip") }
        assertEquals(emptyList(), fresh.groups.listGroups())
    }

    @Test
    fun noGroupCanBeMadeWhenTheSeedCannotBeOpened() {
        val other = GroupRepository(env.secrets, env.events, IdentityRepository(owntally.data.secrets.BoxedSecretStore(AeadSecretBox(newAead()), env.blobs), env.settings), env.clock, java.security.SecureRandom())
        assertFailsWith<IllegalStateException> { other.createGroup("Goa Trip") }
    }

    @Test
    fun aBadNameOrCurrencyStoresNothing() {
        assertFailsWith<IllegalArgumentException> { env.groups.createGroup("") }
        assertFailsWith<IllegalArgumentException> { env.groups.createGroup("x".repeat(41)) }
        assertFailsWith<IllegalArgumentException> { env.groups.createGroup("Trip", "inr") }
        assertEquals(emptyList(), env.groups.listGroups())
        assertEquals(emptyList(), env.blobs.list("key-"))
    }

    @Test
    fun ifTheKeyCannotBeSavedNoGroupIsStored() {
        val broken = TestEnv().also { it.firstRun() }
        val repo = GroupRepository(
            owntally.data.secrets.BoxedSecretStore(object : SecretBox {
                override fun seal(plain: ByteArray, context: ByteArray): ByteArray = throw SecretStoreException("keystore unavailable")
                override fun open(sealed: ByteArray, context: ByteArray): ByteArray? = null
            }, broken.blobs),
            broken.events, broken.identity, broken.clock, java.security.SecureRandom(),
        )
        assertFailsWith<Exception> { repo.createGroup("Goa Trip") }
        assertEquals(emptyList(), broken.events.groups())
    }

    @Test
    fun ifTheDatabaseFailsTheKeyIsRemovedAgain() {
        env.events.failNextAddGroup = true
        assertFailsWith<IllegalStateException> { env.groups.createGroup("Goa Trip") }
        assertEquals(emptyList(), env.groups.listGroups())
        assertEquals(emptyList(), env.blobs.list("key-"), "no orphan key is left behind")
        // The failed attempt used up slot 0 (never reused, see below), so the next group takes slot 1.
        val id = env.groups.createGroup("Goa Trip")
        assertEquals(1L, env.groups.listGroups().single { it.groupId == id }.keySlot)
    }

    @Test
    fun groupsCreatedAtTheSameTimeGetDifferentSlots() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val ids = (1..16).map { n -> pool.submit<GroupId> { env.groups.createGroup("Group $n") } }.map { it.get() }
            assertEquals(16, ids.toSet().size)
            assertEquals((0L..15L).toList(), env.groups.listGroups().map { it.keySlot }.sorted())
        } finally {
            pool.shutdown()
        }
    }

    // --- appending ---------------------------------------------------------------------------

    private fun ownerOf(group: GroupId) = env.identity.groupIdentity(env.groups.listGroups().single { it.groupId == group }.keySlot)!!

    private fun expense(group: GroupId, counter: Long, text: String = "Dinner"): Envelope {
        val id = ownerOf(group)
        val key = env.secrets.loadKeyring(group).keyring[1]!!
        val event = Event(1, id.publicKey, counter, mapOf(id.publicKey to counter - 1), 5, EventBody.ExpenseAdded(text, Money(60000), id.publicKey, 20000, Split.Equal(setOf(id.publicKey))))
        return EventSealing.seal(id, group, 1, key, event)
    }

    @Test
    fun anOwnEventIsAddedAndAnIdenticalOneIsNotAddedTwice() {
        val group = env.groups.createGroup("Goa Trip")
        val e = expense(group, 3)
        assertEquals(AppendResult.Added, env.groups.appendEnvelope(group, e))
        assertEquals(AppendResult.Duplicate, env.groups.appendEnvelope(group, e))
        assertEquals(3, env.groups.envelopes(group).envelopes.size)
        assertEquals(1, state(group).expenses.size)
    }

    @Test
    fun storedEnvelopesComeBackInTheOrderTheyWereAdded() {
        val group = env.groups.createGroup("Goa Trip")
        val e3 = expense(group, 3, "One")
        val e4 = expense(group, 4, "Two")
        env.groups.appendEnvelope(group, e3)
        env.groups.appendEnvelope(group, e4)
        assertEquals(listOf(1L, 2L, 3L, 4L), env.groups.envelopes(group).envelopes.map { it.counter })
    }

    @Test
    fun anEnvelopeForAnotherGroupOrAnUnknownGroupIsRejected() {
        val a = env.groups.createGroup("A")
        val b = env.groups.createGroup("B")
        assertEquals(AppendResult.Rejected(RejectedBecause.WRONG_GROUP), env.groups.appendEnvelope(a, expense(b, 3)))
        assertEquals(AppendResult.Rejected(RejectedBecause.UNKNOWN_GROUP), env.groups.appendEnvelope(GroupId(ByteArray(16) { 1 }), expense(b, 3)))
        assertEquals(2, env.groups.envelopes(a).envelopes.size)
    }

    @Test
    fun anEnvelopeWithABadSignatureIsRejected() {
        val group = env.groups.createGroup("Goa Trip")
        val good = expense(group, 3)
        val bent = Envelope(1, group, 1, good.author, 3, good.nonce(), good.ciphertext().also { it[0] = (it[0].toInt() xor 1).toByte() }, good.signature())
        assertEquals(AppendResult.Rejected(RejectedBecause.BAD_SIGNATURE), env.groups.appendEnvelope(group, bent))
        assertEquals(2, env.groups.envelopes(group).envelopes.size)
    }

    @Test
    fun anEnvelopeThatIsTooLargeIsRejected() {
        val group = env.groups.createGroup("Goa Trip")
        val id = ownerOf(group)
        val huge = EnvelopeSigning.sign(id, 1, group, 1, 3, ByteArray(24), ByteArray(GroupRepository.MAX_ENVELOPE_BYTES))
        assertEquals(AppendResult.Rejected(RejectedBecause.TOO_LARGE), env.groups.appendEnvelope(group, huge))
    }

    @Test
    fun twoDifferentEnvelopesWithTheSameIdAreBothKeptAndReplayIgnoresBoth() {
        val group = env.groups.createGroup("Goa Trip")
        val one = expense(group, 3, "Real")
        val two = expense(group, 3, "Forged")
        assertEquals(AppendResult.Added, env.groups.appendEnvelope(group, one))
        assertEquals(AppendResult.Added, env.groups.appendEnvelope(group, two))
        assertEquals(4, env.groups.envelopes(group).envelopes.size)
        val st = state(group)
        assertEquals(IgnoreReason.DUPLICATE_ID, st.ignored.first { it.id == EventId(ownerOf(group).publicKey, 3) }.reason)
        assertEquals(0, st.expenses.size)
    }

    @Test
    fun damagedStoredBytesAreCountedAndTheRestStillComesBack() {
        val group = env.groups.createGroup("Goa Trip")
        env.events.addRawForTest(group, ByteArray(30) { 7 })
        val stored = env.groups.envelopes(group)
        assertEquals(2, stored.envelopes.size)
        assertEquals(1, stored.damaged)
    }

    // --- deleting ----------------------------------------------------------------------------

    @Test
    fun deletingAGroupRemovesItsEventsAndKeysAndLeavesOthers() {
        val a = env.groups.createGroup("A")
        val b = env.groups.createGroup("B")
        env.groups.deleteGroup(a)
        assertEquals(listOf(b), env.groups.listGroups().map { it.groupId })
        assertEquals(emptyList(), env.groups.envelopes(a).envelopes)
        assertEquals(emptySet(), env.secrets.loadKeyring(a).keyring.epochs())
        assertEquals(setOf(1L), env.secrets.loadKeyring(b).keyring.epochs())
        assertEquals(2, env.groups.envelopes(b).envelopes.size)
    }

    @Test
    fun aDeletedGroupsSlotIsNeverReusedEvenIfItWasTheHighest() {
        // A reused slot would give the new group the same member key as the deleted one, and anyone who was in both could link them.
        val a = env.groups.createGroup("A")
        val b = env.groups.createGroup("B")
        val keyOfB = state(b).owner
        env.groups.deleteGroup(b) // the highest slot
        val c = env.groups.createGroup("C")
        assertEquals(2L, env.groups.listGroups().single { it.groupId == c }.keySlot)
        assertNotEquals(keyOfB, state(c).owner)
        env.groups.deleteGroup(a)
        val d = env.groups.createGroup("D")
        assertEquals(3L, env.groups.listGroups().single { it.groupId == d }.keySlot)
    }

    @Test
    fun aGroupThatIsNotThereHasNoEnvelopes() {
        assertEquals(emptyList(), env.groups.envelopes(GroupId(ByteArray(16) { 9 })).envelopes)
        assertNull(env.groups.group(GroupId(ByteArray(16) { 9 })))
    }

}
