package owntally.data.secrets

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FileBlobStoreTest {
    private val dir: File = createTempDirectory("owntally-blobs").toFile()
    private val store = FileBlobStore(dir)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun putGetDeleteAndList() {
        assertNull(store.get("seed"))
        store.put("seed", byteArrayOf(1, 2, 3))
        store.put("key-aa-1", byteArrayOf(4))
        store.put("key-aa-2", byteArrayOf(5))
        assertContentEquals(byteArrayOf(1, 2, 3), store.get("seed"))
        assertEquals(listOf("key-aa-1", "key-aa-2"), store.list("key-aa-"))
        assertEquals(listOf("key-aa-1", "key-aa-2", "seed"), store.list(""))
        store.delete("seed")
        assertNull(store.get("seed"))
        store.delete("seed") // deleting what is not there is fine
    }

    @Test
    fun overwritingReplacesTheWholeBlobAndLeavesNoTemporaryFile() {
        store.put("seed", ByteArray(1000) { 1 })
        store.put("seed", byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), store.get("seed"))
        assertEquals(listOf("seed.blob"), dir.list()!!.toList())
    }

    @Test
    fun aNewStoreOnTheSameFolderSeesTheData() {
        store.put("seed", byteArrayOf(7, 7))
        assertContentEquals(byteArrayOf(7, 7), FileBlobStore(dir).get("seed"))
    }

    @Test
    fun namesThatCouldEscapeTheFolderAreRefused() {
        for (name in listOf("", ".", "..", "../x", "a/b", "a\\b", "a b", "a.b", "é", "x".repeat(200), "seed\u0000")) {
            assertFailsWith<IllegalArgumentException>("\"$name\"") { store.put(name, byteArrayOf(1)) }
            assertFailsWith<IllegalArgumentException>("\"$name\"") { store.get(name) }
        }
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test
    fun strayTemporaryFilesAreNotListedAndNotRead() {
        File(dir, "seed.blob.tmp").writeBytes(byteArrayOf(1))
        File(dir, "notes.txt").writeBytes(byteArrayOf(1))
        assertEquals(emptyList(), store.list(""))
        assertNull(store.get("seed"))
    }

    @Test
    fun theFolderIsCreatedWhenMissing() {
        val nested = File(dir, "a/b")
        FileBlobStore(nested).put("seed", byteArrayOf(1))
        assertContentEquals(byteArrayOf(1), FileBlobStore(nested).get("seed"))
    }
}
