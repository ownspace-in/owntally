package owntally.data.secrets

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.TreeMap

/** Named byte blobs. Names are 1 to 100 letters, digits, `-` or `_`. */
interface BlobStore {
    fun put(name: String, bytes: ByteArray)
    fun get(name: String): ByteArray?

    /** Deleting what is not there is fine. */
    fun delete(name: String)

    /** The names that start with [prefix], in order. */
    fun list(prefix: String): List<String>
}

private val NAME = Regex("[A-Za-z0-9_-]{1,100}")
private val PREFIX = Regex("[A-Za-z0-9_-]{0,100}")

private fun checkName(name: String) = require(NAME.matches(name)) { "not a valid blob name" }

/** For tests and previews. */
class InMemoryBlobStore : BlobStore {
    private val blobs = TreeMap<String, ByteArray>()

    @Synchronized
    override fun put(name: String, bytes: ByteArray) {
        checkName(name)
        blobs[name] = bytes.copyOf()
    }

    @Synchronized
    override fun get(name: String): ByteArray? = blobs[name.also(::checkName)]?.copyOf()

    @Synchronized
    override fun delete(name: String) {
        blobs.remove(name.also(::checkName))
    }

    @Synchronized
    override fun list(prefix: String): List<String> {
        require(PREFIX.matches(prefix)) { "not a valid prefix" }
        return blobs.keys.filter { it.startsWith(prefix) }
    }

    /** Everything stored, for tests that look at the raw bytes. */
    @Synchronized
    fun all(): Map<String, ByteArray> = blobs.mapValues { it.value.copyOf() }
}

/** One file per blob in [dir] (on Android, a folder that Android never backs up). A write replaces the whole file or nothing. */
class FileBlobStore(private val dir: File) : BlobStore {
    private fun file(name: String) = File(dir, "$name.blob")

    @Synchronized
    override fun put(name: String, bytes: ByteArray) {
        checkName(name)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw java.io.IOException("cannot create the folder")
        val target = file(name)
        val temp = File(dir, "$name.blob.tmp")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    override fun get(name: String): ByteArray? {
        checkName(name)
        val f = file(name)
        return if (f.isFile) f.readBytes() else null
    }

    @Synchronized
    override fun delete(name: String) {
        checkName(name)
        file(name).delete()
    }

    @Synchronized
    override fun list(prefix: String): List<String> {
        require(PREFIX.matches(prefix)) { "not a valid prefix" }
        val files = dir.listFiles() ?: return emptyList()
        return files.map { it.name }.filter { it.endsWith(".blob") }.map { it.removeSuffix(".blob") }
            .filter { NAME.matches(it) && it.startsWith(prefix) }.sorted()
    }
}
