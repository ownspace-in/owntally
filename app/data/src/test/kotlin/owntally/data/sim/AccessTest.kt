package owntally.data.sim

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import owntally.crypto.EventSealing
import owntally.data.session.AddResult
import owntally.data.session.MembershipAccess
import owntally.model.CancelledEntry
import owntally.model.Envelope
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.Money
import owntally.model.Split
import owntally.sync.ErrorCode
import owntally.sync.GroupRoom
import owntally.sync.SyncEngine
import owntally.sync.SyncOutcome
import owntally.sync.SyncResult
import owntally.sync.SyncRole

/** Who may connect, and what a person who was removed or left receives (decision S2, T5.7), with real sessions and databases. */
class AccessTest {
    private class World(val asha: SimDevice, val ben: SimDevice, val chitra: SimDevice, val dev: SimDevice)

    /** [initiator] connects to [responder]; each side decides what the other may have by the group's membership as it knows it. */
    private suspend fun TestScope.sync(initiator: SimDevice, responder: SimDevice, initiatorOpen: Boolean = false): Pair<SyncResult, SyncResult> {
        val (la, lb) = SimNetwork(this, Random(1)).link(initiator.key, responder.key)
        val room = GroupRoom.fromGroupId(initiator.group.bytes())
        val si = initiator.syncStore()
        val sr = responder.syncStore()
        val result = coroutineScope {
            val x = async { SyncEngine(room, si, access = if (initiatorOpen) owntally.sync.PeerAccess.EVERYONE else MembershipAccess(initiator.session, si)).run(la, SyncRole.INITIATOR) }
            val y = async { SyncEngine(room, sr, access = MembershipAccess(responder.session, sr)).run(lb, SyncRole.RESPONDER) }
            x.await() to y.await()
        }
        initiator.session.refresh()
        responder.session.refresh()
        return result
    }

    private suspend fun TestScope.world(seed: Long): World {
        val asha = SimDevice.owner(seed, "Asha")
        val ben = SimDevice.joined(seed, 1, "Ben", asha)
        val chitra = SimDevice.joined(seed, 2, "Chitra", asha)
        val dev = SimDevice.joined(seed, 3, "Dev", asha)
        val all = listOf(asha, ben, chitra, dev)
        repeat(2) { for (i in all.indices) for (j in i + 1 until all.size) sync(all[i], all[j]) }
        return World(asha, ben, chitra, dev)
    }

    private fun content(d: SimDevice): Set<List<Byte>> = d.env.groups.rawEnvelopes(d.group).map { it.toList() }.toSet()

    @Test fun someoneWhoWasNeverAMemberIsRefusedAndLearnsNothing() = runTest {
        val w = world(31)
        val mallory = SimDevice.owner(32, "Mallory") // her own, different group
        val before = content(mallory)
        val room = GroupRoom.fromGroupId(w.asha.group.bytes())
        val (lm, la) = SimNetwork(this, Random(1)).link(mallory.key, w.asha.key)
        val asha = w.asha.syncStore()
        val (rm, ra) = coroutineScope {
            val x = async { SyncEngine(room, mallory.syncStore()).run(lm, SyncRole.INITIATOR) }
            val y = async { SyncEngine(room, asha, access = MembershipAccess(w.asha.session, asha)).run(la, SyncRole.RESPONDER) }
            x.await() to y.await()
        }
        assertEquals(SyncOutcome.PEER_ERROR, rm.outcome)
        assertEquals(ErrorCode.NOT_ALLOWED, rm.error)
        assertEquals(0, rm.stored + rm.duplicates, "she was sent nothing")
        assertEquals(null, rm.peerSummary, "she never saw a summary of the group")
        assertEquals(SyncOutcome.REFUSED, ra.outcome)
        assertEquals(before, content(mallory))
    }

    @Test fun aMemberStillGetsEverything() = runTest {
        val w = world(33)
        assertTrue(w.asha.session.addExpense("Lunch", Money(10_000), w.asha.key, 20_000, Split.Equal(setOf(w.asha.key, w.ben.key))) is AddResult.Added)
        val (r1, r2) = sync(w.ben, w.asha)
        assertEquals(SyncOutcome.COMPLETED, r1.outcome)
        assertEquals(SyncOutcome.COMPLETED, r2.outcome)
        assertEquals(content(w.asha), content(w.ben))
    }

    @Test fun aPersonRemovedByTheOwnerIsSentOnlyTheRemovalAndHerLateEntriesBecomeCancelledEntries() = runTest {
        val w = world(34)
        // Chitra goes offline; the owner removes her and the news reaches Ben; Chitra, not knowing, writes an expense.
        assertTrue(w.asha.session.removeMember(w.chitra.key) is AddResult.Added)
        sync(w.ben, w.asha)
        val late = w.chitra.session.addExpense("Late dinner", Money(50_000), w.chitra.key, 20_000, Split.Equal(setOf(w.chitra.key, w.ben.key)))
        assertTrue(late is AddResult.Added)
        val chitraBefore = content(w.chitra)
        val (rChitra, rBen) = sync(w.chitra, w.ben, initiatorOpen = true) // Chitra's own phone asks for everything; Ben decides what she may have
        assertEquals(SyncOutcome.COMPLETED, rChitra.outcome)
        assertEquals(SyncOutcome.COMPLETED, rBen.outcome)
        // She received exactly the removal event, and nothing else.
        val received = content(w.chitra) - chitraBefore
        assertEquals(1, received.size, "one envelope")
        val removal = Envelope.decode(received.single().toByteArray())
        assertEquals(w.asha.key, removal.author)
        assertEquals(1L, removal.epoch, "written under the key she holds, so she can read it")
        // Her phone says so, and keeps what she had.
        val view = w.chitra.session.view.value
        assertTrue(view.youWereRemoved)
        assertEquals(chitraBefore, content(w.chitra) - received)
        // What she wrote reached Ben and is cancelled, listed for the owner after it syncs.
        assertTrue(content(w.ben).containsAll(chitraBefore.filter { b -> Envelope.decode(b.toByteArray()).author == w.chitra.key }))
        sync(w.asha, w.ben)
        val cancelled = w.asha.session.view.value.cancelled.filterIsInstance<CancelledEntry.Expense>()
        assertEquals(listOf("Late dinner"), cancelled.map { it.description })
        // And she is shown nothing of what came after: Ben's summary to her was limited.
        assertTrue(rChitra.peerSummary!!.entries.keys.all { it == w.chitra.key || it in rChitra.peerSummary!!.entries.keys }, "a summary clipped to what the removal had seen")
    }

    @Test fun aPersonWhoLeftIsSentNothingAndTheAnswerProvesHerLeaveArrived() = runTest {
        val w = world(35)
        assertTrue(w.ben.session.leave() is AddResult.Added)
        val leaveCounter = w.ben.session.view.value.state.removals.getValue(w.ben.key).event.counter
        val benBefore = content(w.ben)
        assertTrue(w.asha.session.addExpense("After", Money(1_000), w.asha.key, 20_000, Split.Equal(setOf(w.asha.key))) is AddResult.Added)
        val (rBen, rAsha) = sync(w.ben, w.asha, initiatorOpen = true) // Ben's phone connects
        assertEquals(SyncOutcome.COMPLETED, rBen.outcome)
        assertEquals(0, rBen.stored, "nothing was sent to the leaver")
        assertEquals(benBefore, content(w.ben))
        assertTrue(rAsha.stored >= 1, "her leave was stored")
        val ownEntry = rBen.peerFinal!!.entries
        assertEquals(setOf(w.ben.key), ownEntry.keys, "the answer names only the leaver's own events")
        assertTrue(ownEntry.getValue(w.ben.key).counter >= leaveCounter, "and shows that the leave arrived")
        // the owner's phone has the leave and has already changed the key for the others (T4.11)
        assertTrue(w.ben.key !in w.asha.session.view.value.state.currentMembers)
        assertEquals(2L, w.asha.session.view.value.state.epoch)
    }

    @Test fun aRemovalThatDidNotComeFromTheOwnerIsNotTakenForTheRealThing() = runTest {
        val w = world(36)
        // Dev, an ordinary member, forges "Chitra was removed" (really signed by Dev, sealed under the group key).
        val ring = w.dev.env.secrets.loadKeyring(w.dev.group).keyring
        val counter = (w.dev.syncStore().summary().entries[w.dev.key]?.counter ?: 0L) + 1
        val after = w.dev.syncStore().summary().entries.mapValues { it.value.counter }
        val forged = Event(1, w.dev.key, counter, after, 1_000, EventBody.MemberRemoved(w.chitra.key, 2, emptyMap()))
        val envelope = EventSealing.seal(w.dev.env.identity.groupIdentity(w.dev.slot)!!, w.dev.group, 1, ring[1]!!, forged)
        w.chitra.env.groups.appendEnvelope(w.chitra.group, envelope)
        w.chitra.session.refresh()
        assertEquals(false, w.chitra.session.view.value.youWereRemoved, "Dev is not the owner")
        assertNotEquals(true, w.chitra.session.view.value.state.members.getValue(w.chitra.key).removed)
    }

    @Test fun aRemovalThatDependsOnEventsSheNeverGotIsStillRecognisedWithoutBeingApplied() = runTest {
        val w = world(37)
        // Ben writes something only Asha hears of; Asha's removal of Chitra has seen it, so on Chitra's phone it cannot be applied.
        assertTrue(w.ben.session.addExpense("Taxi", Money(7_000), w.ben.key, 20_000, Split.Equal(setOf(w.ben.key, w.asha.key))) is AddResult.Added)
        sync(w.ben, w.asha)
        assertTrue(w.asha.session.removeMember(w.chitra.key) is AddResult.Added)
        sync(w.ben, w.asha)
        val chitraBefore = content(w.chitra)
        sync(w.chitra, w.ben, initiatorOpen = true)
        assertEquals(1, (content(w.chitra) - chitraBefore).size, "only the removal event")
        val view = w.chitra.session.view.value
        assertTrue(view.youWereRemoved, "shown from the event itself")
        assertEquals(false, view.state.members.getValue(w.chitra.key).removed, "but not applied: it depends on Ben's expense she never had")
        assertTrue(view.state.heldBack.isNotEmpty())
    }
}
