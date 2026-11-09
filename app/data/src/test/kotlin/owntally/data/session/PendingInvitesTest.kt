package owntally.data.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import owntally.crypto.Clock
import owntally.crypto.GroupKey
import owntally.crypto.Ticket
import owntally.data.secrets.InMemorySecretStore
import owntally.data.secrets.SecretLoad
import owntally.model.GroupId
import owntally.model.PublicKey

/** M9, T9.3, decision A1: a link that arrives before the person can use it waits in the secret store until used, refused or out of time. */
class PendingInvitesTest {
    private var now = 1_800_000_000L
    private val secrets = InMemorySecretStore()
    private val pending = PendingInvites(secrets, Clock { now })

    private fun ticket(expires: Long = now + 8 * 3600) = Ticket(ByteArray(16) { 1 }, PublicKey(ByteArray(32) { 2 }), GroupId(ByteArray(16) { 3 }), 1, GroupKey(ByteArray(32) { 4 }), expires, "")
    private fun link(t: Ticket = ticket()) = Invites.LINK_START + t.encode()

    @Test fun aGoodLinkIsKeptAndComesBackOnce() {
        assertEquals(PendingInvites.Received.Kept, pending.receive(link()))
        assertEquals(link(), pending.waiting())
        assertEquals(link(), pending.waiting(), "it stays until it is used")
        pending.clear()
        assertNull(pending.waiting())
    }

    @Test fun whatIsNotAnInviteIsNeverKept() {
        assertEquals(PendingInvites.Received.NotAnInvite, pending.receive("https://joinowntally.ownspace.in/j#not-a-ticket"))
        assertNull(pending.waiting())
        assertEquals(SecretLoad.Missing, secrets.loadPendingInvite())
    }

    @Test fun aLinkThatHasAlreadyExpiredIsNeverKept() {
        assertEquals(PendingInvites.Received.Expired, pending.receive(link(ticket(expires = now - 3600))))
        assertNull(pending.waiting())
    }

    @Test fun aNewerLinkReplacesTheOne() {
        val other = Ticket(ByteArray(16) { 9 }, PublicKey(ByteArray(32) { 2 }), GroupId(ByteArray(16) { 3 }), 1, GroupKey(ByteArray(32) { 4 }), now + 3600, "")
        pending.receive(link()); pending.receive(link(other))
        assertEquals(link(other), pending.waiting())
    }

    @Test fun aWaitingLinkThatRunsOutIsDroppedWhenAskedFor() {
        pending.receive(link(ticket(expires = now + 3600)))
        now += 3600 + 24 * 3600 // well past the ticket's time and the allowance for clocks
        assertNull(pending.waiting())
        assertEquals(SecretLoad.Missing, secrets.loadPendingInvite(), "and forgotten, not only hidden")
    }

    @Test fun anUnreadableOneIsDroppedToo() {
        // A stored value that is not a link any more (damaged, or from a different version) must not stay for ever.
        secrets.savePendingInvite("junk", now + 1000)
        assertNull(pending.waiting())
        assertEquals(SecretLoad.Missing, secrets.loadPendingInvite())
    }

    @Test fun everyChangeIsAnnouncedSoTheScreenNoticesALinkThatArrivesLater() {
        val before = pending.changes.value
        pending.receive(link())
        assertEquals(before + 1, pending.changes.value)
        pending.receive("junk")
        assertEquals(before + 2, pending.changes.value, "a refused link is announced too, so its message can be shown")
    }

    @Test fun aRefusedLinkLeavesAMessageForTheScreenOnce() {
        pending.receive("junk")
        assertEquals(PendingInvites.Received.NotAnInvite, pending.takeProblem())
        assertNull(pending.takeProblem(), "shown once")
        pending.receive(link(ticket(expires = now - 3600)))
        assertEquals(PendingInvites.Received.Expired, pending.takeProblem())
    }

    @Test fun aLinkThatFailedTheLinkCheckKeepsNothingAndLeavesTheMessage() {
        pending.receive(link())
        pending.refuse()
        assertEquals(PendingInvites.Received.NotAnInvite, pending.takeProblem())
        assertEquals(link(), pending.waiting(), "a bad link does not throw away a good one that was waiting")
    }
}
