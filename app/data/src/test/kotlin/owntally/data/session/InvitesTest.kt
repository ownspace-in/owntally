package owntally.data.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.crypto.InMemoryTicketRecords
import owntally.crypto.Ticket
import owntally.data.repo.TestEnv
import owntally.data.store.InMemoryJoinRequestStore
import owntally.model.GroupId

/** T6.3 and T6.4 on real repositories: tickets of a real group, a "restart" being a new Invites over the same records. */
class InvitesTest {
    private val env = TestEnv().also { it.firstRun() }
    private val group = env.groups.createGroup("Goa Trip")
    private val records = InMemoryTicketRecords()
    private fun sessions() = Sessions(env.groups, env.secrets, env.identity, env.clock, env.settings)
    private fun invites() = Invites(records, env.groups, env.secrets, env.identity, sessions(), env.clock)

    @Test fun aTicketIssuedForAGroupIsUsableOnceEvenAfterARestart() {
        val ticket = invites().registry(group)!!.issue(group, 1, env.secrets.loadKeyring(group).keyring[1]!!)
        assertEquals(env.identity.groupIdentity(env.groups.group(group)!!.keySlot)!!.publicKey, ticket.hostId)
        assertNotNull(invites().registry(group)!!.find(ticket.ticketId()))
        assertTrue(invites().registry(group)!!.accept(ticket))
        assertFalse(invites().registry(group)!!.accept(ticket), "used before the restart, still used")
    }

    @Test fun theKeyIsReadFromTheKeyringSoTheRecordHoldsNone() {
        val key = env.secrets.loadKeyring(group).keyring[1]!!
        val ticket = invites().registry(group)!!.issue(group, 1, key)
        val record = records.get(ticket.ticketId())!!
        assertFalse(record.javaClass.declaredFields.any { it.type.simpleName.contains("Key") && it.type.simpleName.contains("Group") })
        env.secrets.deleteGroupKey(group, 1)
        assertNull(invites().registry(group)!!.find(ticket.ticketId()), "no key, no ticket")
    }

    @Test fun aGroupThatIsNotThisPhonesHasNoRegistry() {
        assertNull(invites().registry(GroupId(ByteArray(16) { 1 })))
    }

    // ---- the invitations the screen shows (T6.4) ------------------------------------------------------------------------------

    @Test fun theOwnerGetsAnInviteWhoseLinkHoldsATicketThatTheirOwnRegistryAccepts() {
        val invite = invites().create(group)!!
        assertTrue(invite.link.startsWith("https://joinowntally.ownspace.in/j#"))
        val ticket = Ticket.fromShared(invite.link)!!
        assertEquals(env.clock.nowSeconds() + Ticket.LIFETIME_SECONDS, invite.expiresAtSeconds)
        assertEquals(1L, ticket.epoch)
        assertTrue(invites().registry(group)!!.accept(ticket))
    }

    @Test fun anInviteAfterAKeyChangeCarriesTheNewKeyNotTheOldOne() {
        val s = sessions()
        assertTrue(s.session(group).rotateKey() is AddResult.Added)
        val invites = Invites(records, env.groups, env.secrets, env.identity, s, env.clock)
        val ticket = Ticket.fromShared(invites.create(group)!!.link)!!
        assertEquals(2L, ticket.epoch)
        assertEquals(env.secrets.loadKeyring(group).keyring[2L], ticket.groupKey)
    }

    // ---- N2 (docs/SECURITY_REVIEW.md, owner decision 10 Nov 2026): a key change cancels the invitations that are open ---------------------

    @Test fun anOpenInviteStopsWorkingWhenTheKeyChanges() {
        val s = sessions()
        val before = Invites(records, env.groups, env.secrets, env.identity, s, env.clock)
        val old = before.create(group)!!
        val backend = GroupJoinBackend(group, env.identity.groupIdentity(env.groups.group(group)!!.keySlot)!!, before, JoinRequests(InMemoryJoinRequestStore(), s, env.clock), s)
        assertNotNull(backend.issued(old.ticketId), "works before the change")
        assertTrue(s.session(group).rotateKey() is AddResult.Added)
        val after = Invites(records, env.groups, env.secrets, env.identity, s, env.clock)
        assertNull(backend.issued(old.ticketId), "a joiner holding the old link is refused (the same answer as for an expired one)")
        assertTrue(after.open(group).isEmpty(), "and the screen no longer lists it")
        assertNull(after.current(group))
        val fresh = after.create(group)!!
        assertNotNull(backend.issued(fresh.ticketId), "a new invitation carries the new key and works")
    }

    @Test fun removingAMemberCancelsTheOpenInvitesToo() {
        val s = sessions()
        val invites = Invites(records, env.groups, env.secrets, env.identity, s, env.clock)
        val backend = GroupJoinBackend(group, env.identity.groupIdentity(env.groups.group(group)!!.keySlot)!!, invites, JoinRequests(InMemoryJoinRequestStore(), s, env.clock), s)
        val old = invites.create(group)!!
        val ben = owntally.model.PublicKey(ByteArray(32) { 9 })
        assertTrue(s.session(group).approveJoin(ben, owntally.model.EncryptionKey(ByteArray(32) { 7 }), "Ben", "+91 98765 43211") is AddResult.Added)
        assertNotNull(backend.issued(old.ticketId), "admitting someone does not change the key")
        assertTrue(s.session(group).removeMember(ben) is AddResult.Added)
        assertNull(backend.issued(old.ticketId))
    }

    @Test fun theCurrentInviteIsTheNewestOneThatStillWorksAndSurvivesARestart() {
        assertNull(invites().current(group), "none yet")
        val first = invites().create(group)!!
        env.nowSeconds += 600
        val second = invites().create(group)!!
        assertEquals(second.link, invites().current(group)!!.link)
        assertTrue(first.link != second.link)
        invites().revoke(group, second)
        assertEquals(first.link, invites().current(group)!!.link, "the older one is still open")
    }

    @Test fun anInviteThatWasUsedExpiredOrRevokedIsNotCurrent() {
        val used = invites().create(group)!!
        assertTrue(invites().registry(group)!!.accept(Ticket.fromShared(used.link)!!))
        assertNull(invites().current(group), "used")
        val expired = invites().create(group)!!
        env.nowSeconds += Ticket.LIFETIME_SECONDS
        assertNull(invites().current(group), "expired")
        val revoked = invites().create(group)!!
        invites().revoke(group, revoked)
        assertNull(invites().current(group), "revoked")
        assertFalse(invites().registry(group)!!.accept(Ticket.fromShared(revoked.link)!!))
    }

    @Test fun aPlainMemberAndAStrangerCannotCreateOrSeeInvitesButTheOwnerCan() {
        val asha = owntally.data.sim.SimDevice.owner(3, "Asha")
        val ben = owntally.data.sim.SimDevice.joined(3, 1, "Ben", asha)
        fun invitesOf(d: owntally.data.sim.SimDevice) = Invites(InMemoryTicketRecords(), d.env.groups, d.env.secrets, d.env.identity, Sessions(d.env.groups, d.env.secrets, d.env.identity, d.env.clock, d.env.settings), d.env.clock)
        fun catchUp() { asha.env.groups.envelopes(asha.group).envelopes.forEach { ben.env.groups.appendEnvelope(ben.group, it) }; ben.session.refresh() }
        catchUp() // Ben's phone learns of his own admission
        assertTrue(ben.key in ben.session.view.value.state.currentMembers, "Ben is a member in his own view")
        assertNotNull(invitesOf(asha).create(asha.group), "the owner can")
        val plain = invitesOf(ben)
        assertFalse(plain.canInvite(ben.group))
        assertNull(plain.create(ben.group), "a plain member cannot")
        assertNull(plain.current(ben.group))
        assertFalse(invitesOf(asha).canInvite(GroupId(ByteArray(16) { 3 })), "not a group of this phone")
        assertNull(invitesOf(asha).create(GroupId(ByteArray(16) { 3 })))
        // Made an admin, he can.
        assertTrue(asha.session.makeAdmin(ben.key) is AddResult.Added)
        catchUp()
        val admin = invitesOf(ben) // a new phone session reads the log again
        assertTrue(admin.canInvite(ben.group))
        assertNotNull(admin.create(ben.group), "an admin can")
    }

    @Test fun anInviteNeverShowsItsSecretInToString() {
        val invite = invites().create(group)!!
        val secret = invite.link.substringAfter('#')
        assertFalse(invite.toString().contains(secret))
    }

    @Test fun everyOpenInviteIsListedNewestFirstAndRevokingOneLeavesTheOthersAndTheListIsEmptyForAPlainMember() {
        assertEquals(emptyList(), invites().open(group).map { it.link })
        val first = invites().create(group)!!
        env.nowSeconds += 60
        val second = invites().create(group)!!
        assertEquals(listOf(second.link, first.link), invites().open(group).map { it.link })
        invites().revoke(group, second)
        assertEquals(listOf(first.link), invites().open(group).map { it.link })
        env.nowSeconds += Ticket.LIFETIME_SECONDS
        assertEquals(emptyList(), invites().open(group).map { it.link }, "expired")
        val asha = owntally.data.sim.SimDevice.owner(3, "Asha")
        val ben = owntally.data.sim.SimDevice.joined(3, 1, "Ben", asha)
        asha.env.groups.envelopes(asha.group).envelopes.forEach { ben.env.groups.appendEnvelope(ben.group, it) }
        val plain = Invites(InMemoryTicketRecords(), ben.env.groups, ben.env.secrets, ben.env.identity, Sessions(ben.env.groups, ben.env.secrets, ben.env.identity, ben.env.clock, ben.env.settings), ben.env.clock)
        assertEquals(emptyList(), plain.open(ben.group).map { it.link })
    }

    @Test fun theRequestsListSaysWhenSomethingChanged() {
        val sessions = sessions()
        val requests = JoinRequests(owntally.data.store.InMemoryJoinRequestStore(), sessions, env.clock)
        val before = requests.changes.value
        val key = env.identity.groupIdentity(77)!!
        requests.receive(group, key.publicKey, key.encryptionKey, "Rohan", "98765 43210")
        assertEquals(before + 1, requests.changes.value)
        requests.decline(group, key.publicKey)
        assertEquals(before + 2, requests.changes.value)
        requests.decline(group, key.publicKey) // already declined: nothing changed
        assertEquals(before + 2, requests.changes.value)
        val k2 = env.identity.groupIdentity(78)!!
        requests.receive(group, k2.publicKey, k2.encryptionKey, "Meera", "98765 43211")
        requests.approve(group, k2.publicKey, "Meera")
        assertEquals(before + 4, requests.changes.value)
    }
}
