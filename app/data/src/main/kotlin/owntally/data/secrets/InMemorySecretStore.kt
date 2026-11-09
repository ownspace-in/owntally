package owntally.data.secrets

import java.util.TreeMap
import owntally.crypto.GroupKey
import owntally.crypto.GroupKeyring
import owntally.crypto.MasterSeed
import owntally.model.GroupId

/** A [SecretStore] that keeps everything in memory without sealing it, for tests and previews of other modules. Never used in the app. */
class InMemorySecretStore : SecretStore {
    private var seed: MasterSeed? = null
    private val keys = TreeMap<String, GroupKey>()

    private fun name(id: GroupId, epoch: Long) = "${id.toHex()}-${epoch.toString().padStart(10, '0')}"

    @Synchronized
    override fun saveMasterSeed(seed: MasterSeed) {
        this.seed = seed
    }

    @Synchronized
    override fun loadMasterSeed(): SecretLoad<MasterSeed> = seed?.let { SecretLoad.Found(it) } ?: SecretLoad.Missing

    @Synchronized
    override fun saveGroupKey(groupId: GroupId, epoch: Long, key: GroupKey) {
        require(epoch >= 1)
        keys[name(groupId, epoch)] = key
    }

    @Synchronized
    override fun loadKeyring(groupId: GroupId): KeyringLoad {
        val ring = GroupKeyring(groupId)
        val prefix = "${groupId.toHex()}-"
        for ((n, k) in keys) if (n.startsWith(prefix)) ring.add(n.removePrefix(prefix).toLong(), k)
        return KeyringLoad(ring, emptySet())
    }

    @Synchronized
    override fun deleteGroupKey(groupId: GroupId, epoch: Long) {
        keys.remove(name(groupId, epoch))
    }

    private var pending: PendingInvite? = null

    @Synchronized
    override fun savePendingInvite(link: String, expiresAtSeconds: Long) { pending = PendingInvite(link, expiresAtSeconds) }

    @Synchronized
    override fun loadPendingInvite(): SecretLoad<PendingInvite> = pending?.let { SecretLoad.Found(it) } ?: SecretLoad.Missing

    @Synchronized
    override fun clearPendingInvite() { pending = null }

    @Synchronized
    override fun deleteGroup(groupId: GroupId) {
        keys.keys.removeAll { it.startsWith("${groupId.toHex()}-") }
    }

    @Synchronized
    override fun wipeAll() {
        seed = null
        keys.clear()
        pending = null
    }
}
