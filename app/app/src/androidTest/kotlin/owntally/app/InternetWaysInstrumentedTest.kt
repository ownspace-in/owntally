package owntally.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import owntally.crypto.KeyDerivation
import owntally.crypto.MasterSeed
import owntally.model.GroupId
import owntally.sync.GroupRoom
import owntally.sync.LinkKind
import owntally.sync.LinkSecurity

/**
 * The real internet ways in this app's configuration (T7.10): two member keys on this one device reach each other through our relay
 * (relay.ownspace.in) and through Iroh (our relay as home relay, n0's discovery), each alone and then together. Needs this device to be
 * online. Two phones on different mobile networks, which is the check that matters, is the owner's (docs/tasks/M7-internet-sync.md).
 */
@RunWith(AndroidJUnit4::class)
class InternetWaysInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val seed = MasterSeed(ByteArray(64) { (0x40 + it).toByte() })
    private val room = GroupRoom.fromGroupId(GroupId(ByteArray(16) { 0x5A }).bytes())

    private fun twoPhones(useIroh: Boolean, useRelay: Boolean, check: suspend (LinkKind, LinkSecurity?) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Fresh keys each time so a run does not meet what an earlier one left in n0's discovery.
        val n = System.nanoTime() and 0xFFFFFF
        val a = KeyDerivation.groupIdentity(seed, n)
        val b = KeyDerivation.groupIdentity(seed, n + 1)
        val wa = InternetWays(context, a, scope, local = null, useIroh = useIroh, useRelay = useRelay)
        val wb = InternetWays(context, b, scope, local = null, useIroh = useIroh, useRelay = useRelay)
        try {
            withTimeout(90_000) {
                val incoming = wb.transport.incoming(room)
                wa.transport.incoming(room)
                val ab = wa.transport.connect(room, b.publicKey)
                val ba = incoming.first()
                assertEquals(b.publicKey, ab.peer, "A reached B")
                assertEquals(a.publicKey, ba.peer, "and B knows it is A")
                ab.send(byteArrayOf(1, 2, 3))
                assertContentEquals(byteArrayOf(1, 2, 3), ba.received.first())
                ba.send(byteArrayOf(9))
                assertContentEquals(byteArrayOf(9), ab.received.first())
                check(ab.kind, ab.security)
            }
        } finally {
            wa.stop(); wb.stop(); scope.cancel()
        }
    }

    @Test
    fun twoKeysReachEachOtherThroughOurRelayAloneWithTheLinkEncryptedEndToEnd() = twoPhones(useIroh = false, useRelay = true) { kind, security ->
        assertEquals(LinkKind.RELAYED, kind)
        assertEquals(LinkSecurity.TLS, security)
    }

    @Test
    fun twoKeysReachEachOtherThroughIrohAlone() = twoPhones(useIroh = true, useRelay = false) { kind, _ ->
        assertTrue(kind == LinkKind.DIRECT || kind == LinkKind.RELAYED, "a link of either kind: $kind")
    }

    @Test
    fun twoKeysReachEachOtherWithBothWaysOpen() = twoPhones(useIroh = true, useRelay = true) { _, _ -> }
}
