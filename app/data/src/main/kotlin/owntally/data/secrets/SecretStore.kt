package owntally.data.secrets

import owntally.crypto.GroupKey
import owntally.crypto.GroupKeyring
import owntally.crypto.MasterSeed
import owntally.model.GroupId

/** The answer when asking for a stored secret. [Unreadable] means something is stored but cannot be opened (the Keystore key is gone or changed, or the data is damaged). */
sealed class SecretLoad<out T> {
    data class Found<out T>(val value: T) : SecretLoad<T>()
    data object Missing : SecretLoad<Nothing>()
    data object Unreadable : SecretLoad<Nothing>()
}

/** The keys that could be read for a group, and the epochs that are stored but could not be opened. */
data class KeyringLoad(val keyring: GroupKeyring, val unreadableEpochs: Set<Long>)

/** An invite link that arrived before the person could use it (decision A1, M9): the link as it was written, and when its ticket expires (seconds). */
data class PendingInvite(val link: String, val expiresAtSeconds: Long)

/** Keeps the master seed and each group's epoch keys safe on disk. Nothing secret is logged, and no message contains one. */
interface SecretStore {
    /** Replaces any stored seed. Throws [SecretStoreException] if it cannot be sealed. */
    fun saveMasterSeed(seed: MasterSeed)
    fun loadMasterSeed(): SecretLoad<MasterSeed>

    /** Throws [SecretStoreException] if it cannot be sealed. */
    fun saveGroupKey(groupId: GroupId, epoch: Long, key: GroupKey)
    fun loadKeyring(groupId: GroupId): KeyringLoad

    /** Forgets one epoch's key (for example one saved for a key change that was then refused). Deleting what is not there is fine. */
    fun deleteGroupKey(groupId: GroupId, epoch: Long)
    fun deleteGroup(groupId: GroupId)

    /**
     * Keeps one invite link (it holds the group key, so it is sealed like a key and never goes to the database) until it is used. A newer link
     * replaces it. Throws [SecretStoreException] if it cannot be sealed.
     */
    fun savePendingInvite(link: String, expiresAtSeconds: Long)
    fun loadPendingInvite(): SecretLoad<PendingInvite>
    fun clearPendingInvite()
    fun wipeAll()
}

/**
 * A [SecretStore] on a [SecretBox] and a [BlobStore]. Each blob is a version byte (1) followed by the sealed secret, sealed for
 * its own slot ("seed", or "key" of one group and epoch), so blobs cannot be swapped between slots.
 */
class BoxedSecretStore(private val box: SecretBox, private val blobs: BlobStore) : SecretStore {
    private fun keyName(groupId: GroupId, epoch: Long) = "$KEY_PREFIX${groupId.toHex()}-$epoch"
    private fun keyContext(groupId: GroupId, epoch: Long) = "owntally/v1/key/${groupId.toHex()}/$epoch".toByteArray()

    private fun write(name: String, context: ByteArray, secret: ByteArray) {
        val blob = try {
            blobBytes(box, context, secret)
        } catch (e: SecretStoreException) {
            throw e
        } catch (e: Exception) {
            // Whatever a box throws, only its class name gets out: its message could contain what was being sealed.
            throw SecretStoreException("could not seal (${e.javaClass.simpleName})")
        }
        try {
            blobs.put(name, blob)
        } catch (e: java.io.IOException) {
            throw SecretStoreException("could not write (${e.javaClass.simpleName})")
        }
    }

    private fun read(name: String, context: ByteArray): SecretLoad<ByteArray> {
        val blob = blobs.get(name) ?: return SecretLoad.Missing
        if (blob.isEmpty() || blob[0] != VERSION) return SecretLoad.Unreadable
        return box.open(blob.copyOfRange(1, blob.size), context)?.let { SecretLoad.Found(it) } ?: SecretLoad.Unreadable
    }

    override fun saveMasterSeed(seed: MasterSeed) {
        val bytes = seed.bytes()
        try {
            write(SEED_NAME, SEED_CONTEXT, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    override fun loadMasterSeed(): SecretLoad<MasterSeed> = when (val r = read(SEED_NAME, SEED_CONTEXT)) {
        is SecretLoad.Found -> try {
            SecretLoad.Found(MasterSeed(r.value))
        } catch (e: IllegalArgumentException) {
            SecretLoad.Unreadable // sealed correctly but not a 64-byte seed
        } finally {
            r.value.fill(0)
        }
        SecretLoad.Missing -> SecretLoad.Missing
        SecretLoad.Unreadable -> SecretLoad.Unreadable
    }

    override fun saveGroupKey(groupId: GroupId, epoch: Long, key: GroupKey) {
        require(epoch >= 1) { "epochs start at 1" }
        val bytes = key.bytes()
        try {
            write(keyName(groupId, epoch), keyContext(groupId, epoch), bytes)
        } finally {
            bytes.fill(0)
        }
    }

    override fun loadKeyring(groupId: GroupId): KeyringLoad {
        val keyring = GroupKeyring(groupId)
        val unreadable = sortedSetOf<Long>()
        val prefix = "$KEY_PREFIX${groupId.toHex()}-"
        for (name in blobs.list(prefix)) {
            val epoch = name.removePrefix(prefix).toLongOrNull()?.takeIf { it >= 1 } ?: continue
            when (val r = read(name, keyContext(groupId, epoch))) {
                is SecretLoad.Found -> try {
                    keyring.add(epoch, GroupKey(r.value))
                } catch (e: IllegalArgumentException) {
                    unreadable += epoch
                } finally {
                    r.value.fill(0)
                }
                SecretLoad.Unreadable -> unreadable += epoch
                SecretLoad.Missing -> Unit
            }
        }
        return KeyringLoad(keyring, unreadable)
    }

    override fun deleteGroupKey(groupId: GroupId, epoch: Long) {
        blobs.delete(keyName(groupId, epoch))
    }

    override fun savePendingInvite(link: String, expiresAtSeconds: Long) {
        val bytes = java.nio.ByteBuffer.allocate(8).putLong(expiresAtSeconds).array() + link.toByteArray(Charsets.UTF_8)
        try {
            write(PENDING_NAME, PENDING_CONTEXT, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    override fun loadPendingInvite(): SecretLoad<PendingInvite> = when (val r = read(PENDING_NAME, PENDING_CONTEXT)) {
        is SecretLoad.Found -> try {
            if (r.value.size < 9) SecretLoad.Unreadable
            else SecretLoad.Found(PendingInvite(String(r.value, 8, r.value.size - 8, Charsets.UTF_8), java.nio.ByteBuffer.wrap(r.value, 0, 8).long))
        } finally {
            r.value.fill(0)
        }
        SecretLoad.Missing -> SecretLoad.Missing
        SecretLoad.Unreadable -> SecretLoad.Unreadable
    }

    override fun clearPendingInvite() {
        blobs.delete(PENDING_NAME)
    }

    override fun deleteGroup(groupId: GroupId) {
        for (name in blobs.list("$KEY_PREFIX${groupId.toHex()}-")) blobs.delete(name)
    }

    override fun wipeAll() {
        for (name in blobs.list("")) blobs.delete(name)
    }

    companion object {
        private const val VERSION: Byte = 1
        private const val SEED_NAME = "seed"
        private const val KEY_PREFIX = "key-"
        private const val PENDING_NAME = "pending-invite"
        private val PENDING_CONTEXT = "owntally/v1/pending-invite".toByteArray()
        private val SEED_CONTEXT = "owntally/v1/seed".toByteArray()

        private fun blobBytes(box: SecretBox, context: ByteArray, secret: ByteArray) = byteArrayOf(VERSION) + box.seal(secret, context)

        /** For tests: a correctly sealed blob with any content. */
        internal fun sealForTest(box: SecretBox, context: String, content: ByteArray) = blobBytes(box, context.toByteArray(), content)
    }
}
