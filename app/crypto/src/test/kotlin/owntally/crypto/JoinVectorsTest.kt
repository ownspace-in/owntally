package owntally.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import owntally.model.GroupId
import owntally.model.PublicKey

/**
 * T6.12: the proofs of joining and of the local link (CRYPTO.md sections 7a, 7b, 7c) against an independent implementation
 * (`crypto_vectors.py`, Python's cryptography package, written from the spec). Ed25519 is deterministic, so our signatures must equal its
 * signatures byte for byte; the sealed request and preview it made must open here.
 */
class JoinVectorsTest {
    private fun String.hex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val root: JsonObject = Json.parseToJsonElement(JoinVectorsTest::class.java.getResource("/crypto-vectors.json")!!.readText()).jsonObject
    private val v: JsonObject = root["join"]!!.jsonObject
    private fun s(name: String) = v[name]!!.jsonPrimitive.content
    private fun bytes(name: String) = s(name).hex()

    private val seed = MasterSeed(Json.parseToJsonElement(JoinVectorsTest::class.java.getResource("/crypto-vectors.json")!!.readText()).jsonObject["seed"]!!.jsonPrimitive.content.hex())
    private val host = KeyDerivation.groupIdentity(seed, v["host"]!!.jsonPrimitive.int.toLong())
    private val joiner = KeyDerivation.groupIdentity(seed, v["joiner"]!!.jsonPrimitive.int.toLong())
    private val group = GroupId(bytes("group"))
    private val ticketId = bytes("ticketId")
    private val nonce = bytes("nonce")
    private val key = GroupKey(bytes("groupKey"))

    @Test fun theJoinSignaturesAreByteForByteTheIndependentOnes() {
        assertEquals(s("hostSignature"), JoinCrypto.signHost(host, group, ticketId, nonce).toHex())
        assertEquals(s("requestSignature"), JoinCrypto.signRequest(joiner, group, host.publicKey, nonce, bytes("body")).toHex())
        assertEquals(s("statusSignature"), JoinCrypto.signStatus(joiner, group, host.publicKey, nonce).toHex())
    }

    @Test fun theLinkAndTlsSignaturesAreByteForByteTheIndependentOnes() {
        assertEquals(s("linkServerSignature"), LinkAuth.signAsServer(host, bytes("transcript")).toHex())
        assertEquals(s("linkClientSignature"), LinkAuth.signAsClient(joiner, bytes("transcript")).toHex())
        assertEquals(s("tlsBindingSignature"), LinkAuth.signTlsBinding(host, bytes("certificateKey")).toHex())
    }

    @Test fun theIndependentSignaturesVerifyHereAndOnlyForTheirOwnPurpose() {
        assertTrue(JoinCrypto.verifyHost(host.publicKey, group, ticketId, nonce, bytes("hostSignature")))
        assertTrue(JoinCrypto.verifyRequest(joiner.publicKey, group, host.publicKey, nonce, bytes("body"), bytes("requestSignature")))
        assertTrue(JoinCrypto.verifyStatus(joiner.publicKey, group, host.publicKey, nonce, bytes("statusSignature")))
        assertTrue(LinkAuth.verifyServer(host.publicKey, bytes("transcript"), bytes("linkServerSignature")))
        assertTrue(LinkAuth.verifyClient(joiner.publicKey, bytes("transcript"), bytes("linkClientSignature")))
        assertTrue(LinkAuth.verifyTlsBinding(host.publicKey, bytes("certificateKey"), bytes("tlsBindingSignature")))
        // the labels keep purposes apart even on the same fields
        assertFalse(LinkAuth.verifyClient(host.publicKey, bytes("transcript"), bytes("linkServerSignature")))
        assertFalse(LinkAuth.verifyServer(host.publicKey, bytes("transcript"), bytes("linkClientSignature")))
        assertFalse(JoinCrypto.verifyStatus(joiner.publicKey, group, host.publicKey, nonce, bytes("requestSignature")))
    }

    @Test fun aRequestAndAPreviewSealedByTheIndependentImplementationOpenHereAndNotAsEachOther() {
        assertContentEquals(bytes("requestPlain"), JoinCrypto.openRequest(key, ticketId, host.publicKey, nonce, bytes("sealedRequest")))
        assertContentEquals(bytes("previewPlain"), JoinCrypto.openPreview(key, ticketId, host.publicKey, nonce, bytes("sealedPreview")))
        assertNull(JoinCrypto.openPreview(key, ticketId, host.publicKey, nonce, bytes("sealedRequest")), "a request is not a preview")
        assertNull(JoinCrypto.openRequest(key, ticketId, host.publicKey, nonce, bytes("sealedPreview")), "a preview is not a request")
        assertNull(JoinCrypto.openRequest(key, ticketId, PublicKey(ByteArray(32) { 3 }), nonce, bytes("sealedRequest")), "another host")
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
