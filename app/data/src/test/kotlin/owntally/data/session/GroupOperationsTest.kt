package owntally.data.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.crypto.GroupKey
import owntally.data.repo.TestEnv
import owntally.data.store.GroupRow
import owntally.data.store.StoredEnvelope
import owntally.model.EncryptionKey
import owntally.model.EventBody
import owntally.model.GroupId
import owntally.model.IgnoreReason
import owntally.model.Money
import owntally.model.PublicKey
import owntally.model.Role
import owntally.model.Split

/**
 * The group operations of M4 (T4.3), tried the way they really happen: several phones, each with its own storage and keys, that
 * pass stored envelopes to each other. "Syncing" here copies envelopes; the real sync arrives in M5.
 */
class GroupOperationsTest {
    private class Phone(val env: TestEnv, val group: GroupId, val slot: Long) {
        val me get() = env.identity.groupIdentity(slot)!!
        val key: PublicKey get() = me.publicKey
        val session: GroupSession by lazy { GroupSession(group, env.groups, env.secrets, env.identity, env.clock) }
        val state get() = session.view.value.state
        fun receiveFrom(other: Phone) {
            other.env.groups.envelopes(group).envelopes.forEach { env.groups.appendEnvelope(group, it) }
            session.refresh()
        }
        fun epochsHeld() = env.secrets.loadKeyring(group).keyring.epochs()
    }

    private val ownerEnv = TestEnv().also { it.firstRun("Asha", "98765 43210") }
    private val group: GroupId = ownerEnv.groups.createGroup("Goa Trip")
    private val owner = Phone(ownerEnv, group, ownerEnv.groups.group(group)!!.keySlot)

    /** A new phone whose person asks to join; [admitter] writes the admission, and the phones then exchange what they have. */
    private fun joined(name: String, admitter: Phone = owner, phone: String = "98765 0000${name.length}"): Phone {
        val env = TestEnv().also { it.firstRun(name, phone) }
        val slot = env.identity.reserveSlot(emptyList())
        val identity = env.identity.groupIdentity(slot)!!
        val stored = admitter.env.groups.envelopes(group).envelopes.map { StoredEnvelope(it.author, it.counter, it.encode()) }
        env.events.addGroup(GroupRow(group, slot, 0), stored)
        env.secrets.saveGroupKey(group, 1, admitter.env.secrets.loadKeyring(group).keyring[1]!!) // what the invitation would carry
        assertTrue(admitter.session.approveJoin(identity.publicKey, identity.encryptionKey, name, phone) is AddResult.Added)
        val p = Phone(env, group, slot)
        p.receiveFrom(admitter)
        if (admitter !== owner) owner.receiveFrom(admitter)
        return p
    }

    private fun everyoneSyncs(vararg phones: Phone) {
        repeat(2) { for (a in phones) for (b in phones) if (a !== b) a.receiveFrom(b) }
    }

    private fun rupees(n: Long) = Money(n * 100)
    private fun key(n: Int) = PublicKey(ByteArray(32).also { it[0] = n.toByte() })
    private fun added(r: AddResult) = r is AddResult.Added
    private fun refusedAs(r: AddResult) = (r as? AddResult.Refused)?.reason

    // ---- approving a join --------------------------------------------------------------------------------------------

    @Test fun theOwnerAndAnAdminCanApproveAJoinButAMemberCannot() {
        val ben = joined("Ben")
        val chitra = joined("Chitra")
        assertTrue(added(owner.session.makeAdmin(ben.key)))
        everyoneSyncs(owner, ben, chitra)
        val eveEnv = TestEnv().also { it.firstRun("Eve", "98765 00003") }
        val eve = eveEnv.identity.groupIdentity(eveEnv.identity.reserveSlot(emptyList()))!!
        assertEquals(IgnoreReason.NOT_ALLOWED, refusedAs(chitra.session.approveJoin(eve.publicKey, eve.encryptionKey, "Eve", "98765 00003")))
        assertTrue(added(ben.session.approveJoin(eve.publicKey, eve.encryptionKey, "Eve", "98765 00003")), "an admin may")
        assertTrue(added(owner.session.approveJoin(key(60), EncryptionKey(ByteArray(32) { 1 }), "Fay", "98765 00004")), "the owner may")
    }

    @Test fun approvingWithATakenNameIsRefusedWithTheReason() {
        joined("Ben")
        val e = TestEnv().also { it.firstRun("Zed", "98765 00005") }
        val z = e.identity.groupIdentity(e.identity.reserveSlot(emptyList()))!!
        for (name in listOf("Ben", "ben", " BEN ", "Asha")) {
            assertEquals(IgnoreReason.INVALID, refusedAs(owner.session.approveJoin(z.publicKey, z.encryptionKey, name, "98765 00005")), name)
        }
        assertTrue(added(owner.session.approveJoin(z.publicKey, z.encryptionKey, "Ben V", "98765 00005")))
    }

    @Test fun aBlankNameOrABadPhoneGivesAnInvalidResultNotACrash() {
        val e = TestEnv().also { it.firstRun("Zed", "98765 00005") }
        val z = e.identity.groupIdentity(e.identity.reserveSlot(emptyList()))!!
        assertTrue(owner.session.approveJoin(z.publicKey, z.encryptionKey, "   ", "98765 00005") is AddResult.Invalid)
        assertTrue(owner.session.approveJoin(z.publicKey, z.encryptionKey, "Zed", "12ab") is AddResult.Invalid)
        assertTrue(owner.session.updateProfile("", "98765 43210") is AddResult.Invalid)
        assertTrue(owner.session.updateProfile("Asha", "123") is AddResult.Invalid)
    }

    // ---- removing a member and the new key --------------------------------------------------------------------------

    @Test fun onlyTheOwnerCanRemoveAndARefusedRemovalLeavesNoKeyBehind() {
        val ben = joined("Ben")
        val chitra = joined("Chitra")
        owner.session.makeAdmin(ben.key)
        everyoneSyncs(owner, ben, chitra)
        for ((phone, target) in listOf(ben to chitra.key, chitra to ben.key)) {
            val r = phone.session.removeMember(target)
            assertEquals(IgnoreReason.NOT_ALLOWED, refusedAs(r))
            assertEquals(setOf(1L), phone.epochsHeld(), "no key for a removal that was refused")
        }
    }

    @Test fun theNewKeyReachesOnlyThoseWhoStay() {
        val ben = joined("Ben")
        val chitra = joined("Chitra")
        val dev = joined("Dev")
        everyoneSyncs(owner, ben, chitra, dev)
        assertTrue(added(owner.session.removeMember(chitra.key)))
        assertEquals(2L, owner.state.epoch)
        assertTrue(added(owner.session.addExpense("After", rupees(60), owner.key, 20_000, Split.Equal(setOf(owner.key, ben.key, dev.key)))))
        everyoneSyncs(owner, ben, chitra, dev)
        assertEquals(setOf(1L, 2L), ben.epochsHeld())
        assertEquals(setOf(1L, 2L), dev.epochsHeld())
        assertEquals(setOf(1L), chitra.epochsHeld(), "the removed member gets no key")
        assertTrue(ben.state.expenses.values.any { it.description == "After" }, "a member who stays reads the new entry")
        assertTrue(chitra.state.expenses.values.none { it.description == "After" }, "the removed member cannot")
        assertEquals(setOf(2L), chitra.session.view.value.counts.waitingForKey, "her phone keeps the entry but cannot open it")
        assertTrue(Role.MEMBER == ben.state.members.getValue(ben.key).role && chitra.key !in ben.state.currentMembers)
    }

    @Test fun aStaleKeyForTheNextEpochIsReplacedByTheRealOne() {
        val ben = joined("Ben")
        val chitra = joined("Chitra")
        everyoneSyncs(owner, ben, chitra)
        ben.env.secrets.saveGroupKey(group, 2, GroupKey.generate()) // what an interrupted attempt could leave behind
        assertTrue(added(owner.session.removeMember(chitra.key)))
        owner.session.addExpense("After", rupees(10), owner.key, 20_000, Split.Equal(setOf(owner.key, ben.key)))
        everyoneSyncs(owner, ben)
        assertTrue(ben.state.expenses.values.any { it.description == "After" }, "Ben opens epoch 2 with the key from the removal, not the stale one")
    }

    @Test fun removingSomeoneWhoIsNotAMemberIsRefusedAndLeavesNoKey() {
        val r = owner.session.removeMember(key(77))
        assertEquals(IgnoreReason.UNKNOWN_TARGET, refusedAs(r))
        assertEquals(setOf(1L), owner.epochsHeld())
        assertEquals(1L, owner.state.epoch)
    }

    @Test fun theOwnerCannotRemoveThemselves() {
        assertEquals(IgnoreReason.NOT_ALLOWED, refusedAs(owner.session.removeMember(owner.key)))
        assertEquals(setOf(1L), owner.epochsHeld())
    }

    // ---- leaving ---------------------------------------------------------------------------------------------------

    @Test fun aMemberCanLeaveAndAnAdminAndTheOwnerCannot() {
        val ben = joined("Ben")
        val chitra = joined("Chitra")
        owner.session.makeAdmin(ben.key)
        everyoneSyncs(owner, ben, chitra)
        assertEquals(IgnoreReason.NOT_ALLOWED, refusedAs(ben.session.leave()), "an admin")
        assertEquals(IgnoreReason.NOT_ALLOWED, refusedAs(owner.session.leave()), "the owner")
        assertTrue(added(chitra.session.leave()))
    }

    @Test fun aLeaveCarriesNoKeyAndLeavesTheEpochAlone() {
        val chitra = joined("Chitra")
        everyoneSyncs(owner, chitra)
        assertTrue(added(chitra.session.leave()))
        owner.receiveFrom(chitra)
        assertEquals(1L, owner.state.epoch)
        assertEquals(setOf(1L), owner.epochsHeld())
        assertTrue(chitra.key !in owner.state.currentMembers)
        assertTrue(chitra.key in owner.state.members, "still listed, as left")
    }

    @Test fun aLeaverCanWriteNothingMore() {
        val chitra = joined("Chitra")
        everyoneSyncs(owner, chitra)
        chitra.session.leave()
        val r = chitra.session.addExpense("Late", rupees(5), chitra.key, 20_000, Split.Equal(setOf(chitra.key)))
        assertTrue(r is AddResult.Refused, "after leaving: $r")
    }

    // ---- changing the key -----------------------------------------------------------------------------------------

    @Test fun onlyTheOwnerCanChangeTheKeyAndEveryoneWhoStaysGetsIt() {
        val ben = joined("Ben")
        val chitra = joined("Chitra")
        owner.session.makeAdmin(ben.key)
        everyoneSyncs(owner, ben, chitra)
        assertEquals(IgnoreReason.NOT_ALLOWED, refusedAs(ben.session.rotateKey()))
        assertEquals(IgnoreReason.NOT_ALLOWED, refusedAs(chitra.session.rotateKey()))
        assertEquals(setOf(1L), ben.epochsHeld())
        assertTrue(added(owner.session.rotateKey()))
        owner.session.addExpense("Next", rupees(5), owner.key, 20_000, Split.Equal(setOf(owner.key)))
        everyoneSyncs(owner, ben, chitra)
        assertEquals(setOf(1L, 2L), ben.epochsHeld())
        assertEquals(setOf(1L, 2L), chitra.epochsHeld())
        assertTrue(chitra.state.expenses.values.any { it.description == "Next" })
    }

    // ---- admins and ownership ------------------------------------------------------------------------------------

    @Test fun onlyTheOwnerMakesAndUnmakesAdmins() {
        val ben = joined("Ben")
        val chitra = joined("Chitra")
        everyoneSyncs(owner, ben, chitra)
        assertEquals(IgnoreReason.NOT_ALLOWED, refusedAs(ben.session.makeAdmin(chitra.key)))
        assertTrue(added(owner.session.makeAdmin(ben.key)))
        assertEquals(IgnoreReason.ALREADY_DONE, refusedAs(owner.session.makeAdmin(ben.key)))
        everyoneSyncs(owner, ben, chitra)
        assertEquals(IgnoreReason.NOT_ALLOWED, refusedAs(ben.session.removeAdmin(ben.key)))
        assertTrue(added(owner.session.removeAdmin(ben.key)))
        assertEquals(Role.MEMBER, owner.state.members.getValue(ben.key).role)
    }

    @Test fun ownershipCanBeOfferedDeclinedOfferedAgainAndAccepted() {
        val ben = joined("Ben")
        val chitra = joined("Chitra")
        everyoneSyncs(owner, ben, chitra)
        assertEquals(IgnoreReason.NOT_ALLOWED, refusedAs(ben.session.offerOwnership(chitra.key)), "a member cannot offer")
        assertTrue(added(owner.session.offerOwnership(ben.key)))
        everyoneSyncs(owner, ben, chitra)
        assertEquals(ben.key, ben.state.pendingOffer)
        assertEquals(IgnoreReason.NOT_ALLOWED, refusedAs(chitra.session.acceptOwnership()), "not offered to her")
        assertEquals(IgnoreReason.NOT_ALLOWED, refusedAs(chitra.session.declineOwnership()))
        assertTrue(added(ben.session.declineOwnership()))
        everyoneSyncs(owner, ben, chitra)
        assertNull(owner.state.pendingOffer)
        assertEquals(owner.key, owner.state.owner)
        assertTrue(added(owner.session.offerOwnership(ben.key)))
        everyoneSyncs(owner, ben, chitra)
        assertTrue(added(ben.session.acceptOwnership()))
        everyoneSyncs(owner, ben, chitra)
        for (p in listOf(owner, ben, chitra)) {
            assertEquals(ben.key, p.state.owner)
            assertEquals(Role.ADMIN, p.state.members.getValue(owner.key).role, "the old owner is an admin")
        }
    }

    @Test fun theGroupsListTagsTheOfferOnlyOnTheOfferedPersonsPhone() {
        val ben = joined("Ben")
        assertTrue(added(owner.session.offerOwnership(ben.key)))
        everyoneSyncs(owner, ben)
        fun tag(p: Phone) = Sessions(p.env.groups, p.env.secrets, p.env.identity, p.env.clock, p.env.settings).also { it.refresh() }.summaries.value.single().ownershipOfferForMe
        assertTrue(tag(ben), "the offered person sees the tag")
        assertFalse(tag(owner), "the owner does not")
        assertTrue(added(ben.session.declineOwnership()))
        everyoneSyncs(owner, ben)
        assertFalse(tag(ben), "it goes away when answered")
        assertEquals(ben.key, owner.state.declinedOffer, "and the owner can see who declined")
    }

    @Test fun anOfferCanBeCancelledByTheOwnerOnly() {
        val ben = joined("Ben")
        everyoneSyncs(owner, ben)
        owner.session.offerOwnership(ben.key)
        everyoneSyncs(owner, ben)
        assertEquals(IgnoreReason.NOT_ALLOWED, refusedAs(ben.session.cancelOffer()))
        assertTrue(added(owner.session.cancelOffer()))
        everyoneSyncs(owner, ben)
        assertNull(ben.state.pendingOffer)
    }

    // ---- profile ---------------------------------------------------------------------------------------------------

    @Test fun aProfileChangeIsSeenByEveryoneAndANameClashIsRefused() {
        val ben = joined("Ben")
        val chitra = joined("Chitra")
        everyoneSyncs(owner, ben, chitra)
        assertEquals(IgnoreReason.INVALID, refusedAs(ben.session.updateProfile("chitra", "98765 11111")), "someone else's name, other capitals")
        assertTrue(added(ben.session.updateProfile("BEN", "(022) 2345-6789")), "only the capitals of your own name")
        assertTrue(added(ben.session.updateProfile("Ben Kumar", "+91 98765 22222")))
        everyoneSyncs(owner, ben, chitra)
        assertEquals("Ben Kumar", owner.state.members.getValue(ben.key).name)
        assertEquals("+91 98765 22222", chitra.state.members.getValue(ben.key).phone)
    }

    @Test fun anEventTheRulesRefuseIsNeverStored() {
        val ben = joined("Ben")
        everyoneSyncs(owner, ben)
        val before = ben.env.groups.envelopes(group).envelopes.size
        ben.session.removeMember(owner.key)
        ben.session.rotateKey()
        ben.session.makeAdmin(ben.key)
        assertEquals(before, ben.env.groups.envelopes(group).envelopes.size)
        assertFalse(ben.state.ignored.isNotEmpty())
        assertNotEquals(null, ben.state.owner)
    }
}
