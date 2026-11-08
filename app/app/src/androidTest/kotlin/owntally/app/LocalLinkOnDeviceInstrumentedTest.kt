package owntally.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
import owntally.sync.GroupRoom
import owntally.sync.LocalConfig
import owntally.sync.LocalNames
import owntally.sync.LocalNode

/**
 * The local link on a real Android runtime (T6.7): two phones' worth of nodes in this one process find each other through Android's own
 * service discovery (mDNS), prove their keys and exchange messages over real sockets. Two real phones on one Wi-Fi are the owner's check.
 */
@RunWith(AndroidJUnit4::class)
class LocalLinkOnDeviceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val seed = MasterSeed(ByteArray(64) { 3 })
    private val a = KeyDerivation.groupIdentity(seed, 0)
    private val b = KeyDerivation.groupIdentity(seed, 1)
    private val room = GroupRoom.fromGroupId(ByteArray(16) { 9 })
    private val c = KeyDerivation.groupIdentity(seed, 7) // its own key, so a name announced by the other test is never mistaken for it

    @Test
    fun aPhoneIsFoundByTheNameMadeFromItsRoomAndKeyAndNotByAnyOther() = runBlocking {
        val discovery = NsdLocalDiscovery(context)
        val handle = discovery.announce(room, c.publicKey, 40_123)
        try {
            val found = withTimeout(30_000) { discovery.find(room, c.publicKey, 20_000) }
            assertNotNull(found, "found through mDNS")
            assertEquals(40_123, found.port)
            assertNull(discovery.find(room, a.publicKey, 3_000), "another key is not found")
            assertNull(discovery.find(GroupRoom(ByteArray(16) { 1 }), c.publicKey, 3_000), "nor the same key in another room")
            assertEquals(20, LocalNames.serviceName(room, c.publicKey).length)
        } finally {
            handle.close()
        }
    }

    @Test
    fun twoNodesFindEachOtherByServiceDiscoveryAndTalkOverASocket() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val nodeA = LocalNode(NsdLocalDiscovery(context), scope, LocalConfig(findTimeoutMs = 20_000))
        val nodeB = LocalNode(NsdLocalDiscovery(context), scope, LocalConfig(findTimeoutMs = 20_000))
        try {
            val incoming = nodeB.transportFor(b).incoming(room)
            val link = withTimeout(60_000) { nodeA.transportFor(a).connect(room, b.publicKey) }
            val other = withTimeout(20_000) { incoming.first() }
            assertEquals(b.publicKey, link.peer)
            assertEquals(a.publicKey, other.peer)
            link.send(byteArrayOf(1, 2, 3))
            assertContentEquals(byteArrayOf(1, 2, 3), withTimeout(10_000) { other.received.first() })
            other.send(ByteArray(300_000) { it.toByte() })
            assertEquals(300_000, withTimeout(10_000) { link.received.first() }.size)
        } finally {
            nodeA.close(); nodeB.close(); scope.cancel()
        }
    }
}
