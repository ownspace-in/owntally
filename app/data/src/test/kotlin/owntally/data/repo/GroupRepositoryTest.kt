package owntally.data.repo

import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals(listOf(first, second).map { owntally.crypto.KeySlots.creator(owntally.model.GroupIds.randomPart(it)) }, list.map { it.keySlot }, "the slot is made from the group's id (M11), not counted")
        assertEquals(listOf(1_800_000_100L, 1_800_000_200L), list.map { it.createdAtSeconds })
    }

    @Test
    fun eachGroupUsesItsOwnSlotSoTheMemberKeyIsDifferentPerGroup() {
        val a = env.groups.createGroup("A")
        val b = env.groups.createGroup("B")
        assertNotEquals(state(a).owner, state(b).owner)
        val seed = (env.secrets.loadMasterSeed() as owntally.data.secrets.SecretLoad.Found).value
        assertEquals(KeyDerivation.groupIdentity(seed, owntally.crypto.KeySlots.creator(owntally.model.GroupIds.randomPart(a))).publicKey, state(a).owner)
        assertEquals(KeyDerivation.groupIdentity(seed, owntally.crypto.KeySlots.creator(owntally.model.GroupIds.randomPart(b))).publicKey, state(b).owner)
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
        // Nothing was used up: the next group is made from its own id as usual.
        val id = env.groups.createGroup("Goa Trip")
        assertEquals(owntally.crypto.KeySlots.creator(owntally.model.GroupIds.randomPart(id)), env.groups.listGroups().single { it.groupId == id }.keySlot)
    }

    @Test
    fun groupsCreatedAtTheSameTimeGetDifferentSlots() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val ids = (1..16).map { n -> pool.submit<GroupId> { env.groups.createGroup("Group $n") } }.map { it.get() }
            assertEquals(16, ids.toSet().size)
            assertEquals(16, env.groups.listGroups().map { it.keySlot }.toSet().size, "sixteen different keys")
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
    fun aDeletedGroupsKeyIsNotGivenToANewGroup() {
        // The same member key in two groups would let anyone who was in both link them. A slot made from a random group id does not repeat.
        val a = env.groups.createGroup("A")
        val b = env.groups.createGroup("B")
        val keyOfB = state(b).owner
        env.groups.deleteGroup(b)
        val c = env.groups.createGroup("C")
        assertNotEquals(keyOfB, state(c).owner)
        env.groups.deleteGroup(a)
        val d = env.groups.createGroup("D")
        assertNotEquals(state(c).owner, state(d).owner)
    }

    @Test
    fun aPhoneRestoredFromTheSamePhraseFindsTheCreatorsKeyFromTheGroupIdAlone() {
        val phrase = owntally.crypto.Bip39.fromEntropy(ByteArray(16) { 4 })
        val old = TestEnv().also { it.identity.completeFirstRun(phrase, "Asha", "98765 43210") }
        val id = old.groups.createGroup("Goa Trip")
        val creatorKey = old.identity.groupIdentity(old.groups.group(id)!!.keySlot)!!.publicKey
        // A new phone with the same phrase and nothing else: id in, key out, and the id vouches for it.
        val restored = TestEnv().also { it.identity.completeFirstRun(phrase, "Asha", "98765 43210") }
        val found = restored.identity.groupIdentity(owntally.crypto.KeySlots.creator(owntally.model.GroupIds.randomPart(id)))!!
        assertEquals(creatorKey, found.publicKey)
        assertTrue(owntally.model.GroupIds.isCreatedBy(id, found.publicKey))
        // Another person's phrase does not.
        val other = TestEnv().also { it.identity.completeFirstRun(owntally.crypto.Bip39.fromEntropy(ByteArray(16) { 5 }), "Eve", "98765 43211") }
        assertFalse(owntally.model.GroupIds.isCreatedBy(id, other.identity.groupIdentity(owntally.crypto.KeySlots.creator(owntally.model.GroupIds.randomPart(id)))!!.publicKey))
    }

    @Test
    fun noCounterIsKeptAnyMore() {
        env.groups.createGroup("A")
        assertNull(env.settings.get("next_slot"))
    }

    @Test
    fun aGroupThatIsNotThereHasNoEnvelopes() {
        assertEquals(emptyList(), env.groups.envelopes(GroupId(ByteArray(16) { 9 })).envelopes)
        assertNull(env.groups.group(GroupId(ByteArray(16) { 9 })))
    }


    @Test
    fun theGroupIdIsMadeForItsCreatorSoNobodyElseCanCreateIt() {
        val id = env.groups.createGroup("Goa Trip")
        val me = env.identity.groupIdentity(env.groups.group(id)!!.keySlot)!!
        assertTrue(owntally.model.GroupIds.isCreatedBy(id, me.publicKey))
        assertFalse(owntally.model.GroupIds.isCreatedBy(id, owntally.model.PublicKey(ByteArray(32) { 5 })))
        assertNotEquals(id, env.groups.createGroup("Another"))
    }
}
