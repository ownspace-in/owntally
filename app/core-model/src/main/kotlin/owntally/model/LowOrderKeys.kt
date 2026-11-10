package owntally.model

/**
 * The X25519 public keys of small order (RFC 7748; the list libsodium blocks): sealing a group key to one of them is refused by HPKE, so a member
 * who registered one would make every key change impossible, including their own removal (finding F1, docs/SECURITY_REVIEW.md). Replay ignores a
 * join or a creating event that names one, and the host refuses such a join request at the door. The top bit of the last byte is ignored by X25519,
 * so it is ignored here. The list is the seven encodings of the points of order 1, 2, 4 and 8, including those outside the canonical range.
 */
object LowOrderKeys {
    private fun hex(s: String) = ByteArray(32) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    val ALL: List<ByteArray> = listOf(
        "0000000000000000000000000000000000000000000000000000000000000000",
        "0100000000000000000000000000000000000000000000000000000000000000",
        "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
        "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    ).map(::hex)

    fun isLowOrder(key: EncryptionKey): Boolean {
        val masked = key.bytes().also { it[31] = (it[31].toInt() and 0x7f).toByte() }
        return ALL.any { it.contentEquals(masked) }
    }
}
