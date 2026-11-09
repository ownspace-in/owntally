package owntally.data.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import owntally.data.repo.TestEnv
import owntally.data.store.GroupRow
import owntally.data.store.StoredEnvelope
import owntally.model.GroupId
import owntally.model.Money
import owntally.model.PublicKey
import owntally.model.Split

/**
 * Found by the simulation of M8 (T8.7, seed 13): two phones each believe they are the owner (an ownership acceptance and the owner's newer
 * offer or cancel were written at the same time), a member leaves, and both change the key for the same epoch. The rules accept one and
 * refuse the other; the phone whose key change was refused had already written entries under its own key, which nobody else can open, and
 * every later entry of its depends on them. The repair: when a phone replaces its refused key by the accepted one it seals its own entries
 * again under the accepted key (same entry, same id), so that the others can read them.
 */
class KeyChangeRaceTest {
    private class Phone(val env: TestEnv, val group: GroupId, val slot: Long) {
        val key: PublicKey get() = env.identity.groupIdentity(slot)!!.publicKey
        val session: GroupSession by lazy { GroupSession(group, env.groups, env.secrets, env.identity, env.clock) }
        val view get() = session.view.value
        fun receiveFrom(other: Phone) {
            other.env.groups.envelopes(group).envelopes.forEach { env.groups.appendEnvelope(group, it) }
            session.refresh()
        }
    }

    /** A box that can be made to fail, to stop a phone between sealing its entries again and storing the key it replaces (a crash). */
    private class FlakyBox(private val inner: owntally.data.secrets.SecretBox) : owntally.data.secrets.SecretBox {
        @Volatile var failing = false
        override fun seal(plain: ByteArray, context: ByteArray): ByteArray =
            if (failing) throw owntally.data.secrets.SecretStoreException("the store is not available (a test)") else inner.seal(plain, context)
        override fun open(sealed: ByteArray, context: ByteArray): ByteArray? = inner.open(sealed, context)
    }

    private class World {
        val devBox = FlakyBox(owntally.data.secrets.AeadSecretBox(owntally.data.secrets.newAead()))
        val ownerEnv = TestEnv().also { it.firstRun("Asha", "98765 43210") }
        val group: GroupId = ownerEnv.groups.createGroup("Goa Trip")
        val asha = Phone(ownerEnv, group, ownerEnv.groups.group(group)!!.keySlot)
        fun join(name: String, n: Int): Phone {
            val env = (if (name == "Dev") TestEnv(devBox) else TestEnv()).also { it.firstRun(name, "98765 0000$n") }
            val slot = env.identity.reserveSlot(emptyList())
            val identity = env.identity.groupIdentity(slot)!!
            val stored = asha.env.groups.envelopes(group).envelopes.map { StoredEnvelope(it.author, it.counter, it.encode()) }
            env.events.addGroup(GroupRow(group, slot, 0), stored)
            env.secrets.saveGroupKey(group, 1, asha.env.secrets.loadKeyring(group).keyring[1]!!)
            assertIs<AddResult.Added>(asha.session.approveJoin(identity.publicKey, identity.encryptionKey, name, "98765 0000$n"))
            return Phone(env, group, slot).also { it.receiveFrom(asha) }
        }
        val ben = join("Ben", 1)
        val dev = join("Dev", 2)
        fun all() = listOf(asha, ben, dev)
        fun sync() { repeat(3) { for (a in all()) for (b in all()) if (a !== b) a.receiveFrom(b) } }
    }

    private fun rupees(n: Long) = Money(n * 100)

    /** Plays the race; returns the world if the rules ended up with Asha as owner and Dev's acceptance refused, or null if this order of events did not make the race. */
    private fun race(extraRotationsByDev: Int = 0, crashOnce: Boolean = false, replaceBy: (World) -> Unit): World? {
        val w = World()
        w.sync()
        assertIs<AddResult.Added>(w.asha.session.offerOwnership(w.dev.key)); w.sync()
        assertIs<AddResult.Added>(w.dev.session.acceptOwnership())        // on Dev's phone Dev is the owner now
        replaceBy(w)                                                       // on Asha's phone the offer is replaced or cancelled, not having heard
        assertIs<AddResult.Added>(w.ben.session.leave())                   // a member leaves: whoever is the owner must change the key
        w.dev.receiveFrom(w.ben); w.asha.receiveFrom(w.ben)
        // Both change the key for epoch 2 (each believes it is the owner), and each writes an entry under its own epoch-2 key.
        assertIs<AddResult.Added>(w.dev.session.addExpense("Dev lunch", rupees(300), w.dev.key, 20_000, Split.Equal(setOf(w.dev.key, w.asha.key))))
        repeat(extraRotationsByDev) { n ->
            // Dev changes the key again by hand (epoch 3 and on, which only Dev ever starts) and writes under each new key.
            assertIs<AddResult.Added>(w.dev.session.rotateKey())
            assertIs<AddResult.Added>(w.dev.session.addExpense("Dev extra $n", rupees(10), w.dev.key, 20_000, Split.Equal(setOf(w.dev.key, w.asha.key))))
        }
        assertIs<AddResult.Added>(w.asha.session.addExpense("Asha taxi", rupees(200), w.asha.key, 20_000, Split.Equal(setOf(w.dev.key, w.asha.key))))
        if (crashOnce) {
            w.devBox.failing = true
            try { w.sync() } catch (e: owntally.data.secrets.SecretStoreException) { /* Dev's phone stopped half way */ }
            w.devBox.failing = false
        }
        w.sync()
        return w
    }

    private fun expenses(p: Phone) = p.view.state.expenses.values.map { it.description }.toSet()

    @Test fun entriesWrittenUnderAKeyThatTheRulesRefusedAreSealedAgainSoEveryMemberReadsThem() {
        var raced = 0
        val variants = listOf<(World) -> Unit>({ w -> w.asha.session.cancelOffer() }, { w -> w.asha.session.offerOwnership(w.ben.key) })
        repeat(6) { round ->
            val w = race(replaceBy = variants[round % variants.size]) ?: return@repeat
            val owners = w.all().map { it.view.state.owner }.toSet()
            assertEquals(1, owners.size, "every phone agrees who the owner is")
            val epochs = w.all().filter { it.key in w.asha.view.state.currentMembers }.map { it.view.state.epoch }.toSet()
            assertEquals(1, epochs.size, "and which epoch the group is in")
            // Whichever phone's key change was refused: both entries are on both members' phones.
            for (p in listOf(w.asha, w.dev)) {
                assertEquals(setOf("Dev lunch", "Asha taxi"), expenses(p).filter { it == "Dev lunch" || it == "Asha taxi" }.toSet(), "${p.view.state.members[p.key]?.name} sees both entries")
                assertEquals(0, p.view.counts.unreadable, "nothing is left unreadable")
                assertTrue(p.view.counts.waitingForKey.isEmpty(), "and nothing waits for a key that will never come")
            }
            assertEquals(w.asha.view.balances, w.dev.view.balances)
            raced++
        }
        assertTrue(raced > 0, "the race happened at least once in the rounds tried (the order the rules apply the events in depends on the random keys)")
    }

    @Test fun entriesUnderLaterKeysThatOnlyTheRefusedPhoneStartedAreSealedAgainToo() {
        var raced = 0
        val variants = listOf<(World) -> Unit>({ w -> w.asha.session.cancelOffer() }, { w -> w.asha.session.offerOwnership(w.ben.key) })
        repeat(6) { round ->
            val w = race(extraRotationsByDev = 2, replaceBy = variants[round % variants.size]) ?: return@repeat
            val devIsOwnerInTheEnd = w.dev.view.state.owner == w.dev.key
            if (!devIsOwnerInTheEnd) {
                for (p in listOf(w.asha, w.dev)) {
                    assertTrue(setOf("Dev lunch", "Dev extra 0", "Dev extra 1", "Asha taxi").all { it in expenses(p) }, "${p.view.state.members[p.key]?.name} sees every entry")
                    assertEquals(0, p.view.counts.unreadable); assertTrue(p.view.counts.waitingForKey.isEmpty())
                }
                raced++
            }
        }
        assertTrue(raced > 0, "Dev lost the ownership in at least one round")
    }

    @Test fun aPhoneThatStopsHalfWayThroughTheRepairDoesNotSealTheSameEntryTwice() {
        var twoVersions = 0
        val variants = listOf<(World) -> Unit>({ w -> w.asha.session.cancelOffer() }, { w -> w.asha.session.offerOwnership(w.ben.key) })
        repeat(8) { round ->
            val w = race(crashOnce = true, replaceBy = variants[round % variants.size]) ?: return@repeat
            val versions = w.dev.env.groups.rawEnvelopes(w.group).map { owntally.model.Envelope.decode(it) }.filter { it.author == w.dev.key }.groupBy { it.counter }.mapValues { it.value.size }
            assertTrue(versions.values.all { it <= 2 }, "an entry has its first envelope and at most one sealed again, not a third: $versions")
            if (versions.values.any { it == 2 }) twoVersions++
            for (p in listOf(w.asha, w.dev)) assertTrue(setOf("Dev lunch", "Asha taxi").all { it in expenses(p) }, "after the stop and the next try, every entry is there")
        }
        assertTrue(twoVersions > 0, "in at least one round Dev's phone was the one that had to repair")
    }
}
