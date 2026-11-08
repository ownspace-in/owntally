package owntally.data.session

import owntally.model.PublicKey
import owntally.sync.Admission
import owntally.sync.AuthorState
import owntally.sync.PeerAccess
import owntally.sync.SyncStore
import owntally.sync.SyncSummary

/**
 * Who may have what (decision S2), judged by the group as this phone knows it:
 * - a member gets everything;
 * - someone who was never a member is refused before anything is sent;
 * - someone the owner removed is sent only the removal event, shown a summary clipped to what the removal had seen, and what they hand
 *   over is stored (their entries after the cut-off become the owner's list of cancelled entries);
 * - the phone that introduced a person who has not yet received their own admission is treated as a member (it is who they asked to join);
 * - someone who left is sent nothing and shown only their own entry in the summary, which, after the sync, proves their leave arrived.
 */
class MembershipAccess(private val session: GroupSession, private val store: SyncStore, private val introducers: Set<PublicKey> = emptySet()) : PeerAccess {
    override fun admit(peer: PublicKey): Admission {
        session.refresh() // what the peer has just handed over (their leave, for one) counts at once
        val state = session.view.value.state
        // A person just admitted knows nobody yet: the phone that approved them (named in their invitation) is trusted until their own admission has arrived.
        val member = state.members[peer] ?: return if (peer in introducers && state.members[session.view.value.me] == null) Admission.Full else Admission.Refuse
        if (!member.removed) return Admission.Full
        val removal = state.removals[peer] ?: return Admission.Refuse
        fun ownEntry(): Map<PublicKey, AuthorState> = store.summary().entries.filterKeys { it == peer }
        if (removal.by == peer) return Admission.Limited({ SyncSummary(ownEntry()) }, emptyList())
        val event = session.eventOf(removal.event) ?: return Admission.Limited({ SyncSummary(ownEntry()) }, emptyList())
        val clock = event.after + (event.author to event.counter)
        val send = store.versionsAbove(removal.event.author, removal.event.counter - 1).filter { bytes ->
            owntally.model.Envelope.decode(bytes).counter == removal.event.counter
        }
        return Admission.Limited(
            {
                val clipped = clock.mapNotNull { (author, counter) -> store.fingerprintAt(author, counter)?.let { author to AuthorState(counter, it) } }.toMap()
                SyncSummary(clipped + ownEntry())
            },
            send,
        )
    }
}
