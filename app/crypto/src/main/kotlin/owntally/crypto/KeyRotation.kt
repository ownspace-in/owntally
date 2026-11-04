package owntally.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import java.security.GeneralSecurityException
import owntally.model.EncryptionKey
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.GroupId
import owntally.model.GroupState
import owntally.model.PublicKey
import owntally.model.SealedKey

/**
 * Handing a new group key to the members who stay (docs/CRYPTO.md section 6). The key is sealed to each member's encryption
 * key with HPKE (RFC 9180: X25519, HKDF-SHA256, ChaCha20-Poly1305, via Tink). The HPKE context binds the sealed key to the
 * sender (the author of the event that carries it), the group and the epoch, so it cannot be replayed as the key of another
 * epoch or group, nor copied into another member's event (owner decision, 4 Nov 2026).
 */
object KeyRotation {
    private val INFO_PREFIX = "owntally/v1/key-delivery".toByteArray()

    private val parameters: HpkeParameters by lazy {
        HybridConfig.register()
        HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.CHACHA20_POLY1305)
            .setVariant(HpkeParameters.Variant.NO_PREFIX)
            .build()
    }

    /** label, then the sender's public key (32 bytes), the group id (16 bytes) and the epoch (4 bytes, big-endian). */
    private fun info(sender: PublicKey, groupId: GroupId, epoch: Long): ByteArray =
        INFO_PREFIX + sender.bytes() + groupId.bytes() + byteArrayOf((epoch ushr 24).toByte(), (epoch ushr 16).toByte(), (epoch ushr 8).toByte(), epoch.toByte())

    /** The current members (minus [excluding]) and the encryption key each published when they joined. */
    fun recipients(state: GroupState, excluding: Set<PublicKey> = emptySet()): Map<PublicKey, EncryptionKey> =
        state.currentMembers.filterKeys { it !in excluding }.mapValues { it.value.encryptionKey }

    /**
     * Seals [newKey] for each of [recipients]. [sender] is the author of the event that will carry the sealed keys. Throws
     * [GeneralSecurityException] if a recipient's key is unusable.
     */
    fun seal(sender: PublicKey, groupId: GroupId, newEpoch: Long, newKey: GroupKey, recipients: Map<PublicKey, EncryptionKey>): Map<PublicKey, SealedKey> {
        require(newEpoch >= 2) { "a rotation starts epoch 2 or later" }
        val context = info(sender, groupId, newEpoch)
        val keyBytes = newKey.bytes()
        try {
            return recipients.mapValues { (_, encryptionKey) ->
                val publicKey = HpkePublicKey.create(parameters, Bytes.copyFrom(encryptionKey.bytes()), null)
                val encrypt = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(publicKey).withFixedId(1).makePrimary()).build()
                    .getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
                SealedKey(encrypt.encrypt(keyBytes, context))
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    /** The key sealed for [me] by [sender], or null if it was not sealed for this member, sender, group and epoch (or has been changed). */
    fun open(sealed: SealedKey, me: GroupIdentity, sender: PublicKey, groupId: GroupId, epoch: Long): GroupKey? {
        val privateBytes = me.encryptionPrivateKey()
        return try {
            val publicKey = HpkePublicKey.create(parameters, Bytes.copyFrom(X25519.publicFromPrivate(privateBytes)), null)
            val privateKey = HpkePrivateKey.create(publicKey, SecretBytes.copyFrom(privateBytes, InsecureSecretKeyAccess.get()))
            val decrypt = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(privateKey).withFixedId(1).makePrimary()).build()
                .getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
            GroupKey(decrypt.decrypt(sealed.bytes(), info(sender, groupId, epoch)))
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null // the opened bytes were not a 32-byte key
        } finally {
            privateBytes.fill(0)
        }
    }

    /**
     * Takes the new epoch's key out of [event] and puts it in [keyring]. Only the event that [state] says started the epoch
     * counts: an event that replay ignored (for example a rotation written by a plain member) or that is not in the log
     * delivers nothing, so nobody can feed the group a key of their choosing. Returns true if a key was added.
     */
    fun receive(state: GroupState, event: Event, me: GroupIdentity, keyring: GroupKeyring): Boolean {
        val (epoch, sealedKeys) = when (val body = event.body) {
            is EventBody.MemberRemoved -> body.newEpoch to body.sealedKeys
            is EventBody.KeyRotated -> body.newEpoch to body.sealedKeys
            else -> return false
        }
        if (state.epochStarts[epoch] != event.id) return false
        val mine = sealedKeys[me.publicKey] ?: return false
        val key = open(mine, me, event.author, keyring.groupId, epoch) ?: return false
        keyring.add(epoch, key)
        return true
    }
}
