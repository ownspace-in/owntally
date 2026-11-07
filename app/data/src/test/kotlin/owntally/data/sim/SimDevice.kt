package owntally.data.sim

import owntally.data.repo.TestEnv
import owntally.data.session.AddResult
import owntally.data.session.GroupSession
import owntally.data.session.RepositorySyncStore
import owntally.data.store.GroupRow
import owntally.data.store.StoredEnvelope
import owntally.model.GroupId
import owntally.model.PublicKey

/** One simulated phone: its own storage and keys, and a session on the group. */
class SimDevice(val index: Int, val name: String, val env: TestEnv, val group: GroupId, val slot: Long) {
    val key: PublicKey get() = env.identity.groupIdentity(slot)!!.publicKey
    val session: GroupSession by lazy { GroupSession(group, env.groups, env.secrets, env.identity, env.clock) }
    var online = true

    fun syncStore() = RepositorySyncStore(group, env.groups)

    companion object {
        /** A phone whose recovery phrase, and so every key, comes from the seed: the same seed always makes the same people (event order depends on the keys). */
        internal fun phone(seed: Long, index: Int, name: String, phone: String): TestEnv {
            val entropy = kotlin.random.Random(seed * 1_000 + index).nextBytes(16)
            return TestEnv().also { it.identity.completeFirstRun(owntally.crypto.Bip39.fromEntropy(entropy), name, phone) }
        }

        fun owner(seed: Long, name: String): SimDevice {
            val env = phone(seed, 0, name, "98765 00000")
            val group = env.groups.createGroup("Simulated")
            return SimDevice(0, name, env, group, env.groups.group(group)!!.keySlot)
        }

        /** A phone that joins: it gets the group's key and what the admitter has (what an invitation does in M6), and is then admitted. */
        fun joined(seed: Long, index: Int, name: String, admitter: SimDevice): SimDevice {
            val phone = "98765 %05d".format(index)
            val env = phone(seed, index, name, phone)
            val slot = env.identity.reserveSlot(emptyList())
            val identity = env.identity.groupIdentity(slot)!!
            val stored = admitter.env.groups.envelopes(admitter.group).envelopes.map { StoredEnvelope(it.author, it.counter, it.encode()) }
            env.events.addGroup(GroupRow(admitter.group, slot, 0), stored)
            env.secrets.saveGroupKey(admitter.group, 1, admitter.env.secrets.loadKeyring(admitter.group).keyring[1]!!)
            check(admitter.session.approveJoin(identity.publicKey, identity.encryptionKey, name, phone) is AddResult.Added)
            return SimDevice(index, name, env, admitter.group, slot).also { it.session.refresh() }
        }

        /**
         * A second copy of [source]'s phone (the same keys, from the same seed) holding [envelopes] and the group's keys: for trying the
         * same arrivals in different orders.
         */
        fun copyOf(seed: Long, source: SimDevice, envelopes: List<ByteArray>): SimDevice {
            val env = phone(seed, source.index, source.name, "98765 %05d".format(source.index))
            val slot = env.identity.reserveSlot(emptyList())
            check(slot == source.slot) { "the copy must use the same key slot" }
            env.events.addGroup(GroupRow(source.group, slot, 0), envelopes.map { b -> owntally.model.Envelope.decode(b).let { StoredEnvelope(it.author, it.counter, b) } })
            val ring = source.env.secrets.loadKeyring(source.group).keyring
            for (epoch in ring.epochs()) env.secrets.saveGroupKey(source.group, epoch, ring[epoch]!!)
            return SimDevice(source.index, source.name, env, source.group, slot).also { it.session.refresh() }
        }
    }
}
