package owntally.sync

import java.nio.ByteBuffer

/**
 * Splits a message into chunks for transports with a small payload limit (Nearby allows 32 KB per
 * bytes payload). Each chunk starts with [message id 4][index 2][count 2].
 */
class Chunker(private val maxChunk: Int = 32_000) {
    private var nextId = 0

    @Synchronized
    fun split(message: ByteArray): List<ByteArray> {
        val id = nextId++
        val count = maxOf(1, (message.size + maxChunk - 1) / maxChunk)
        require(count <= 0xFFFF) { "message too large" }
        return List(count) { index ->
            val from = index * maxChunk
            val to = minOf(message.size, from + maxChunk)
            ByteBuffer.allocate(HEADER + to - from).putInt(id).putShort(index.toShort()).putShort(count.toShort())
                .put(message, from, to - from).array()
        }
    }

    companion object {
        const val HEADER = 8
    }
}

/** Puts chunks from one sender back together. Chunks of different messages may interleave. */
class Reassembler {
    private class Partial(val count: Int) {
        val parts = arrayOfNulls<ByteArray>(count)
        var have = 0
    }

    private val partial = HashMap<Int, Partial>()

    /** Returns the whole message when the last chunk arrives, otherwise null. */
    @Synchronized
    fun add(chunk: ByteArray): ByteArray? {
        if (chunk.size < Chunker.HEADER) return null
        val buf = ByteBuffer.wrap(chunk)
        val id = buf.int
        val index = buf.short.toInt() and 0xFFFF
        val count = buf.short.toInt() and 0xFFFF
        if (count == 0 || index >= count) return null
        val p = partial.getOrPut(id) { Partial(count) }
        if (p.count != count || p.parts[index] != null) return null
        p.parts[index] = chunk.copyOfRange(Chunker.HEADER, chunk.size)
        p.have++
        if (p.have < p.count) return null
        partial.remove(id)
        val out = ByteBuffer.allocate(p.parts.sumOf { it!!.size })
        p.parts.forEach { out.put(it!!) }
        return out.array()
    }
}
