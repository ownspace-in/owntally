package owntally.crypto

import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import owntally.model.EncryptionKey
import owntally.model.GroupId

/**
 * M10, T10.8, finding F1 (docs/SECURITY_REVIEW.md): a member's encryption key is whatever 32 bytes the joiner sent, and nothing refuses a
 * low-order X25519 point. Sealing a new group key to such a key throws, as the documentation of [KeyRotation.seal] says. This test records that
 * behaviour; the owner decides the fix (refuse such a key when the member joins), and when it is made this test stays true.
 */
class KeyRotationUnusableKeyTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val me = KeyDerivation.groupIdentity(Bip39.seed(Bip39.fromEntropy(ByteArray(16) { 3 })), 0)
    private val group = GroupId(ByteArray(16) { 1 })

    @Test fun sealingToALowOrderEncryptionKeyThrows() {
        val lowOrder = listOf(
            ByteArray(32),
            ByteArray(32).also { it[0] = 1 },
            hex("e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800"),
        )
        for (k in lowOrder) {
            assertFailsWith<GeneralSecurityException> { KeyRotation.seal(me.publicKey, group, 2, GroupKey.generate(), mapOf(me.publicKey to EncryptionKey(k))) }
        }
    }
}
