package owntally.app

import owntally.crypto.Clock
import owntally.crypto.EventSealing
import owntally.crypto.GroupIdentity
import owntally.crypto.KeyDerivation
import owntally.data.repo.GroupRepository
import owntally.data.secrets.SecretLoad
import owntally.data.secrets.SecretStore
import owntally.data.session.AddResult
import owntally.data.session.GroupSession
import owntally.data.session.JoinRequests
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.Money
import owntally.model.PublicKey
import owntally.model.Split
import owntally.ui.groups.DebugAction

/** A pretend person: a name and the identity made from this phone's own seed. */
internal class Pretend(val name: String, val identity: GroupIdentity) {
    val key: PublicKey get() = identity.publicKey
}

/**
 * Debug builds only (M4, D6 and D7): stand-ins for other people until joining by link exists (M6). Up to four pretend friends
 * (five people in all), pretend join requests, and writing events as a pretend member, so every membership screen can be tried on
 * one phone. The people come from this phone's own seed at slots far above those real groups use. Nothing here ships in release builds.
 */
internal class PretendMembers(
    private val secrets: SecretStore,
    private val groups: GroupRepository,
    private val requests: JoinRequests,
    private val clock: Clock,
) {
    companion object {
        const val FRIEND_SLOT = 2_000_000L
        const val REQUEST_SLOT = 3_000_000L
        val FRIENDS = listOf("Ben (test)", "Meera (test)", "Kabir (test)", "Isha (test)")
        val REQUESTERS = listOf("Aarav (test)", "Naina (test)", "Zoya (test)", "Vikram (test)", "Tara (test)", "Dev (test)")
        const val MAX_PEOPLE = 5
    }

    private fun identity(slot: Long): GroupIdentity? =
        (secrets.loadMasterSeed() as? SecretLoad.Found)?.value?.let { KeyDerivation.groupIdentity(it, slot) }

    private fun phone(slot: Long) = "98765 %05d".format(slot % 100_000)

    /** The pretend friends who are in the group now. */
    fun friends(session: GroupSession): List<Pretend> {
        val members = session.view.value.state.currentMembers
        return FRIENDS.indices.mapNotNull { i -> identity(FRIEND_SLOT + i)?.takeIf { it.publicKey in members }?.let { Pretend(FRIENDS[i], it) } }
    }

    /** Adds the next pretend friend ("Ben (test)", then "Meera (test)", ...) or returns null when the group has five people or all four are in. */
    fun addFriend(session: GroupSession): String? {
        val state = session.view.value.state
        if (state.currentMembers.size >= MAX_PEOPLE) return null
        val i = FRIENDS.indices.firstOrNull { identity(FRIEND_SLOT + it)?.publicKey !in state.members } ?: return null
        val id = identity(FRIEND_SLOT + i) ?: return null
        val result = session.approveJoin(id.publicKey, id.encryptionKey, FRIENDS[i], "98765 0000$i")
        return if (result is AddResult.Added) FRIENDS[i] else null
    }

    /** A pretend person asks to join; returns their name, or null if every pretend requester is already in or waiting. */
    fun addRequest(session: GroupSession): String? {
        val group = session.view.value.state.groupId ?: return null
        val taken = session.view.value.state.members.values.map { it.name }.toSet() + requests.waiting(group).map { it.name }
        val i = REQUESTERS.indices.firstOrNull { REQUESTERS[it] !in taken } ?: return null
        val id = identity(REQUEST_SLOT + i) ?: return null
        requests.receive(group, id.publicKey, id.encryptionKey, REQUESTERS[i], phone(REQUEST_SLOT + i))
        return REQUESTERS[i]
    }

    /**
     * Writes [body] as [who], the way their own phone would (sealed and signed with their identity, with the counter and "seen" list
     * their phone would have). The group's rules still decide: if they refuse, that is returned and nothing counts.
     */
    @Synchronized
    fun act(session: GroupSession, who: Pretend, body: EventBody): AddResult {
        val state = session.view.value.state
        val group = state.groupId ?: return AddResult.NoKey
        val key = secrets.loadKeyring(group).keyring[state.epoch] ?: return AddResult.NoKey
        val stored = groups.envelopes(group).envelopes
        val after = stored.groupBy { it.author }.mapValues { (_, list) -> list.maxOf { it.counter } }
        val event = Event(1, who.key, (after[who.key] ?: 0L) + 1, after, clock.nowSeconds() * 1000, body)
        groups.appendEnvelope(group, EventSealing.seal(who.identity, group, state.epoch, key, event))
        session.refresh()
        return session.view.value.state.ignored.firstOrNull { it.id == event.id }?.let { AddResult.Refused(it.reason) } ?: AddResult.Added(event.id)
    }

    /** The debug buttons that make sense for this group right now. */
    fun actions(session: GroupSession): List<DebugAction> {
        val state = session.view.value.state
        val list = ArrayList<DebugAction>()
        val next = FRIENDS.indices.firstOrNull { identity(FRIEND_SLOT + it)?.publicKey !in state.members }
        if (state.currentMembers.size < MAX_PEOPLE && next != null) list += DebugAction("Add pretend friend: ${FRIENDS[next]}") { addFriend(session) }
        list += DebugAction("Add pretend join request") { addRequest(session) }
        for (f in friends(session)) {
            val me = session.view.value.me
            if (state.pendingOffer == f.key) {
                list += DebugAction("As ${f.name}: accept ownership") { act(session, f, EventBody.OwnershipAccepted) }
                list += DebugAction("As ${f.name}: decline ownership") { act(session, f, EventBody.OwnershipDeclined) }
            }
            val day = clock.nowSeconds() / 86_400
            list += DebugAction("As ${f.name}: write an expense") {
                act(session, f, EventBody.ExpenseAdded("Snacks by ${f.name}", Money(60_000), f.key, day, Split.Equal(setOf(me, f.key))))
            }
            if (state.members[f.key]?.role == owntally.model.Role.MEMBER) {
                list += DebugAction("As ${f.name}: leave the group") { act(session, f, EventBody.MemberRemoved(f.key, state.epoch, emptyMap())) }
            }
        }
        return list
    }
}
