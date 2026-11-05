package owntally.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import owntally.model.EncryptionKey
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.EventLog
import owntally.model.GroupId
import owntally.model.GroupIds
import owntally.model.IgnoreReason
import owntally.model.Money
import owntally.model.PublicKey
import owntally.model.SealedKey
import owntally.model.Split
import owntally.model.replay

/** docs/CRYPTO.md section 6: a new group key per epoch, sealed to each remaining member with HPKE. */
class KeyRotationTest {
    private fun String.hex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val vectors: JsonObject = Json.parseToJsonElement(KeyRotationTest::class.java.getResource("/crypto-vectors.json")!!.readText()).jsonObject
    private val seed = MasterSeed(vectors.getValue("seed").jsonPrimitive.content.hex())

    // Four members of one group (they share a seed here only to keep the test short).
    private val a = KeyDerivation.groupIdentity(seed, 10)
    private val b = KeyDerivation.groupIdentity(seed, 11)
    private val c = KeyDerivation.groupIdentity(seed, 12)
    private val d = KeyDerivation.groupIdentity(seed, 13)
    private val outsider = KeyDerivation.groupIdentity(seed, 14)

    /** The group id is made for its creator, a (GroupIds): only that author can create the group. */
    private val group = GroupIds.create(a.publicKey, ByteArray(8) { 9 })

    /** Writes events the way phones would: each knows its own counter and what it has seen. */
    private class Chain {
        val log = EventLog()
        private val last = mutableMapOf<PublicKey, Long>()

        fun emit(who: GroupIdentity, body: EventBody): Event {
            val counter = (last[who.publicKey] ?: 0L) + 1
            val after = last.toMutableMap().also { if (counter > 1) it[who.publicKey] = counter - 1 else it.remove(who.publicKey) }
            return Event(1, who.publicKey, counter, after, counter, body).also { log.append(it); last[who.publicKey] = counter }
        }

        fun state() = replay(log)
    }

    private fun newGroup(): Chain = Chain().also { chain ->
        chain.emit(a, EventBody.GroupCreated(group, "Goa Trip", "INR", a.encryptionKey))
        for ((i, who) in listOf(b, c, d).withIndex()) chain.emit(a, EventBody.MemberJoined(who.publicKey, who.encryptionKey, "M$i", "98765 4321$i"))
    }

    private fun removeMember(chain: Chain, by: GroupIdentity, member: GroupIdentity, newKey: GroupKey): Event {
        val state = chain.state()
        val sealed = KeyRotation.seal(by.publicKey, group, state.epoch + 1, newKey, KeyRotation.recipients(state, excluding = setOf(member.publicKey)))
        return chain.emit(by, EventBody.MemberRemoved(member.publicKey, state.epoch + 1, sealed))
    }

    private fun ring(vararg keys: Pair<Long, GroupKey>) = GroupKeyring(group).also { r -> keys.forEach { r.add(it.first, it.second) } }

    // --- the key delivery -------------------------------------------------------------------------

    @Test
    fun everyRemainingMemberOpensTheirOwnSealedKeyAndGetsTheSameKey() {
        val chain = newGroup()
        val k2 = GroupKey.generate()
        val removal = removeMember(chain, a, d, k2)
        val state = chain.state()
        assertNull(state.ignored.firstOrNull { it.id == removal.id }, "replay accepts the removal")
        assertEquals(2L, state.epoch)
        for (who in listOf(a, b, c)) {
            val keyring = ring()
            assertTrue(KeyRotation.receive(state, removal, who, keyring), who.toString())
            assertEquals(k2, keyring[2L])
        }
    }

    @Test
    fun theRemovedMemberHasNoSealedKeyAndCannotOpenAnotherMembersOne() {
        val chain = newGroup()
        val k2 = GroupKey.generate()
        val removal = removeMember(chain, a, d, k2)
        val state = chain.state()
        val body = removal.body as EventBody.MemberRemoved
        assertEquals(setOf(a.publicKey, b.publicKey, c.publicKey), body.sealedKeys.keys)
        val keyring = ring(1L to GroupKey.generate())
        assertFalse(KeyRotation.receive(state, removal, d, keyring))
        assertNull(keyring[2L])
        // Even with someone else's sealed key in hand, only its owner's private key opens it.
        assertNull(KeyRotation.open(body.sealedKeys.getValue(b.publicKey), d, a.publicKey, group, 2))
    }

    @Test
    fun theRemovedMemberReadsOldEpochEventsButNotNewOnes() {
        val chain = newGroup()
        val k1 = GroupKey.generate()
        val k2 = GroupKey.generate()
        val oldEvent = Event(1, c.publicKey, 1, emptyMap(), 5, EventBody.ProfileUpdated("Chitra", "98765 43210"))
        val sealedOld = EventSealing.seal(c, group, 1, k1, oldEvent)
        removeMember(chain, a, d, k2)
        val newEvent = Event(1, c.publicKey, 2, mapOf(c.publicKey to 1), 6, EventBody.ProfileUpdated("Chitra C", "98765 43210"))
        val sealedNew = EventSealing.seal(c, group, 2, k2, newEvent)

        val removedMembersKeys = ring(1L to k1) // all that d ever had
        assertEquals(OpenResult.Opened(oldEvent), EventSealing.open(sealedOld, removedMembersKeys))
        assertEquals(OpenResult.NeedKey(2), EventSealing.open(sealedNew, removedMembersKeys))

        val remaining = ring(1L to k1, 2L to k2)
        assertEquals(OpenResult.Opened(newEvent), EventSealing.open(sealedNew, remaining))
    }

    @Test
    fun aSealedKeyOpensOnlyWithTheRightPrivateKeyGroupAndEpoch() {
        val k = GroupKey.generate()
        val sealed = KeyRotation.seal(a.publicKey, group, 2, k, mapOf(b.publicKey to b.encryptionKey, c.publicKey to c.encryptionKey))
        val forB = sealed.getValue(b.publicKey)
        assertEquals(k, KeyRotation.open(forB, b, a.publicKey, group, 2))
        assertNull(KeyRotation.open(forB, c, a.publicKey, group, 2), "another member's private key")
        assertNull(KeyRotation.open(forB, b, c.publicKey, group, 2), "another sender")
        assertNull(KeyRotation.open(forB, b, a.publicKey, GroupId(ByteArray(16) { 1 }), 2), "another group")
        assertNull(KeyRotation.open(forB, b, a.publicKey, group, 3), "another epoch")
        assertNull(KeyRotation.open(SealedKey(forB.bytes().also { it[40] = (it[40].toInt() xor 1).toByte() }), b, a.publicKey, group, 2), "a changed byte")
        assertNull(KeyRotation.open(SealedKey(forB.bytes().copyOf(30)), b, a.publicKey, group, 2), "cut short")
    }

    @Test
    fun eachSealingIsDifferentAndTheSizeIsFixed() {
        val k = GroupKey.generate()
        val one = KeyRotation.seal(a.publicKey, group, 2, k, mapOf(b.publicKey to b.encryptionKey)).getValue(b.publicKey)
        val two = KeyRotation.seal(a.publicKey, group, 2, k, mapOf(b.publicKey to b.encryptionKey)).getValue(b.publicKey)
        assertNotEquals(one, two)
        assertEquals(32 + 32 + 16, one.bytes().size) // encapsulated key, the group key, tag
    }

    @Test
    fun keysSealedByAnIndependentRfc9180ImplementationOpen() {
        val list = vectors.getValue("keyDeliveries").jsonArray.map { it.jsonObject }
        assertTrue(list.size >= 2)
        for (v in list) {
            val who = KeyDerivation.groupIdentity(seed, v.getValue("recipientSlot").jsonPrimitive.long)
            val opened = KeyRotation.open(SealedKey(v.getValue("sealed").jsonPrimitive.content.hex()), who, PublicKey.fromHex(v.getValue("sender").jsonPrimitive.content), GroupId(v.getValue("groupId").jsonPrimitive.content.hex()), v.getValue("epoch").jsonPrimitive.long)
            assertEquals(GroupKey(v.getValue("groupKey").jsonPrimitive.content.hex()), opened)
        }
    }

    // --- who may deliver a key ---------------------------------------------------------------------

    @Test
    fun aForgedKeyDeliveryByAPlainMemberIsNotAccepted() {
        val chain = newGroup()
        val attackerKey = GroupKey.generate()
        // c is a plain member: replay ignores this rotation, so nobody must take the key from it.
        val sealed = KeyRotation.seal(c.publicKey, group, 2, attackerKey, KeyRotation.recipients(chain.state()))
        val forged = chain.emit(c, EventBody.KeyRotated(2, sealed))
        val state = chain.state()
        assertEquals(IgnoreReason.NOT_ALLOWED, state.ignored.single { it.id == forged.id }.reason)
        val keyring = ring()
        assertFalse(KeyRotation.receive(state, forged, b, keyring))
        assertNull(keyring[2L])
        assertEquals(1L, state.epoch)
    }

    @Test
    fun anEventThatIsNotInTheLogYetDeliversNothing() {
        val chain = newGroup()
        val k2 = GroupKey.generate()
        val state = chain.state()
        val sealed = KeyRotation.seal(a.publicKey, group, 2, k2, KeyRotation.recipients(state))
        val notRecorded = Event(1, a.publicKey, 6, mapOf(a.publicKey to 5), 6, EventBody.KeyRotated(2, sealed))
        val keyring = ring()
        assertFalse(KeyRotation.receive(state, notRecorded, b, keyring))
        assertNull(keyring[2L])
    }

    @Test
    fun aLeaveDeliversNoKeyAndAnAdminRotatesAfterwards() {
        val chain = newGroup()
        chain.emit(c, EventBody.MemberRemoved(c.publicKey, 1, emptyMap()))
        val afterLeave = chain.state()
        assertEquals(1L, afterLeave.epoch)
        val k2 = GroupKey.generate()
        val sealed = KeyRotation.seal(a.publicKey, group, 2, k2, KeyRotation.recipients(afterLeave))
        val rotation = chain.emit(a, EventBody.KeyRotated(2, sealed))
        val state = chain.state()
        assertEquals(rotation.id, state.epochStarts.getValue(2))
        val cKeys = ring(1L to GroupKey.generate())
        assertFalse(KeyRotation.receive(state, rotation, c, cKeys))
        assertNull(cKeys[2L])
        for (who in listOf(a, b, d)) assertTrue(KeyRotation.receive(state, rotation, who, ring()), who.toString())
    }

    @Test
    fun sealedKeysCopiedIntoAnotherOwnersEventDoNotOpen() {
        // Owner decision, 4 Nov 2026 (clarification C1): the sender's key is part of the HPKE context. Ownership moves from a to b;
        // b copies the sealed keys that a made into b's own rotation event. Replay accepts the event (b is the owner now), but nobody
        // can open the keys from it, because they were sealed by a.
        val chain = newGroup()
        chain.emit(a, EventBody.OwnershipOffered(b.publicKey))
        chain.emit(b, EventBody.OwnershipAccepted)
        val k2 = GroupKey.generate()
        val byA = KeyRotation.seal(a.publicKey, group, 2, k2, KeyRotation.recipients(chain.state()))
        val copiedByB = chain.emit(b, EventBody.KeyRotated(2, byA))
        val state = chain.state()
        assertEquals(copiedByB.id, state.epochStarts.getValue(2), "replay accepts it: b is the owner")
        val keyring = ring(1L to GroupKey.generate())
        assertFalse(KeyRotation.receive(state, copiedByB, c, keyring))
        assertNull(keyring[2L])
    }

    @Test
    fun theRecipientsAreExactlyTheCurrentMembersWithTheirEncryptionKeys() {
        val chain = newGroup()
        chain.emit(a, EventBody.MemberRemoved(d.publicKey, 2, KeyRotation.seal(a.publicKey, group, 2, GroupKey.generate(), KeyRotation.recipients(chain.state(), setOf(d.publicKey)))))
        val state = chain.state()
        val recipients = KeyRotation.recipients(state)
        assertEquals(mapOf<PublicKey, EncryptionKey>(a.publicKey to a.encryptionKey, b.publicKey to b.encryptionKey, c.publicKey to c.encryptionKey), recipients)
    }

    // --- whole pipeline ---------------------------------------------------------------------------

    @Test
    fun eventsFromARemovedMemberOrANonMemberCanBeReadButHaveNoEffect() {
        val chain = newGroup()
        val k1 = GroupKey.generate()
        val removal = removeMember(chain, a, d, GroupKey.generate())
        // d still holds k1 and keeps writing under epoch 1; an outsider who got k1 does the same.
        val late = chain.emit(d, EventBody.ExpenseAdded("Late", Money(100), d.publicKey, 1, Split.Equal(setOf(d.publicKey))))
        val intruder = chain.emit(outsider, EventBody.ExpenseAdded("Intruder", Money(100), outsider.publicKey, 1, Split.Equal(setOf(outsider.publicKey))))
        val state = chain.state()
        for ((who, event) in listOf(d to late, outsider to intruder)) {
            val envelope = EventSealing.seal(who, group, 1, k1, event)
            assertEquals(OpenResult.Opened(event), EventSealing.open(envelope, ring(1L to k1)))
        }
        assertEquals(IgnoreReason.AFTER_REMOVAL, state.ignored.single { it.id == late.id }.reason)
        assertEquals(IgnoreReason.NOT_MEMBER, state.ignored.single { it.id == intruder.id }.reason)
        assertTrue(state.expenses.isEmpty())
        assertTrue(removal.id in state.epochStarts.values)
    }

}
