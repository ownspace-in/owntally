package owntally.model

/** Test helper: a key whose first byte is [n], so keys sort by n. */
fun key(n: Int) = PublicKey(ByteArray(32).also { it[0] = n.toByte() })

/** The group id of the usual test group: made for key(1), the owner in [standardGroup]. */
val GROUP = GroupIds.create(key(1), ByteArray(8) { 9 })

fun rupees(n: Long) = Money(n * 100)

/** Test helper: a fixed encryption key for places that do not care which one. */
val ENC = EncryptionKey(ByteArray(32) { 7 })

/** Test helper: a different, deterministic encryption key for each member key. */
fun encKeyFor(k: PublicKey) = EncryptionKey(k.bytes().also { it[1] = 0x77 })

fun sealed(n: Int) = SealedKey(ByteArray(80) { n.toByte() })
