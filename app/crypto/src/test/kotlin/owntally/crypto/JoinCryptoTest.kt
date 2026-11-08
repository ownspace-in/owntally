package owntally.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.model.GroupId

/** T6.2: the proofs of the join handshake. Each is bound to the ticket, the group, both phones and this one connection. */
class JoinCryptoTest {
    private val seed = MasterSeed(ByteArray(64) { 3 })
    private val host = KeyDerivation.groupIdentity(seed, 0)
    private val joiner = KeyDerivation.groupIdentity(seed, 1)
    private val other = KeyDerivation.groupIdentity(seed, 2)
    private val group = GroupId(ByteArray(16) { 9 })
    private val otherGroup = GroupId(ByteArray(16) { 8 })
    private val ticketId = ByteArray(16) { 5 }
    private val nonce = ByteArray(JoinCrypto.NONCE_SIZE) { 7 }
    private val key = GroupKey(ByteArray(32) { it.toByte() })

    @Test fun theHostProofIsAcceptedOnlyForTheSameTicketGroupNonceAndHost() {
        val sig = JoinCrypto.signHost(host, group, ticketId, nonce)
        assertEquals(64, sig.size)
        assertTrue(JoinCrypto.verifyHost(host.publicKey, group, ticketId, nonce, sig))
        assertFalse(JoinCrypto.verifyHost(other.publicKey, group, ticketId, nonce, sig), "another host key")
        assertFalse(JoinCrypto.verifyHost(host.publicKey, otherGroup, ticketId, nonce, sig), "another group")
        assertFalse(JoinCrypto.verifyHost(host.publicKey, group, ByteArray(16) { 6 }, nonce, sig), "another ticket")
        assertFalse(JoinCrypto.verifyHost(host.publicKey, group, ticketId, ByteArray(32) { 1 }, sig), "another connection")
        assertFalse(JoinCrypto.verifyHost(host.publicKey, group, ticketId, nonce, sig.copyOf().also { it[0] = (it[0] + 1).toByte() }), "a changed signature")
        assertFalse(JoinCrypto.verifyHost(host.publicKey, group, ticketId, nonce, ByteArray(10)), "a short one does not throw")
    }

    @Test fun aHostProofCannotBeUsedAsAJoinerOrStatusProof() {
        val hostSig = JoinCrypto.signHost(host, group, ticketId, nonce)
        val body = ByteArray(20) { 2 }
        assertFalse(JoinCrypto.verifyRequest(host.publicKey, group, host.publicKey, nonce, body, hostSig))
        assertFalse(JoinCrypto.verifyStatus(host.publicKey, group, host.publicKey, nonce, hostSig))
        val req = JoinCrypto.signRequest(joiner, group, host.publicKey, nonce, body)
        assertFalse(JoinCrypto.verifyStatus(joiner.publicKey, group, host.publicKey, nonce, req), "a request signature is not a status proof")
        val st = JoinCrypto.signStatus(joiner, group, host.publicKey, nonce)
        assertTrue(JoinCrypto.verifyStatus(joiner.publicKey, group, host.publicKey, nonce, st))
        assertFalse(JoinCrypto.verifyRequest(joiner.publicKey, group, host.publicKey, nonce, ByteArray(0), st), "a status proof is not a request signature")
    }

    @Test fun theRequestSignatureCoversTheBodyTheHostAndTheNonce() {
        val body = ByteArray(40) { it.toByte() }
        val sig = JoinCrypto.signRequest(joiner, group, host.publicKey, nonce, body)
        assertTrue(JoinCrypto.verifyRequest(joiner.publicKey, group, host.publicKey, nonce, body, sig))
        assertFalse(JoinCrypto.verifyRequest(joiner.publicKey, group, host.publicKey, nonce, body.copyOf().also { it[3] = 99 }, sig))
        assertFalse(JoinCrypto.verifyRequest(joiner.publicKey, group, other.publicKey, nonce, body, sig))
        assertFalse(JoinCrypto.verifyRequest(joiner.publicKey, group, host.publicKey, ByteArray(32), body, sig))
        assertFalse(JoinCrypto.verifyRequest(joiner.publicKey, otherGroup, host.publicKey, nonce, body, sig))
        assertFalse(JoinCrypto.verifyRequest(other.publicKey, group, host.publicKey, nonce, body, sig))
    }

    @Test fun aSealedRequestOpensOnlyWithTheGroupKeyAndTheSameTicketHostAndNonce() {
        val plain = "Aarav +91".toByteArray()
        val sealed = JoinCrypto.sealRequest(key, ticketId, host.publicKey, nonce, plain)
        assertFalse(sealed.toList().containsAll(plain.toList().take(4)) && String(sealed).contains("Aarav"), "not in the clear")
        assertContentEquals(plain, JoinCrypto.openRequest(key, ticketId, host.publicKey, nonce, sealed))
        assertNull(JoinCrypto.openRequest(GroupKey(ByteArray(32) { 1 }), ticketId, host.publicKey, nonce, sealed), "another key")
        assertNull(JoinCrypto.openRequest(key, ByteArray(16) { 6 }, host.publicKey, nonce, sealed), "another ticket")
        assertNull(JoinCrypto.openRequest(key, ticketId, other.publicKey, nonce, sealed), "another host")
        assertNull(JoinCrypto.openRequest(key, ticketId, host.publicKey, ByteArray(32), sealed), "an earlier connection's nonce: a replay")
        assertNull(JoinCrypto.openRequest(key, ticketId, host.publicKey, nonce, sealed.copyOf().also { it[30] = (it[30] + 1).toByte() }), "tampered")
        assertNull(JoinCrypto.openRequest(key, ticketId, host.publicKey, nonce, ByteArray(5)), "too short does not throw")
    }

    @Test fun twoSealsOfTheSameRequestDiffer() {
        val a = JoinCrypto.sealRequest(key, ticketId, host.publicKey, nonce, ByteArray(10))
        val b = JoinCrypto.sealRequest(key, ticketId, host.publicKey, nonce, ByteArray(10))
        assertNotEquals(a.toList(), b.toList())
    }

    @Test fun randomNoncesAreTheRightSizeAndDiffer() {
        val a = JoinCrypto.newNonce()
        assertEquals(JoinCrypto.NONCE_SIZE, a.size)
        assertNotEquals(a.toList(), JoinCrypto.newNonce().toList())
    }
}
