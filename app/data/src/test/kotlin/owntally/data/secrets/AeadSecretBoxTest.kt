package owntally.data.secrets

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AeadSecretBoxTest {
    private val box = AeadSecretBox(newAead())
    private val secret = ByteArray(32) { (it * 7).toByte() }
    private val context = "owntally/v1/test".toByteArray()

    @Test
    fun sealedBytesOpenAgain() {
        assertContentEquals(secret, box.open(box.seal(secret, context), context))
    }

    @Test
    fun emptyAndLargeSecretsWork() {
        assertContentEquals(ByteArray(0), box.open(box.seal(ByteArray(0), context), context))
        val big = ByteArray(100_000) { it.toByte() }
        assertContentEquals(big, box.open(box.seal(big, context), context))
    }

    @Test
    fun theSecretIsNotInTheSealedBytesAndEverySealingDiffers() {
        val one = box.seal(secret, context)
        val two = box.seal(secret, context)
        assertNotEquals(one.hex(), two.hex())
        assertEquals(false, secret.hex().take(16) in one.hex())
    }

    @Test
    fun aDifferentContextWrongKeyOrDamageGivesNothingNotAnException() {
        val sealed = box.seal(secret, context)
        assertNull(box.open(sealed, "other".toByteArray()), "another context")
        assertNull(AeadSecretBox(newAead()).open(sealed, context), "another key")
        assertNull(box.open(sealed.copyOf(sealed.size - 1), context), "cut short")
        assertNull(box.open(ByteArray(0), context), "empty")
        assertNull(box.open(ByteArray(5), context), "too short to be anything")
        for (i in sealed.indices) {
            val bent = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull(box.open(bent, context), "byte $i")
        }
    }
}
