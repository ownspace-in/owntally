package owntally.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.model.EncryptionKey
import owntally.model.PublicKey

class JoinMessagesTest {
    private val room = GroupRoom(ByteArray(16) { 1 })
    private fun bytes(n: Int, v: Int) = ByteArray(n) { v.toByte() }

    private val all: List<JoinMessage> = listOf(
        JoinMessage.Hello(room, bytes(16, 2)),
        JoinMessage.Challenge(bytes(32, 3), bytes(64, 4)),
        JoinMessage.Request(bytes(200, 5)),
        JoinMessage.Reply(true), JoinMessage.Reply(false),
        JoinMessage.StatusHello(room, PublicKey(bytes(32, 6))),
        JoinMessage.StatusChallenge(bytes(32, 7)),
        JoinMessage.StatusProof(bytes(64, 8)),
        JoinMessage.StatusReply(JoinStatus.PENDING), JoinMessage.StatusReply(JoinStatus.APPROVED),
        // An approved member is also handed the group's epoch keys, sealed to them (R7).
        JoinMessage.StatusReply(JoinStatus.APPROVED, listOf(EpochKey(1, owntally.model.SealedKey(bytes(80, 9))), EpochKey(4_294_967_295L, owntally.model.SealedKey(bytes(1, 1))))),
    )

    @Test fun keysTravelOnlyWithAnApprovedStatusAndWithinTheirLimits() {
        val k = EpochKey(2, owntally.model.SealedKey(bytes(80, 9)))
        for (s in listOf(JoinStatus.UNKNOWN, JoinStatus.PENDING, JoinStatus.DECLINED)) {
            assertFailsWith<IllegalArgumentException>("$s") { JoinMessage.StatusReply(s, listOf(k)) }
            // The same on the wire: an approved reply with keys, re-labelled pending, must not decode.
            val b = JoinCodec.encode(JoinMessage.StatusReply(JoinStatus.APPROVED, listOf(k)))
            b[4] = s.code.toByte()
            assertNull(JoinCodec.decode(b), "keys with status $s")
        }
        assertFailsWith<IllegalArgumentException> { EpochKey(0, owntally.model.SealedKey(bytes(80, 9))) }
        assertFailsWith<IllegalArgumentException> { JoinMessage.StatusReply(JoinStatus.APPROVED, List(JoinLimits.MAX_CHAIN_KEYS + 1) { k }) }
        val oversize = JoinCodec.encode(JoinMessage.StatusReply(JoinStatus.APPROVED, listOf(k))).also { it[7] = 0x05; it[8] = 0x00 } // length 1280 > 1024
        assertNull(JoinCodec.decode(oversize))
        val countOff = JoinCodec.encode(JoinMessage.StatusReply(JoinStatus.APPROVED, listOf(k))).also { it[6] = 2 } // says two keys, carries one
        assertNull(JoinCodec.decode(countOff))
    }

    @Test fun everyMessageRoundTripsAndOnlyExactBytesDecode() {
        for (m in all) {
            val b = JoinCodec.encode(m)
            assertTrue(JoinCodec.isJoin(b), "$m")
            assertContentEquals(b, JoinCodec.encode(assertNotNull(JoinCodec.decode(b), "$m")))
            assertNull(JoinCodec.decode(b + 0), "trailing byte: $m")
            assertNull(JoinCodec.decode(b.copyOf(b.size - 1)), "cut short: $m")
        }
    }

    @Test fun aJoinMessageIsNotASyncMessageAndTheOtherWayRound() {
        val hello = SyncCodec.encode(SyncMessage.Hello(room, SyncSummary(emptyMap())))
        assertFalse(JoinCodec.isJoin(hello))
        assertNull(JoinCodec.decode(hello))
        val join = JoinCodec.encode(all[0])
        assertTrue(SyncCodec.decode(join) is Decoded.Refused)
    }

    @Test fun badBytesDecodeToNullAndNeverThrow() {
        val rnd = kotlin.random.Random(5)
        repeat(2000) {
            val b = rnd.nextBytes(rnd.nextInt(0, 140))
            if (rnd.nextBoolean() && b.size > 4) { b[0] = 0x4F; b[1] = 0x53; b[2] = 1; b[3] = (16 + rnd.nextInt(10)).toByte() }
            JoinCodec.decode(b)
        }
        assertNull(JoinCodec.decode(ByteArray(0)))
        assertNull(JoinCodec.decode(byteArrayOf(0x4F, 0x53, 2, 16)), "another version")
        val reply = JoinCodec.encode(JoinMessage.Reply(true)); reply[reply.size - 1] = 2
        assertNull(JoinCodec.decode(reply), "a reply is 0 or 1")
        val status = JoinCodec.encode(JoinMessage.StatusReply(JoinStatus.PENDING)); status[status.size - 1] = 9
        assertNull(JoinCodec.decode(status), "unknown status")
    }

    @Test fun aRequestBodyRoundTripsStrictly() {
        val body = JoinRequestBody(PublicKey(bytes(32, 1)), EncryptionKey(bytes(32, 2)), "Aarav K.", "+91 98765 43210")
        val b = body.encode()
        val back = assertNotNull(JoinRequestBody.decode(b))
        assertEquals("Aarav K.", back.name); assertEquals("+91 98765 43210", back.phone)
        assertEquals(body.joinerKey, back.joinerKey)
        assertNull(JoinRequestBody.decode(b + 0)); assertNull(JoinRequestBody.decode(b.copyOf(b.size - 1)))
        assertNull(JoinRequestBody.decode(ByteArray(0)))
        val bad = b.copyOf(); bad[66] = 0xC3.toByte(); bad[67] = 0x28 // not UTF-8
        assertNull(JoinRequestBody.decode(bad))
        assertFalse(runCatching { JoinRequestBody(body.joinerKey, body.encryptionKey, "x".repeat(161), "1").encode() }.isSuccess, "name too long")
    }
}

class JoinRequestLowOrderKeyTest {
    @Test fun aRequestWithALowOrderEncryptionKeyIsNotKept() {
        val joiner = owntally.model.PublicKey(ByteArray(32) { 5 })
        val ok = JoinRequestBody(joiner, owntally.model.EncryptionKey(ByteArray(32) { 7 }), "Asha", "+91 98765 43210").encode()
        kotlin.test.assertNotNull(JoinRequestBody.decode(ok))
        for (low in listOf(ByteArray(32), ByteArray(32).also { it[0] = 1 })) {
            val bad = JoinRequestBody(joiner, owntally.model.EncryptionKey(low), "Asha", "+91 98765 43210").encode()
            kotlin.test.assertNull(JoinRequestBody.decode(bad), "a key nobody can seal a group key to")
        }
    }
}
