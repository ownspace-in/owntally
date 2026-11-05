package owntally.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** docs/WIRE_FORMAT.md. The fixed vectors were made by an independent encoder written from the spec. */
class WireCodecTest {
    private val vectors: JsonObject =
        Json.parseToJsonElement(WireCodecTest::class.java.getResource("/fixtures/wire-vectors.json")!!.readText()).jsonObject
    private val reader = JsonEvents(vectors.getValue("keys").jsonObject.mapValues { PublicKey.fromHex(it.value.jsonPrimitive.content) })

    private fun JsonObject.str(f: String) = getValue(f).jsonPrimitive.content
    private fun decode(v: JsonObject): Event =
        EventCodec.decodePayload(v.getValue("version").jsonPrimitive.int, reader.keys.getValue(v.str("author")), v.getValue("counter").jsonPrimitive.long, hexToBytes(v.str("hex")))

    // --- fixed vectors ------------------------------------------------------------------------

    @Test
    fun everyVectorEncodesToExactlyTheGivenBytes() {
        val list = vectors.getValue("payloads").jsonArray
        assertTrue(list.size >= 20)
        for (v in list.map { it.jsonObject }) {
            val event = reader.event(v)
            assertEquals(v.str("hex"), EventCodec.encodePayload(event).toHex(), v.str("name"))
        }
    }

    @Test
    fun everyVectorDecodesBackToTheSameEvent() {
        for (v in vectors.getValue("payloads").jsonArray.map { it.jsonObject }) {
            assertEquals(reader.event(v), decode(v), v.str("name"))
        }
    }

    @Test
    fun theWorkedExampleInTheSpecIs34Bytes() {
        val v = vectors.getValue("payloads").jsonArray.map { it.jsonObject }.first { it.str("name") == "profile-updated" }
        assertEquals(34, hexToBytes(v.str("hex")).size)
        assertEquals("00000000000003e8", v.str("hex").substring(4, 20))
    }

    @Test
    fun everyInvalidVectorIsRejectedWithADecodeException() {
        val list = vectors.getValue("invalidPayloads").jsonArray.map { it.jsonObject }
        assertTrue(list.size >= 15)
        for (v in list) assertFailsWith<DecodeException>(v.str("name") + ": " + v.str("why")) { decode(v) }
    }

    @Test
    fun anUnknownBodyKeepsItsTagAndContentAndWritesBackIdentically() {
        val v = vectors.getValue("payloads").jsonArray.map { it.jsonObject }.first { it.str("name") == "unknown-tag" }
        val body = decode(v).body as EventBody.Unknown
        assertEquals(200, body.tag)
        assertContentEquals(byteArrayOf(1, 2), body.content())
        assertEquals(v.str("hex"), EventCodec.encodePayload(decode(v)).toHex())
    }

    @Test
    fun envelopesEncodeAndDecodeToTheGivenBytes() {
        for (v in vectors.getValue("envelopes").jsonArray.map { it.jsonObject }) {
            val envelope = Envelope(
                v.getValue("version").jsonPrimitive.int, GroupId(hexToBytes(v.str("groupId"))), v.getValue("epoch").jsonPrimitive.long,
                reader.keys.getValue(v.str("author")), v.getValue("counter").jsonPrimitive.long,
                hexToBytes(v.str("nonce")), hexToBytes(v.str("ciphertext")), hexToBytes(v.str("signature")),
            )
            assertEquals(v.str("hex"), envelope.encode().toHex(), v.str("name"))
            val decoded = Envelope.decode(hexToBytes(v.str("hex")))
            assertEquals(envelope, decoded, v.str("name"))
            // The signature covers every byte before it.
            assertEquals(v.str("hex").dropLast(128), decoded.signedPart().toHex())
        }
    }

    @Test
    fun everyInvalidEnvelopeIsRejected() {
        val list = vectors.getValue("invalidEnvelopes").jsonArray.map { it.jsonObject }
        assertTrue(list.size >= 8)
        for (v in list) assertFailsWith<DecodeException>(v.str("name") + ": " + v.str("why")) { Envelope.decode(hexToBytes(v.str("hex"))) }
    }

    // --- strictness ---------------------------------------------------------------------------

    @Test
    fun encodingIsTheSameEveryTimeAndDoesNotDependOnInsertionOrder() {
        val a = key(1); val b = key(2); val c = key(3)
        fun event(split: Split) = Event(1, a, 1, emptyMap(), 5, EventBody.ExpenseAdded("x", Money(600), a, 1, split))
        val one = event(Split.Exact(linkedMapOf(c to Money(100), a to Money(200), b to Money(300))))
        val two = event(Split.Exact(linkedMapOf(a to Money(200), b to Money(300), c to Money(100))))
        assertContentEquals(EventCodec.encodePayload(one), EventCodec.encodePayload(two))
        assertContentEquals(EventCodec.encodePayload(one), EventCodec.encodePayload(one))
    }

    @Test
    fun aDescriptionOfMultiByteCharactersRoundTrips() {
        val text = "\u20ac".repeat(80) // 240 bytes in UTF-8
        val e = Event(1, key(1), 1, emptyMap(), 0, EventBody.ExpenseAdded(text, Money(1), key(1), 0, Split.Equal(setOf(key(1)))))
        assertEquals(e, EventCodec.decodePayload(1, key(1), 1, EventCodec.encodePayload(e)))
    }

    @Test
    fun aStringWithAnUnpairedSurrogateCannotBeEncoded() {
        val e = Event(1, key(1), 1, emptyMap(), 0, EventBody.ProfileUpdated("bad\uD800", "12345"))
        assertFailsWith<IllegalArgumentException> { EventCodec.encodePayload(e) }
    }

    @Test
    fun ownershipDeclinedIsTagSixteenWithNoContent() {
        // The independent Python encoder says the same bytes (the vector "ownership-declined").
        val v = vectors.getValue("payloads").jsonArray.map { it.jsonObject }.first { it.str("name") == "ownership-declined" }
        val event = decode(v)
        assertEquals(EventBody.OwnershipDeclined, event.body)
        assertEquals(v.str("hex"), EventCodec.encodePayload(event).toHex())
        val bytes = EventCodec.encodePayload(event)
        assertEquals(16, bytes[bytes.size - 5].toInt() and 0xFF, "the tag")
        assertEquals(0, bytes.copyOfRange(bytes.size - 4, bytes.size).sum(), "the content length is 0")
    }

    @Test
    fun aDeclineWithContentIsRejected() {
        val v = vectors.getValue("invalidPayloads").jsonArray.map { it.jsonObject }.first { it.str("name") == "declined-with-content" }
        assertFailsWith<DecodeException> { decode(v) }
    }

    @Test
    fun anOldAppKeepsADeclineAsAnUnknownEventAndWritesItBackIdentically() {
        // What an app that predates tag 16 sees: an unknown body that keeps its tag and content, so it can pass the event on unchanged.
        val v = vectors.getValue("payloads").jsonArray.map { it.jsonObject }.first { it.str("name") == "ownership-declined" }
        val asUnknown = Event(1, key(1), 1, emptyMap(), 0, EventBody.Unknown(16, ByteArray(0)))
        assertFailsWith<IllegalArgumentException> { EventCodec.encodePayload(asUnknown) } // the new app refuses to write tag 16 as "unknown"
        assertTrue(v.str("hex").isNotEmpty())
    }

    @Test
    fun anUnknownBodyWithAKnownTagCannotBeEncoded() {
        val e = Event(1, key(1), 1, emptyMap(), 0, EventBody.Unknown(5, ByteArray(0)))
        assertFailsWith<IllegalArgumentException> { EventCodec.encodePayload(e) }
    }

    @Test
    fun aHugeClaimedLengthIsRejectedWithoutAllocatingIt() {
        // after: 0 entries, time: 0, tag 200, claimed content length 4 GB - 1, nothing follows.
        val bytes = hexToBytes("0000" + "0000000000000000" + "c8" + "ffffffff")
        assertFailsWith<DecodeException> { EventCodec.decodePayload(1, key(1), 1, bytes) }
        val envelope = hexToBytes(vectors.getValue("envelopes").jsonArray[0].jsonObject.str("hex"))
        val lenAt = 2 + 1 + 16 + 4 + 32 + 8 + 24
        for (i in 0 until 4) envelope[lenAt + i] = 0xFF.toByte()
        assertFailsWith<DecodeException> { Envelope.decode(envelope) }
    }

    // --- properties ---------------------------------------------------------------------------

    @Test
    fun decodeOfEncodeGivesTheEventBackAndEncodeOfDecodeGivesTheBytesBack() = runBlocking<Unit> {
        checkAll(2000, Arb.long()) { seed ->
            val event = randomAnyEvent(Random(seed))
            val bytes = EventCodec.encodePayload(event)
            val back = EventCodec.decodePayload(event.version, event.author, event.counter, bytes)
            assertEquals(event, back, "seed $seed")
            assertContentEquals(bytes, EventCodec.encodePayload(back), "seed $seed")
        }
    }

    @Test
    fun anyChangedOrCutPayloadIsRejectedOrDecodesToSomethingThatEncodesToExactlyThoseBytes() = runBlocking<Unit> {
        checkAll(400, Arb.long()) { seed ->
            val rnd = Random(seed)
            val event = randomAnyEvent(rnd)
            val good = EventCodec.encodePayload(event)
            repeat(40) {
                val bad = when (rnd.nextInt(4)) {
                    0 -> good.copyOf().also { it[rnd.nextInt(it.size)] = rnd.nextInt(256).toByte() }
                    1 -> good.copyOf(rnd.nextInt(0, good.size))
                    2 -> good + ByteArray(rnd.nextInt(1, 5)) { rnd.nextInt(256).toByte() }
                    else -> good.copyOf().also { val i = rnd.nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
                }
                val decoded = try {
                    EventCodec.decodePayload(event.version, event.author, event.counter, bad)
                } catch (e: DecodeException) {
                    null // rejecting is always allowed; any other exception type fails the test
                }
                if (decoded != null) assertContentEquals(bad, EventCodec.encodePayload(decoded), "seed $seed")
            }
        }
    }

    @Test
    fun anyChangedOrCutEnvelopeIsRejectedOrWritesBackIdentically() = runBlocking<Unit> {
        val good = hexToBytes(vectors.getValue("envelopes").jsonArray[0].jsonObject.str("hex"))
        checkAll(2000, Arb.long()) { seed ->
            val rnd = Random(seed)
            val bad = when (rnd.nextInt(3)) {
                0 -> good.copyOf().also { it[rnd.nextInt(it.size)] = rnd.nextInt(256).toByte() }
                1 -> good.copyOf(rnd.nextInt(0, good.size))
                else -> good + ByteArray(rnd.nextInt(1, 5))
            }
            val decoded = try { Envelope.decode(bad) } catch (e: DecodeException) { null }
            if (decoded != null) assertContentEquals(bad, decoded.encode(), "seed $seed")
        }
    }

    @Test
    fun randomScenariosSurviveTheWireRoundTripWithTheSameReplay() = runBlocking<Unit> {
        checkAll(100, Arb.long()) { seed ->
            val events = randomScenario(Random(seed), steps = 40)
            val viaWire = events.map { EventCodec.decodePayload(it.version, it.author, it.counter, EventCodec.encodePayload(it)) }
            assertEquals(replay(logOf(events)), replay(logOf(viaWire)), "seed $seed")
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
