package owntally.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import owntally.model.PublicKey

/** The sync messages and the fingerprint (docs/WIRE_FORMAT.md section 6), against vectors made by a separate Python encoder. */
class SyncMessagesTest {
    private val vectors: JsonObject = Json.parseToJsonElement(
        SyncMessagesTest::class.java.getResource("/fixtures/sync-vectors.json")!!.readText(),
    ).jsonObject

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun JsonArray.objects() = map { it.jsonObject }
    private fun key(n: Int) = PublicKey(ByteArray(32).also { it[0] = n.toByte() })
    private fun fp(n: Int) = ByteArray(16) { n.toByte() }

    // ---- vectors ------------------------------------------------------------------------------------------------------

    @Test fun everyValidVectorDecodesAndEncodesBackToTheSameBytes() {
        val valid = vectors["valid"]!!.jsonArray.objects()
        assertTrue(valid.size >= 9)
        for (v in valid) {
            val bytes = hex(v["hex"]!!.jsonPrimitive.content)
            val name = v["name"]!!.jsonPrimitive.content
            val decoded = SyncCodec.decode(bytes)
            assertTrue(decoded is Decoded.Ok, "$name: $decoded")
            assertContentEquals(bytes, SyncCodec.encode(decoded.message), name)
        }
    }

    @Test fun everyInvalidVectorIsRefusedWithTheRightCode() {
        val invalid = vectors["invalid"]!!.jsonArray.objects()
        assertTrue(invalid.size >= 27)
        for (v in invalid) {
            val name = v["name"]!!.jsonPrimitive.content
            val expected = ErrorCode.fromNumber(v["refusal"]!!.jsonPrimitive.content.toInt())
            val decoded = SyncCodec.decode(hex(v["hex"]!!.jsonPrimitive.content))
            assertTrue(decoded is Decoded.Refused, "$name was accepted: $decoded")
            assertEquals(expected, decoded.code, name)
        }
    }

    @Test fun theFingerprintChainsMatchThePythonEncoder() {
        for (v in vectors["fingerprints"]!!.jsonArray.objects()) {
            var fp = Fingerprint.ZERO
            for (step in v["steps"]!!.jsonArray.objects()) {
                fp = Fingerprint.next(fp, step["counter"]!!.jsonPrimitive.content.toLong(), step["envelopes"]!!.jsonArray.map { hex(it.jsonPrimitive.content) })
            }
            assertEquals(v["fp"]!!.jsonPrimitive.content, fp.joinToString("") { "%02x".format(it) }, v["name"]!!.jsonPrimitive.content)
        }
    }

    // ---- the vectors' meaning -----------------------------------------------------------------------------------------

    @Test fun aHelloIsReadBackFieldByField() {
        val v = vectors["valid"]!!.jsonArray.objects().first { it["name"]!!.jsonPrimitive.content == "hello-two-authors" }
        val m = (SyncCodec.decode(hex(v["hex"]!!.jsonPrimitive.content)) as Decoded.Ok).message as SyncMessage.Hello
        assertEquals(GroupRoom(ByteArray(16) { it.toByte() }), m.room)
        assertEquals(listOf(key(1), key(2)), m.summary.entries.keys.toList(), "in author order")
        assertEquals(2L, m.summary.entries.getValue(key(1)).counter)
    }

    @Test fun aNewerVersionAndAnUnknownTypeAreRefusedWithTheirOwnCodes() {
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, (SyncCodec.decode(byteArrayOf(0x4F, 0x53, 2, 4) + ByteArray(32)) as Decoded.Refused).code)
        assertEquals(ErrorCode.INVALID, (SyncCodec.decode(byteArrayOf(0x4F, 0x53, 1, 9)) as Decoded.Refused).code)
    }

    // ---- encoding rules -----------------------------------------------------------------------------------------------

    @Test fun theEncoderRefusesWhatTheDecoderWouldRefuse() {
        assertFailsWith<IllegalArgumentException> { SyncMessage.Envelopes(emptyList()) }
        assertFailsWith<IllegalArgumentException> { SyncMessage.Envelopes(List(201) { ByteArray(10) }) }
        assertFailsWith<IllegalArgumentException> { SyncMessage.Envelopes(listOf(ByteArray(0))) }
        assertFailsWith<IllegalArgumentException> { SyncMessage.Envelopes(listOf(ByteArray(65_537))) }
        assertFailsWith<IllegalArgumentException> { SyncMessage.Envelopes(List(5) { ByteArray(60_000) }) }
        assertFailsWith<IllegalArgumentException> { SyncMessage.Error(ErrorCode.INVALID, "x".repeat(201)) }
        assertFailsWith<IllegalArgumentException> { AuthorState(0, fp(1)) }
        assertFailsWith<IllegalArgumentException> { AuthorState(1, ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { SyncSummary(sortedMapOf<PublicKey, AuthorState>().also { m -> repeat(1001) { i -> m[PublicKey(ByteArray(32).also { b -> b[0] = (i / 256).toByte(); b[1] = (i % 256).toByte() })] = AuthorState(1, fp(1)) } }) }
    }

    @Test fun aBatchBuilderStaysInsideTheLimits() {
        val envelopes = List(500) { ByteArray(40_000) { b -> (it + b).toByte() } }
        val batches = SyncMessage.Envelopes.batches(envelopes)
        assertEquals(envelopes.size, batches.sumOf { it.envelopes.size })
        for (b in batches) {
            assertTrue(b.envelopes.size in 1..200)
            assertTrue(b.envelopes.sumOf { it.size } <= 262_144)
            assertTrue(SyncCodec.decode(SyncCodec.encode(b)) is Decoded.Ok)
        }
        assertEquals(envelopes.map { it.toList() }, batches.flatMap { it.envelopes }.map { it.toList() }, "order kept")
        assertEquals(emptyList(), SyncMessage.Envelopes.batches(emptyList()))
    }

    @Test fun randomMessagesRoundTripAndDecodingNeverThrows() {
        val rnd = Random(42)
        repeat(300) {
            val summary = SyncSummary((1..rnd.nextInt(0, 6)).associate { key(rnd.nextInt(1, 200)) to AuthorState(rnd.nextLong(1, 1_000_000), rnd.nextBytes(16)) }.toSortedMap())
            val message: SyncMessage = when (rnd.nextInt(5)) {
                0 -> SyncMessage.Hello(GroupRoom(rnd.nextBytes(16)), summary)
                1 -> SyncMessage.Envelopes(List(rnd.nextInt(1, 5)) { rnd.nextBytes(rnd.nextInt(1, 500)) })
                2 -> SyncMessage.Done(summary)
                3 -> SyncMessage.Reconcile(key(rnd.nextInt(1, 200)))
                else -> SyncMessage.Error(ErrorCode.entries.random(rnd), "text ${rnd.nextInt()}")
            }
            val bytes = SyncCodec.encode(message)
            assertContentEquals(bytes, SyncCodec.encode((SyncCodec.decode(bytes) as Decoded.Ok).message))
            // damaged input is refused or read, never an exception
            val damaged = bytes.copyOf().also { if (it.isNotEmpty()) it[rnd.nextInt(it.size)] = rnd.nextInt().toByte() }
            SyncCodec.decode(damaged)
            SyncCodec.decode(bytes.copyOf(rnd.nextInt(0, bytes.size + 1)))
        }
    }

    // ---- the fingerprint ----------------------------------------------------------------------------------------------

    @Test fun theFingerprintChangesWithAnyVersionAndIgnoresTheOrderOfVersions() {
        val a = ByteArray(100) { 1 }
        val b = ByteArray(100) { 2 }
        val c = ByteArray(100) { 3 }
        val one = Fingerprint.next(Fingerprint.ZERO, 1, listOf(a))
        assertEquals(16, one.size)
        assertTrue(!one.contentEquals(Fingerprint.next(Fingerprint.ZERO, 1, listOf(b))), "a different event")
        assertTrue(!one.contentEquals(Fingerprint.next(Fingerprint.ZERO, 2, listOf(a))), "a different counter")
        assertTrue(!one.contentEquals(Fingerprint.next(Fingerprint.ZERO, 1, listOf(a, b))), "a second version")
        assertContentEquals(Fingerprint.next(Fingerprint.ZERO, 1, listOf(a, b)), Fingerprint.next(Fingerprint.ZERO, 1, listOf(b, a)))
        assertContentEquals(Fingerprint.next(Fingerprint.ZERO, 1, listOf(a, b)), Fingerprint.next(Fingerprint.ZERO, 1, listOf(a, b, a)), "the same version twice is one version")
        assertFailsWith<IllegalArgumentException> { Fingerprint.next(Fingerprint.ZERO, 1, emptyList()) }
        // more than two versions: only the two smallest hashes count, so phones that exchange versions agree
        val three = Fingerprint.next(Fingerprint.ZERO, 1, listOf(a, b, c))
        val smallestTwo = Fingerprint.smallestTwo(listOf(a, b, c))
        assertContentEquals(three, Fingerprint.next(Fingerprint.ZERO, 1, smallestTwo))
        assertEquals(2, smallestTwo.size)
    }

    @Test fun earlierEventsChangeEveryLaterFingerprint() {
        val e = ByteArray(50) { 7 }
        val x = Fingerprint.next(Fingerprint.next(Fingerprint.ZERO, 1, listOf(ByteArray(50) { 1 })), 2, listOf(e))
        val y = Fingerprint.next(Fingerprint.next(Fingerprint.ZERO, 1, listOf(ByteArray(50) { 2 })), 2, listOf(e))
        assertTrue(!x.contentEquals(y))
    }
}
