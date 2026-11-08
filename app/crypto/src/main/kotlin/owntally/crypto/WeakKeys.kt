package owntally.crypto

/**
 * Ed25519 public keys of small order: they belong to nobody, and a signature of zeros from one of them verifies for about a quarter of all
 * messages (found in T6.8 by a flaky test). Every place that checks a signature refuses them first. The list is the one libsodium uses:
 * the seven encodings of the points of order 1, 2, 4 and 8, including the ones outside the canonical range; the sign bit is ignored.
 */
object WeakKeys {
    private fun hex(s: String) = ByteArray(32) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val list: List<ByteArray> = listOf(
        "0000000000000000000000000000000000000000000000000000000000000000",
        "0100000000000000000000000000000000000000000000000000000000000000",
        "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
        "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    ).map(::hex)

    fun isWeak(publicKey: ByteArray): Boolean {
        if (publicKey.size != 32) return false
        val masked = publicKey.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() }
        return list.any { it.contentEquals(masked) }
    }
}
