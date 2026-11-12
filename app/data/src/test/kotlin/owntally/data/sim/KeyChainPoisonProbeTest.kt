package owntally.data.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import owntally.crypto.EventSealing
import owntally.crypto.GroupKey
import owntally.data.repo.TestEnv
import owntally.data.session.AddResult
import owntally.data.session.GroupJoinBackend
import owntally.data.session.Invites
import owntally.data.session.JoinGroup
import owntally.data.session.JoinRequests
import owntally.data.session.RepositorySyncStore
import owntally.data.session.Sessions
import owntally.data.store.InMemoryJoinRequestStore
import owntally.data.store.InMemoryPendingJoinStore
import owntally.model.Envelope
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.GroupId
import owntally.model.Money
import owntally.model.PreviousKey
import owntally.model.PublicKey
import owntally.model.Split
import owntally.sync.GroupRoom
import owntally.sync.InMemoryNetwork
import owntally.sync.InMemorySyncStore
import owntally.sync.JoinHost
import owntally.sync.SyncEngine
import owntally.sync.SyncRole
import owntally.sync.SyncStore

/**
 * REVIEW PROBE (12 Nov 2026): can a malicious member poison a late joiner's key chain?
 *
 * `adoptLinkedKeys` trusts a `KeyLinked` from anyone, on the grounds that "a key is stored only if it opens an entry of that epoch". But a member can
 * sign any envelope for any epoch number, encrypted under any key they choose. So Mallory (a plain member) writes a fake `KeyLinked(2, WRONG)` and a
 * crafted epoch-1 envelope sealed under WRONG, and withholds Asha's real link (she runs her own software). A late joiner who syncs with Mallory first
 * adopts WRONG as epoch 1's key, stores it, and can then never read the real epoch 1, even after syncing with the honest owner.
 *
 * This test PASSES while the bug is present: it asserts the attack succeeds (finding R7, docs/SECURITY_REVIEW.md section 6). When the fix lands, invert
 * the assertions (the wrong key is never adopted, Dinner is read) and this file becomes the regression test.
 */
class KeyChainPoisonProbeTest {
    private val asha = SimDevice.owner(21, "Asha")
    private val mallory = SimDevice.joined(21, 1, "Mallory", asha)
    private val group: GroupId = asha.group
    private val room = GroupRoom.fromGroupId(group.bytes())
    private val net = InMemoryNetwork()
    private val hostSessions = Sessions(asha.env.groups, asha.env.secrets, asha.env.identity, asha.env.clock, asha.env.settings)
    private val invites = Invites(owntally.crypto.InMemoryTicketRecords(), asha.env.groups, asha.env.secrets, asha.env.identity, hostSessions, asha.env.clock)
    private val requests = JoinRequests(InMemoryJoinRequestStore(), hostSessions, asha.env.clock)
    private val rohanEnv = TestEnv().also { it.firstRun("Rohan", "98765 43210") }
    private val pendingStore = InMemoryPendingJoinStore()
    private val rohanSessions = Sessions(rohanEnv.groups, rohanEnv.secrets, rohanEnv.identity, rohanEnv.clock, rohanEnv.settings)
    private fun rohan() = JoinGroup(rohanEnv.groups, rohanEnv.secrets, rohanEnv.identity, pendingStore, rohanSessions, rohanEnv.clock, { _, key -> net.transportFor(key) }, { _, _ -> }, connectTimeoutMs = 1_000)
    private val ashaSession get() = hostSessions.session(group)

    private fun rohanKey(): PublicKey = rohanEnv.identity.groupIdentity(pendingStore.get(group)?.keySlot ?: rohanEnv.groups.group(group)!!.keySlot)!!.publicKey

    /** Rohan (initiator) syncs with [peerKey], who serves [peerStore]. */
    private suspend fun rohanSyncsWith(peerKey: PublicKey, peerStore: SyncStore) {
        val rohanStore = RepositorySyncStore(group, rohanEnv.groups)
        val ours = net.transportFor(rohanKey())
        val inc = net.transportFor(peerKey).incoming(room)
        coroutineScope {
            val srv = launch { val l = inc.first(); SyncEngine(room, peerStore).run(l, SyncRole.RESPONDER) }
            SyncEngine(room, rohanStore).run(ours.connect(room, peerKey), SyncRole.INITIATOR)
            srv.join()
        }
        rohanSessions.session(group).refresh()
    }

    private suspend fun syncPair(a: SimDevice, b: SimDevice) {
        val sa = RepositorySyncStore(group, a.env.groups); val sb = RepositorySyncStore(group, b.env.groups)
        val inc = net.transportFor(b.key).incoming(room)
        coroutineScope {
            val srv = launch { val l = inc.first(); SyncEngine(room, sb).run(l, SyncRole.RESPONDER) }
            SyncEngine(room, sa).run(net.transportFor(a.key).connect(room, b.key), SyncRole.INITIATOR)
            srv.join()
        }
        a.session.refresh(); b.session.refresh()
    }

    private suspend fun TestScope.rohanIsAdmitted() {
        val backend = GroupJoinBackend(group, asha.env.identity.groupIdentity(asha.slot)!!, invites, requests, hostSessions)
        val incoming = net.transportFor(asha.key).incoming(room)
        val server = backgroundScope.launch { incoming.collect { link -> launch { JoinHost(backend, room, stallTimeoutMs = 5_000).serve(link) } } }
        val link = invites.create(group)!!.link
        rohan().open(link); rohan().request(group, "Rohan", "98765 43210")
        val member = requests.waiting(group).single().member
        requests.approve(group, member, "Rohan V")
        rohan().check(group)
        server.cancel()
    }

    private fun nextCounter(dev: SimDevice): Long =
        (dev.env.groups.envelopes(group).envelopes.filter { it.author == dev.key }.maxOfOrNull { it.counter } ?: 0L) + 1

    @Test fun aMaliciousMemberCanPoisonALateJoinersKeyChainForEver() = runTest {
        // Epoch 1 has real history; then the owner changes the key (epoch 2) and writes the real KeyLinked(2, k1).
        assertTrue(ashaSession.addExpense("Dinner", Money(30_000), asha.key, 20_000, Split.Equal(setOf(asha.key))) is AddResult.Added)
        assertTrue(ashaSession.rotateKey() is AddResult.Added)
        rohanIsAdmitted()
        // Mallory, a plain member, syncs with Asha: she now holds k1 and k2 (HPKE) and sees Rohan's admission.
        syncPair(mallory, asha)
        val k2 = mallory.env.secrets.loadKeyring(group).keyring[2]!!
        assertTrue(mallory.env.secrets.loadKeyring(group).keyring[1] != null)

        // --- the attack: a fake link, and a crafted epoch-1 entry that opens under the fake key -------------------------------------------
        val malloryId = mallory.env.identity.groupIdentity(mallory.slot)!!
        val wrong = GroupKey.generate()
        var c = nextCounter(mallory)
        val fakeLink = Event(1, mallory.key, c, if (c > 1) mapOf(mallory.key to c - 1) else emptyMap(), 0, EventBody.KeyLinked(2, PreviousKey(wrong.bytes())))
        mallory.env.groups.appendEnvelope(group, EventSealing.seal(malloryId, group, 2, k2, fakeLink)) // in epoch 2, under the real k2: Rohan can open it
        c++
        val bait = Event(1, mallory.key, c, mapOf(mallory.key to c - 1), 0, EventBody.ProfileUpdated("Mallory", "98765 00001"))
        mallory.env.groups.appendEnvelope(group, EventSealing.seal(malloryId, group, 1, wrong, bait)) // "epoch 1", but under WRONG: it opens under WRONG

        // Mallory serves everything she has except Asha's real link (she runs her own software and can leave out what she likes).
        val realLink = hostSessions.session(group).view.value.state.keyLinks.getValue(2L)
        val served = InMemorySyncStore(group)
        mallory.env.groups.rawEnvelopes(group).forEach { bytes ->
            val e = Envelope.decode(bytes)
            if (!(e.author == realLink.author && e.counter == realLink.counter)) served.load(bytes)
        }

        // Rohan (ticket for epoch 2, holds k2 only) syncs with Mallory first.
        rohanSyncsWith(mallory.key, served)
        val ring1 = rohanEnv.secrets.loadKeyring(group).keyring[1]
        println("PROBE after Mallory: epoch-1 key adopted = ${ring1 != null}, isWRONG = ${ring1?.bytes()?.contentEquals(wrong.bytes())}, expenses = ${rohanSessions.session(group).view.value.state.expenses.size}, unreadable = ${rohanSessions.session(group).view.value.counts.unreadable}")
        assertTrue(ring1 != null && ring1.bytes().contentEquals(wrong.bytes()), "Rohan adopted Mallory's WRONG key as epoch 1's")
        assertEquals(0, rohanSessions.session(group).view.value.state.expenses.size, "and cannot read the real epoch 1 (Dinner)")

        // Then Rohan syncs with the honest owner, who sends the real link: the poison is permanent.
        rohanSyncsWith(asha.key, RepositorySyncStore(group, asha.env.groups))
        val ring1After = rohanEnv.secrets.loadKeyring(group).keyring[1]
        println("PROBE after Asha: isWRONG = ${ring1After?.bytes()?.contentEquals(wrong.bytes())}, expenses = ${rohanSessions.session(group).view.value.state.expenses.size}, unreadable = ${rohanSessions.session(group).view.value.counts.unreadable}, name = ${rohanSessions.session(group).view.value.state.name}")
        assertTrue(ring1After!!.bytes().contentEquals(wrong.bytes()), "the real link from the owner does not displace the poisoned key")
        assertEquals(0, rohanSessions.session(group).view.value.state.expenses.size, "Rohan never sees Dinner, nor the group's creation")
    }

    /** The lazier attacker: Mallory withholds nothing. Whether the poison lands then depends only on which link is tried first (author key order). */
    @Test fun withoutWithholdingItDependsOnlyOnKeyOrder() = runTest {
        assertTrue(ashaSession.addExpense("Dinner", Money(30_000), asha.key, 20_000, Split.Equal(setOf(asha.key))) is AddResult.Added)
        assertTrue(ashaSession.rotateKey() is AddResult.Added)
        rohanIsAdmitted()
        syncPair(mallory, asha)
        val k2 = mallory.env.secrets.loadKeyring(group).keyring[2]!!
        val malloryId = mallory.env.identity.groupIdentity(mallory.slot)!!
        val wrong = GroupKey.generate()
        var c = nextCounter(mallory)
        mallory.env.groups.appendEnvelope(group, EventSealing.seal(malloryId, group, 2, k2, Event(1, mallory.key, c, if (c > 1) mapOf(mallory.key to c - 1) else emptyMap(), 0, EventBody.KeyLinked(2, PreviousKey(wrong.bytes())))))
        c++
        mallory.env.groups.appendEnvelope(group, EventSealing.seal(malloryId, group, 1, wrong, Event(1, mallory.key, c, mapOf(mallory.key to c - 1), 0, EventBody.ProfileUpdated("Mallory", "98765 00001"))))
        rohanSyncsWith(mallory.key, RepositorySyncStore(group, mallory.env.groups)) // the full store, real link included
        val poisoned = rohanEnv.secrets.loadKeyring(group).keyring[1]!!.bytes().contentEquals(wrong.bytes())
        println("PROBE no-withholding: mallorySortsBeforeAsha = ${mallory.key < asha.key}, poisoned = $poisoned, expenses = ${rohanSessions.session(group).view.value.state.expenses.size}")
    }
}
