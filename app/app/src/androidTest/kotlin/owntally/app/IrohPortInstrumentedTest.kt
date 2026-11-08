package owntally.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import owntally.crypto.KeyDerivation
import owntally.crypto.MasterSeed

/**
 * The native Iroh library in this app's configuration (T7.4): it loads on the Android runtime and, started with the member's key, has that
 * key as its endpoint id (decision I2: a phone's Iroh identity is its member key). Connecting between phones is the owner's check on two
 * phones on mobile networks; nothing here needs another phone.
 */
@RunWith(AndroidJUnit4::class)
class IrohPortInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun theEndpointIdIsTheMemberKeyAndTheEndpointStartsAndStops() = runBlocking {
        val identity = KeyDerivation.groupIdentity(MasterSeed(ByteArray(64) { 9 }), 5)
        val port = IrohPortImpl(context, identity, "https://relay.ownspace.in")
        val id = withTimeout(60_000) { port.start() }
        assertEquals(identity.publicKey, id, "Iroh's endpoint id is the member key")
        port.stop()
    }
}
