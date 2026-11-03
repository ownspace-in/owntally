package owntally.data.repo

import java.security.SecureRandom
import owntally.crypto.Clock
import owntally.crypto.EnvelopeSigning
import owntally.crypto.EventSealing
import owntally.crypto.GroupKey
import owntally.data.secrets.SecretStore
import owntally.data.store.EventStore
import owntally.data.store.GroupRow
import owntally.data.store.StoredEnvelope
import owntally.model.DecodeException
import owntally.model.EncryptionKey
import owntally.model.Envelope
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.GroupId

enum class RejectedBecause { UNKNOWN_GROUP, WRONG_GROUP, TOO_LARGE, BAD_SIGNATURE }

sealed class AppendResult {
    data object Added : AppendResult()

    /** Exactly these bytes were already stored. */
    data object Duplicate : AppendResult()
    data class Rejected(val because: RejectedBecause) : AppendResult()
}

/** The envelopes stored for a group, in the order they were added; [damaged] counts stored entries that could not be read back. */
class StoredEnvelopes(val envelopes: List<Envelope>, val damaged: Int)

/**
 * Groups and their events on this phone. Creating a group makes its first key and its first two events, all sealed, and either
 * stores everything or nothing. Whether an envelope may do anything is decided later by replaying the events (M1); here an
 * envelope is only checked to belong to the group and to carry a valid signature.
 */
class GroupRepository(
    private val secrets: SecretStore,
    private val events: EventStore,
    private val identity: IdentityRepository,
    private val clock: Clock,
    private val random: SecureRandom,
) {
    fun listGroups(): List<GroupRow> = events.groups()
    fun group(id: GroupId): GroupRow? = events.group(id)

    /**
     * A new group owned by this person: a fresh group key (epoch 1), a key slot used for no other group, and the events
     * `GroupCreated` and `ProfileUpdated` (the default name and phone). Throws [IllegalStateException] if first run is not
     * complete or the seed cannot be opened, and [IllegalArgumentException] for a bad name or currency (nothing is stored then).
     */
    @Synchronized
    fun createGroup(name: String, currency: String = "INR"): GroupId {
        check(identity.state() == IdentityState.READY) { "first run is not complete" }
        val profile = checkNotNull(identity.defaultProfile()) { "no default profile" }
        val groupId = GroupId(ByteArray(16).also { random.nextBytes(it) })
        EventBody.GroupCreated(groupId, name, currency, EncryptionKey(ByteArray(32))) // validates name and currency before anything is used up

        val slot = identity.reserveSlot(events.groups().map { it.keySlot })
        val me = checkNotNull(identity.groupIdentity(slot)) { "no usable seed" }
        val key = GroupKey.generate(random)
        val nowMillis = clock.nowSeconds() * 1000
        val created = Event(1, me.publicKey, 1, emptyMap(), nowMillis, EventBody.GroupCreated(groupId, name, currency, me.encryptionKey))
        val named = Event(1, me.publicKey, 2, mapOf(me.publicKey to 1L), nowMillis, EventBody.ProfileUpdated(profile.name, profile.phone))
        val envelopes = listOf(created, named).map { EventSealing.seal(me, groupId, 1, key, it) }

        secrets.saveGroupKey(groupId, 1, key)
        try {
            events.addGroup(GroupRow(groupId, slot, clock.nowSeconds()), envelopes.map { StoredEnvelope(it.author, it.counter, it.encode()) })
        } catch (e: Exception) {
            secrets.deleteGroup(groupId) // no key is left behind for a group that does not exist
            throw e
        }
        return groupId
    }

    @Synchronized
    fun appendEnvelope(groupId: GroupId, envelope: Envelope): AppendResult {
        if (events.group(groupId) == null) return AppendResult.Rejected(RejectedBecause.UNKNOWN_GROUP)
        if (envelope.groupId != groupId) return AppendResult.Rejected(RejectedBecause.WRONG_GROUP)
        val bytes = envelope.encode()
        if (bytes.size > MAX_ENVELOPE_BYTES) return AppendResult.Rejected(RejectedBecause.TOO_LARGE)
        if (!EnvelopeSigning.verify(envelope)) return AppendResult.Rejected(RejectedBecause.BAD_SIGNATURE)
        return if (events.addEnvelope(groupId, StoredEnvelope(envelope.author, envelope.counter, bytes))) AppendResult.Added else AppendResult.Duplicate
    }

    fun envelopes(groupId: GroupId): StoredEnvelopes {
        var damaged = 0
        val list = events.envelopes(groupId).mapNotNull { bytes ->
            try {
                Envelope.decode(bytes)
            } catch (e: DecodeException) {
                damaged++
                null
            }
        }
        return StoredEnvelopes(list, damaged)
    }

    /** Removes the group's events first and its keys second, so a crash in between leaves at worst an unused key. */
    @Synchronized
    fun deleteGroup(groupId: GroupId) {
        events.deleteGroup(groupId)
        secrets.deleteGroup(groupId)
    }

    companion object {
        /** No real event comes near this; it stops one peer filling the phone. */
        const val MAX_ENVELOPE_BYTES = 64 * 1024
    }
}
