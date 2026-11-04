package owntally.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.model.DecodeException
import owntally.model.Envelope
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.EventLog
import owntally.model.GroupId
import owntally.model.IgnoreReason
import owntally.model.Money
import owntally.model.Split
import owntally.model.replay

/** One test for each line of the M2 acceptance list in docs/BUILD_PLAN.md. The detail lives in the other test classes. */
class M2AcceptanceTest {
    private val group = GroupId(ByteArray(16) { 9 })

    @Test
    fun phraseRestoreGivesTheSameKeys() {
        // First run: the app makes a phrase and the keys for three groups. New phone: the person types the 12 words.
        val phrase = Bip39.generate()
        val original = (0L..2L).map { KeyDerivation.groupIdentity(Bip39.seed(phrase), it) }
        val typedBack = phrase.words().joinToString("  ").uppercase()
        val restored = (0L..2L).map { KeyDerivation.groupIdentity(Bip39.seed(RecoveryPhrase.fromText(typedBack)), it) }
        assertEquals(original, restored)
        for ((x, y) in original.zip(restored)) {
            assertEquals(x.publicKey, y.publicKey)
            assertEquals(x.encryptionKey, y.encryptionKey)
        }
        // A different phrase gives different keys. (Fixed published vectors are in Bip39Test and KeyDerivationTest.)
        assertNotEquals(original[0].publicKey, KeyDerivation.groupIdentity(Bip39.seed(Bip39.generate()), 0).publicKey)
    }

    @Test
    fun aTamperedEventAWrongSignatureOrAnEventFromANonMemberIsRejected() {
        val seed = Bip39.seed(Bip39.generate())
        val owner = KeyDerivation.groupIdentity(seed, 0)
        val member = KeyDerivation.groupIdentity(seed, 1)
        val outsider = KeyDerivation.groupIdentity(seed, 2)
        val key = GroupKey.generate()
        val ring = GroupKeyring(group).also { it.add(1, key) }

        val log = EventLog()
        val created = Event(1, owner.publicKey, 1, emptyMap(), 1, EventBody.GroupCreated(group, "Trip", "INR", owner.encryptionKey))
        val joined = Event(1, owner.publicKey, 2, mapOf(owner.publicKey to 1), 2, EventBody.MemberJoined(member.publicKey, member.encryptionKey, "Ben", "98765 00002"))
        log.append(created); log.append(joined)
        val state = replay(log)

        val spend = fun(who: GroupIdentity) = Event(1, who.publicKey, 1, emptyMap(), 3, EventBody.ExpenseAdded("Lunch", Money(500), who.publicKey, 1, Split.Equal(setOf(who.publicKey))))
        val good = EventSealing.seal(member, group, 1, key, spend(member))
        assertTrue(EventSealing.open(good, ring) is OpenResult.Opened)
        assertTrue(EnvelopeSigning.verifyFrom(good, state.currentMembers.keys))

        // Tampered: any changed byte is refused.
        val bytes = good.encode()
        for (i in bytes.indices) {
            val bad = bytes.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            val result = try { EventSealing.open(Envelope.decode(bad), ring) } catch (e: DecodeException) { null }
            assertTrue(result == null || result !is OpenResult.Opened, "byte $i")
        }
        // Wrong signature: the member's envelope with the owner's signature.
        val signedByOwner = EventSealing.seal(owner, group, 1, key, spend(owner))
        val swapped = Envelope(1, group, 1, member.publicKey, 1, good.nonce(), good.ciphertext(), signedByOwner.signature())
        assertEquals(OpenResult.Rejected(RejectReason.BAD_SIGNATURE), EventSealing.open(swapped, ring))
        // Non-member: a valid, readable event from someone who never joined is refused as a member's, and has no effect.
        val intruder = EventSealing.seal(outsider, group, 1, key, spend(outsider))
        assertFalse(EnvelopeSigning.verifyFrom(intruder, state.currentMembers.keys))
        val opened = (EventSealing.open(intruder, ring) as OpenResult.Opened).event
        log.append(opened)
        assertEquals(IgnoreReason.NOT_MEMBER, replay(log).ignored.single { it.id == opened.id }.reason)
    }

    @Test
    fun aRemovedMemberCannotDecryptEventsFromTheNewEpoch() {
        val seed = Bip39.seed(Bip39.generate())
        val owner = KeyDerivation.groupIdentity(seed, 0)
        val stays = KeyDerivation.groupIdentity(seed, 1)
        val leaves = KeyDerivation.groupIdentity(seed, 2)
        val k1 = GroupKey.generate()
        val k2 = GroupKey.generate()
        val log = EventLog()
        log.append(Event(1, owner.publicKey, 1, emptyMap(), 1, EventBody.GroupCreated(group, "Trip", "INR", owner.encryptionKey)))
        log.append(Event(1, owner.publicKey, 2, mapOf(owner.publicKey to 1), 2, EventBody.MemberJoined(stays.publicKey, stays.encryptionKey, "Ben", "98765 00002")))
        log.append(Event(1, owner.publicKey, 3, mapOf(owner.publicKey to 2), 3, EventBody.MemberJoined(leaves.publicKey, leaves.encryptionKey, "Eve", "98765 00005")))
        val sealed = KeyRotation.seal(group, 2, k2, KeyRotation.recipients(replay(log), excluding = setOf(leaves.publicKey)))
        val removal = Event(1, owner.publicKey, 4, mapOf(owner.publicKey to 3), 4, EventBody.MemberRemoved(leaves.publicKey, 2, sealed))
        log.append(removal)
        val state = replay(log)

        val ringOfLeaver = GroupKeyring(group).also { it.add(1, k1) }
        val ringOfStayer = GroupKeyring(group).also { it.add(1, k1) }
        assertFalse(KeyRotation.receive(state, removal, leaves, ringOfLeaver))
        assertTrue(KeyRotation.receive(state, removal, stays, ringOfStayer))

        val newEvent = Event(1, stays.publicKey, 1, emptyMap(), 5, EventBody.ProfileUpdated("Ben B", "98765 00002"))
        val envelope = EventSealing.seal(stays, group, 2, k2, newEvent)
        assertEquals(OpenResult.NeedKey(2), EventSealing.open(envelope, ringOfLeaver))
        assertEquals(OpenResult.Opened(newEvent), EventSealing.open(envelope, ringOfStayer))
    }

    @Test
    fun anExpiredOrReusedTicketIsRejected() {
        var now = 1_000_000L
        val host = KeyDerivation.groupIdentity(Bip39.seed(Bip39.generate()), 0).publicKey
        val registry = TicketRegistry(host, Clock { now })
        val reused = registry.issue(group, 1, GroupKey.generate())
        val expired = registry.issue(group, 1, GroupKey.generate())
        assertTrue(registry.accept(Ticket.fromShared("https://joinowntally.ownspace.in/j#" + reused.encode())!!))
        assertFalse(registry.accept(reused))
        now += Ticket.LIFETIME_SECONDS
        assertFalse(registry.accept(expired))
        assertNull(Ticket.decode(expired.encode().dropLast(1)))
    }

    @Test
    fun noKeyOrPhraseIsEverWrittenToLogs() {
        // The core and crypto modules contain no logging or printing, and every secret prints as [redacted]:
        // see SecretsNotLoggedTest for the checks in hex, Base64, byte-list and word form.
        assertEquals("RecoveryPhrase([redacted])", Bip39.generate().toString())
    }
}
