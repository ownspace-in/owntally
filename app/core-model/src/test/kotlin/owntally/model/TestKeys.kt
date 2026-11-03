package owntally.model

/** Test helper: a key whose first byte is [n], so keys sort by n. */
fun key(n: Int) = PublicKey(ByteArray(32).also { it[0] = n.toByte() })

val GROUP = GroupId(ByteArray(16) { 9 })

fun rupees(n: Long) = Money(n * 100)
