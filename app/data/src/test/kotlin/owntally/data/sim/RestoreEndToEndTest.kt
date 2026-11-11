package owntally.data.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import owntally.crypto.Bip39
import owntally.crypto.KeySlots
import owntally.crypto.Ticket
import owntally.data.repo.TestEnv
import owntally.data.secrets.SecretLoad
import owntally.data.session.AddResult
import owntally.data.session.CheckResult
import owntally.data.session.GroupJoinBackend
import owntally.data.session.Invites
import owntally.data.session.JoinGroup
import owntally.data.session.JoinRequests
import owntally.data.session.OpenResult
import owntally.data.session.PreviewState
import owntally.data.session.RepositorySyncStore
import owntally.data.session.RequestResult
import owntally.data.session.Sessions
import owntally.data.store.InMemoryJoinRequestStore
import owntally.data.store.InMemoryPendingJoinStore
import owntally.data.store.PendingState
import owntally.model.GroupId
import owntally.model.GroupIds
import owntally.model.Money
import owntally.model.Split
import owntally.sync.GroupRoom
import owntally.sync.InMemoryNetwork
import owntally.sync.JoinHost
import owntally.sync.SyncEngine
import owntally.sync.SyncOutcome
import owntally.sync.SyncRole

/**
 * M11, T11.7: the acceptance test of docs/tasks/M11-restore.md. A person with three groups (one with key changes since they joined, one where they
 * are an admin, one they left and came back to) loses the phone. A new phone with only the recovery phrase rejoins each group from a friend's
 * invitation, with no approval, and ends with the same balances as everyone else; another person's phrase gets nothing.
 */
class RestoreEndToEndTest {
    private val net = InMemoryNetwork()
    private val approved = mutableListOf<GroupId>()
    private val phrase = Bip39.fromEntropy(ByteArray(16) { 21 })
    private val otherPhrase = Bip39.fromEntropy(ByteArray(16) { 22 })

    private inner class Host(val device: SimDevice) {
        val group: GroupId get() = device.group
        val sessions = Sessions(device.env.groups, device.env.secrets, device.env.identity, device.env.clock, device.env.settings)
        val invites = Invites(owntally.crypto.InMemoryTicketRecords(), device.env.groups, device.env.secrets, device.env.identity, sessions, device.env.clock)
        val requests = JoinRequests(InMemoryJoinRequestStore(), sessions, device.env.clock)
        val backend = GroupJoinBackend(group, device.env.identity.groupIdentity(device.env.groups.group(group)!!.keySlot)!!, invites, requests, sessions)
        val room = GroupRoom.fromGroupId(group.bytes())
        fun link() = invites.create(group)!!.link
        fun view() = sessions.session(group).view.value
        fun TestScope.serve(): kotlinx.coroutines.Job {
            val incoming = net.transportFor(device.key).incoming(room)
            return backgroundScope.launch { incoming.collect { l -> launch { JoinHost(backend, room, stallTimeoutMs = 5_000).serve(l) } } }
        }
    }

    private fun phone(p: owntally.crypto.RecoveryPhrase) = TestEnv().also { it.identity.completeFirstRun(p, "Rohan", "98765 43210") }
    private val oldRohan = phone(phrase) // only to derive the keys the old phone had; the old phone itself is gone
    private fun rohanKey(group: GroupId, generation: Long) = oldRohan.identity.groupIdentity(KeySlots.member(group, generation))!!
    private fun admit(host: Host, generation: Long = 0, name: String = "Rohan"): owntally.crypto.GroupIdentity {
        val k = rohanKey(host.group, generation)
        assertTrue(host.device.session.approveJoin(k.publicKey, k.encryptionKey, name, "98765 43210") is AddResult.Added)
        return k
    }
    private fun pay(host: Host, who: SimDevice, total: Long, vararg split: owntally.model.PublicKey, description: String = "Dinner") =
        assertTrue(who.session.addExpense(description, Money(total), who.key, 20_000, Split.Equal(split.toSet())) is AddResult.Added)

    private fun joinOf(env: TestEnv) =
        JoinGroup(env.groups, env.secrets, env.identity, InMemoryPendingJoinStore(), Sessions(env.groups, env.secrets, env.identity, env.clock, env.settings), env.clock, { _, key -> net.transportFor(key) }, { g, _ -> approved += g }, connectTimeoutMs = 1_000)

    private suspend fun syncWith(host: Host, env: TestEnv) {
        val slot = env.groups.group(host.group)!!.keySlot
        val ours = net.transportFor(env.identity.groupIdentity(slot)!!.publicKey)
        val incoming = net.transportFor(host.device.key).incoming(host.room)
        kotlinx.coroutines.coroutineScope {
            val server = launch { SyncEngine(host.room, RepositorySyncStore(host.group, host.device.env.groups)).run(incoming.first(), SyncRole.RESPONDER) }
            SyncEngine(host.room, RepositorySyncStore(host.group, env.groups)).run(ours.connect(host.room, host.device.key), SyncRole.INITIATOR)
            server.join()
        }
        host.sessions.session(host.group).refresh()
    }

    @Test fun aWipedPhoneRestoredFromThePhraseRejoinsEveryGroupWithNoApprovalAndTheSameBalances() = runTest {
        // Group 1: Rohan joined, then the key changed twice and entries were written in every epoch.
        val g1 = Host(SimDevice.owner(41, "Asha"))
        val r1 = admit(g1)
        pay(g1, g1.device, 30_000, g1.device.key, r1.publicKey)
        assertTrue(g1.device.session.rotateKey() is AddResult.Added)
        pay(g1, g1.device, 12_000, g1.device.key, r1.publicKey, description = "Taxi")
        assertTrue(g1.device.session.rotateKey() is AddResult.Added)
        pay(g1, g1.device, 4_000, g1.device.key, r1.publicKey, description = "Tea")
        // Group 2: Rohan is an admin.
        val g2 = Host(SimDevice.owner(42, "Ben"))
        val r2 = admit(g2)
        assertTrue(g2.device.session.makeAdmin(r2.publicKey) is AddResult.Added)
        pay(g2, g2.device, 9_000, g2.device.key, r2.publicKey)
        // Group 3: Rohan left (the owner removed his first key) and came back under the next generation.
        val g3 = Host(SimDevice.owner(43, "Chitra"))
        val r3a = admit(g3, 0)
        assertTrue(g3.device.session.removeMember(r3a.publicKey) is AddResult.Added)
        val r3b = admit(g3, 1, "Rohan R") // the removed key keeps the name "Rohan": the admin gives the returning member another
        pay(g3, g3.device, 6_000, g3.device.key, r3b.publicKey)

        // The old phone is gone. The new one has the phrase and nothing else.
        val env = phone(phrase)
        val join = joinOf(env)
        val sessions = Sessions(env.groups, env.secrets, env.identity, env.clock, env.settings)
        val servers = listOf(g1, g2, g3).map { h -> with(h) { serve() } }
        for (h in listOf(g1, g2, g3)) assertEquals(OpenResult.Restored(h.group), join.open(h.link()), h.device.name)
        servers.forEach { it.cancel() }
        assertEquals(setOf(g1.group, g2.group, g3.group), approved.toSet(), "a sync is started for each")
        for (h in listOf(g1, g2, g3)) {
            assertEquals(emptyList(), h.requests.waiting(h.group), "nobody had to approve anything in ${h.device.name}'s group")
            syncWith(h, env)
            sessions.session(h.group).refresh()
        }
        for ((h, key) in listOf(g1 to r1, g2 to r2, g3 to r3b)) {
            val mine = sessions.session(h.group).view.value
            assertEquals(key.publicKey, mine.me, "the same member as before in ${h.device.name}'s group")
            assertTrue(mine.me in mine.state.currentMembers)
            assertEquals(h.view().state.expenses.size, mine.state.expenses.size, "every expense, ${h.device.name}'s group")
            assertEquals(h.view().balances, mine.balances, "the same balances, ${h.device.name}'s group")
            assertEquals(0, mine.counts.waitingForKey.size)
        }
        assertEquals(3, sessions.session(g1.group).view.value.state.expenses.size, "one written in each of the three epochs, read through the chain of keys")
        assertEquals(owntally.model.Role.ADMIN, sessions.session(g2.group).view.value.state.members.getValue(r2.publicKey).role)
        assertEquals(KeySlots.member(g3.group, 1), env.groups.group(g3.group)!!.keySlot)
    }

    @Test fun anotherPersonsPhraseRestoresAnIdentityNoGroupKnowsAndGetsNothing() = runTest {
        val g1 = Host(SimDevice.owner(41, "Asha"))
        admit(g1)
        pay(g1, g1.device, 30_000, g1.device.key)
        with(g1) { serve() }
        val eve = phone(otherPhrase)
        val opened = assertIs<OpenResult.Opened>(joinOf(eve).open(g1.link()))
        assertEquals(KeySlots.member(g1.group, 0), opened.join.keySlot)
        assertNull(eve.groups.group(g1.group), "no group, no history, without a request an admin approves")
        assertEquals(emptyList(), approved)
        assertEquals(emptyList(), g1.requests.waiting(g1.group))
    }

    @Test fun theSamePhraseOnAPhoneThatAlreadyHasAnIdentityIsRefused() {
        val env = phone(phrase)
        assertFailsWith<IllegalStateException> { env.identity.completeFirstRun(phrase, "Rohan", "98765 43210") }
    }
}
