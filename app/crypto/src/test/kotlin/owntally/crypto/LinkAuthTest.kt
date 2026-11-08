package owntally.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LinkAuthTest {
    private val seed = MasterSeed(ByteArray(64) { 6 })
    private val a = KeyDerivation.groupIdentity(seed, 0)
    private val b = KeyDerivation.groupIdentity(seed, 1)
    private val transcript = ByteArray(100) { it.toByte() }

    @Test fun eachSidesProofChecksOnlyForItsOwnKeyAndSideAndTranscript() {
        val s = LinkAuth.signAsServer(b, transcript)
        assertEquals(64, s.size)
        assertTrue(LinkAuth.verifyServer(b.publicKey, transcript, s))
        assertFalse(LinkAuth.verifyServer(a.publicKey, transcript, s), "another key")
        assertFalse(LinkAuth.verifyServer(b.publicKey, transcript.copyOf().also { it[5] = 99 }, s), "another transcript")
        assertFalse(LinkAuth.verifyServer(b.publicKey, transcript, s.copyOf().also { it[0] = (it[0] + 1).toByte() }), "changed")
        assertFalse(LinkAuth.verifyServer(b.publicKey, transcript, ByteArray(3)), "short, and no throw")
        assertFalse(LinkAuth.verifyClient(b.publicKey, transcript, s), "a server proof is not a client proof")
        val c = LinkAuth.signAsClient(a, transcript)
        assertTrue(LinkAuth.verifyClient(a.publicKey, transcript, c))
        assertFalse(LinkAuth.verifyServer(a.publicKey, transcript, c), "a client proof is not a server proof")
    }

    @Test fun nonceIsRightSizeAndRandom() {
        assertEquals(32, LinkAuth.newNonce().size)
        assertNotEquals(LinkAuth.newNonce().toList(), LinkAuth.newNonce().toList())
    }
}
