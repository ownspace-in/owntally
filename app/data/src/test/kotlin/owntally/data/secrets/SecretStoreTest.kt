package owntally.data.secrets

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import owntally.crypto.GroupKey
import owntally.crypto.MasterSeed
import owntally.model.GroupId

class SecretStoreTest {
    private val blobs = InMemoryBlobStore()
    private val box = AeadSecretBox(newAead())
    private val store: SecretStore = BoxedSecretStore(box, blobs)
    private val seed = MasterSeed(ByteArray(64) { (it + 1).toByte() })
    private val groupA = GroupId(ByteArray(16) { 0xA1.toByte() })
    private val groupB = GroupId(ByteArray(16) { 0xB2.toByte() })
    private fun key(n: Int) = GroupKey(ByteArray(32) { (n * 16 + it).toByte() })

    @Test
    fun nothingIsStoredAtFirst() {
        assertEquals(SecretLoad.Missing, store.loadMasterSeed())
        assertEquals(emptySet(), store.loadKeyring(groupA).keyring.epochs())
    }

    @Test
    fun theMasterSeedComesBack() {
        store.saveMasterSeed(seed)
        assertEquals(SecretLoad.Found(seed), store.loadMasterSeed())
    }

    @Test
    fun savingTheSeedAgainReplacesIt() {
        store.saveMasterSeed(seed)
        val other = MasterSeed(ByteArray(64) { 9 })
        store.saveMasterSeed(other)
        assertEquals(SecretLoad.Found(other), store.loadMasterSeed())
    }

    @Test
    fun nothingSecretIsStoredAsPlainBytesAndEverySavingDiffers() {
        store.saveMasterSeed(seed)
        store.saveGroupKey(groupA, 1, key(1))
        val everything = blobs.all().values
        for (blob in everything) {
            assertFalse(seed.bytes().hex().take(16) in blob.hex())
            assertFalse(key(1).bytes().hex().take(16) in blob.hex())
        }
        val before = blobs.get("seed")!!.hex()
        store.saveMasterSeed(seed)
        assertNotEquals(before, blobs.get("seed")!!.hex())
    }

    @Test
    fun groupKeysComeBackPerGroupAndEpoch() {
        store.saveGroupKey(groupA, 1, key(1))
        store.saveGroupKey(groupA, 2, key(2))
        store.saveGroupKey(groupA, 3, key(3))
        store.saveGroupKey(groupB, 1, key(9))
        val a = store.loadKeyring(groupA)
        assertEquals(groupA, a.keyring.groupId)
        assertEquals(setOf(1L, 2L, 3L), a.keyring.epochs())
        assertEquals(key(2), a.keyring[2])
        assertEquals(emptySet(), a.unreadableEpochs)
        val b = store.loadKeyring(groupB)
        assertEquals(setOf(1L), b.keyring.epochs())
        assertEquals(key(9), b.keyring[1])
    }

    @Test
    fun savingAnEpochAgainKeepsTheLatest() {
        store.saveGroupKey(groupA, 1, key(1))
        store.saveGroupKey(groupA, 1, key(2))
        assertEquals(key(2), store.loadKeyring(groupA).keyring[1])
    }

    @Test
    fun aBlobThatCannotBeOpenedIsReportedNotThrown() {
        store.saveMasterSeed(seed)
        store.saveGroupKey(groupA, 1, key(1))
        store.saveGroupKey(groupA, 2, key(2))
        store.saveGroupKey(groupA, 3, key(3))
        // The Keystore key is lost or changed: a store with another key cannot open anything.
        val other = BoxedSecretStore(AeadSecretBox(newAead()), blobs)
        assertEquals(SecretLoad.Unreadable, other.loadMasterSeed())
        val ring = other.loadKeyring(groupA)
        assertEquals(emptySet(), ring.keyring.epochs())
        assertEquals(setOf(1L, 2L, 3L), ring.unreadableEpochs)
    }

    @Test
    fun oneDamagedEpochDoesNotHideTheOthers() {
        store.saveGroupKey(groupA, 1, key(1))
        store.saveGroupKey(groupA, 2, key(2))
        store.saveGroupKey(groupA, 3, key(3))
        val name = blobs.list("").first { it.endsWith("-2") }
        blobs.put(name, blobs.get(name)!!.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        val ring = store.loadKeyring(groupA)
        assertEquals(setOf(1L, 3L), ring.keyring.epochs())
        assertEquals(setOf(2L), ring.unreadableEpochs)
    }

    @Test
    fun aBlobMovedToAnotherSlotCannotBeOpenedThere() {
        store.saveMasterSeed(seed)
        store.saveGroupKey(groupA, 1, key(1))
        val seedBlob = blobs.get("seed")!!
        val keyName = blobs.list("").first { it != "seed" }
        val keyBlob = blobs.get(keyName)!!
        blobs.put("seed", keyBlob) // a group key where the seed should be
        assertEquals(SecretLoad.Unreadable, store.loadMasterSeed())
        blobs.put(keyName, seedBlob) // the seed where a group key should be
        assertEquals(setOf(1L), store.loadKeyring(groupA).unreadableEpochs)
        // And a key of group A copied into group B's slot.
        store.saveGroupKey(groupA, 5, key(5))
        val nameA = blobs.list("").first { it.endsWith("-5") }
        blobs.put(nameA.replace(groupA.toHex(), groupB.toHex()), blobs.get(nameA)!!)
        assertEquals(setOf(5L), store.loadKeyring(groupB).unreadableEpochs)
    }

    @Test
    fun emptyUnknownVersionAndTruncatedBlobsAreUnreadable() {
        for (bytes in listOf(ByteArray(0), byteArrayOf(2), byteArrayOf(0, 1, 2, 3), byteArrayOf(1), ByteArray(200) { 7 })) {
            blobs.put("seed", bytes)
            assertEquals(SecretLoad.Unreadable, store.loadMasterSeed(), "${bytes.size} bytes")
        }
    }

    @Test
    fun aValidlySealedBlobWithAnotherVersionByteIsUnreadable() {
        store.saveMasterSeed(seed)
        val good = blobs.get("seed")!!
        assertEquals(SecretLoad.Found(seed), store.loadMasterSeed())
        for (version in listOf(0, 2, 255)) {
            blobs.put("seed", good.copyOf().also { it[0] = version.toByte() })
            assertEquals(SecretLoad.Unreadable, store.loadMasterSeed(), "version $version")
        }
    }

    @Test
    fun aSavedBlobWithTheWrongLengthInsideIsUnreadableNotACrash() {
        // Properly sealed, but the content is not a 64-byte seed.
        blobs.put("seed", BoxedSecretStore.sealForTest(box, "owntally/v1/seed", ByteArray(10)))
        assertEquals(SecretLoad.Unreadable, store.loadMasterSeed())
    }

    @Test
    fun deletingAGroupRemovesOnlyItsKeys() {
        store.saveMasterSeed(seed)
        store.saveGroupKey(groupA, 1, key(1))
        store.saveGroupKey(groupB, 1, key(2))
        store.deleteGroup(groupA)
        assertEquals(emptySet(), store.loadKeyring(groupA).keyring.epochs())
        assertEquals(setOf(1L), store.loadKeyring(groupB).keyring.epochs())
        assertTrue(store.loadMasterSeed() is SecretLoad.Found)
    }

    @Test
    fun wipingRemovesEverything() {
        store.saveMasterSeed(seed)
        store.saveGroupKey(groupA, 1, key(1))
        store.wipeAll()
        assertEquals(SecretLoad.Missing, store.loadMasterSeed())
        assertEquals(emptyList(), blobs.list(""))
    }

    @Test
    fun aFailureWhileSealingThrowsAStoreExceptionAndWritesNothing() {
        val broken = BoxedSecretStore(object : SecretBox {
            override fun seal(plain: ByteArray, context: ByteArray): ByteArray = throw java.security.GeneralSecurityException("keystore unavailable: ${plain.hex()}")
            override fun open(sealed: ByteArray, context: ByteArray): ByteArray? = null
        }, blobs)
        val e = assertFailsWith<SecretStoreException> { broken.saveMasterSeed(seed) }
        assertFalse(seed.bytes().hex().take(16) in e.message.orEmpty(), "the message must not carry the secret")
        assertEquals(emptyList(), blobs.list(""))
        assertFailsWith<SecretStoreException> { broken.saveGroupKey(groupA, 1, key(1)) }
    }

    @Test
    fun theStoreAndItsResultsPrintNoSecret() {
        store.saveMasterSeed(seed)
        val text = listOf(store.toString(), store.loadMasterSeed().toString(), store.loadKeyring(groupA).toString()).joinToString(" ")
        assertFalse(seed.bytes().hex().take(8) in text)
        assertFalse(seed.bytes().contentToString().take(12) in text)
    }

    @Test
    fun theLoadedKeyIsTheSameBytes() {
        store.saveGroupKey(groupA, 1, key(4))
        assertContentEquals(key(4).bytes(), store.loadKeyring(groupA).keyring[1]!!.bytes())
    }
}
