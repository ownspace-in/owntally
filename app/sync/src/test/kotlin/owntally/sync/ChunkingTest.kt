package owntally.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChunkingTest {
    @Test
    fun smallMessageIsOneChunk() {
        val chunks = Chunker(maxChunk = 100).split(byteArrayOf(1, 2, 3))
        assertEquals(1, chunks.size)
        assertContentEquals(byteArrayOf(1, 2, 3), Reassembler().add(chunks[0]))
    }

    @Test
    fun emptyMessageSurvives() {
        val chunks = Chunker(maxChunk = 100).split(ByteArray(0))
        assertContentEquals(ByteArray(0), Reassembler().add(chunks.single()))
    }

    @Test
    fun largeMessageSplitsAndRebuildsInAnyOrder() {
        val message = ByteArray(50_000) { (it * 13).toByte() }
        val chunks = Chunker(maxChunk = 32_000).split(message)
        assertEquals(2, chunks.size)
        assertEquals(true, chunks.all { it.size <= 32_000 + Chunker.HEADER })

        val re = Reassembler()
        assertNull(re.add(chunks[1]))
        assertContentEquals(message, re.add(chunks[0]))
    }

    @Test
    fun interleavedMessagesStayApart() {
        val chunker = Chunker(maxChunk = 10)
        val a = ByteArray(25) { 1 }
        val b = ByteArray(25) { 2 }
        val ca = chunker.split(a)
        val cb = chunker.split(b)
        val re = Reassembler()
        var gotA: ByteArray? = null
        var gotB: ByteArray? = null
        for (i in 0 until 3) {
            re.add(ca[i])?.let { gotA = it }
            re.add(cb[i])?.let { gotB = it }
        }
        assertContentEquals(a, gotA)
        assertContentEquals(b, gotB)
    }

    @Test
    fun duplicateAndGarbageChunksAreIgnored() {
        val chunks = Chunker(maxChunk = 10).split(ByteArray(25) { 5 })
        val re = Reassembler()
        assertNull(re.add(chunks[0]))
        assertNull(re.add(chunks[0]))
        assertNull(re.add(byteArrayOf(1, 2)))
        assertNull(re.add(chunks[1]))
        assertContentEquals(ByteArray(25) { 5 }, re.add(chunks[2]))
    }
}
