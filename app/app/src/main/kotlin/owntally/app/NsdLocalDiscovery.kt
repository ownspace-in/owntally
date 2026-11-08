package owntally.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetSocketAddress
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import owntally.model.PublicKey
import owntally.sync.GroupRoom
import owntally.sync.LinkSecurity
import owntally.sync.LocalDiscovery
import owntally.sync.LocalNames

/**
 * Finds the other phones of a group on the same network with Android's own service discovery (mDNS; decision L1). A phone is announced as
 * `_owntally._tcp` under an opaque name made from the room and its key ([LocalNames]): to anyone else on the network it says nothing about
 * the group or the person. Needs no Google services.
 */
class NsdLocalDiscovery(context: Context) : LocalDiscovery {
    private val nsd: NsdManager = context.applicationContext.getSystemService(NsdManager::class.java)

    override fun announce(room: GroupRoom, self: PublicKey, port: Int, security: LinkSecurity): AutoCloseable {
        val info = NsdServiceInfo().apply { serviceName = LocalNames.serviceName(room, self, security); serviceType = TYPE; setPort(port) }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return AutoCloseable { runCatching { nsd.unregisterService(listener) } }
    }

    override suspend fun find(room: GroupRoom, peer: PublicKey, timeoutMs: Long, security: LinkSecurity): InetSocketAddress? {
        val wanted = LocalNames.serviceName(room, peer, security)
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var done = false
                lateinit var discovery: NsdManager.DiscoveryListener
                fun finish(address: InetSocketAddress?) {
                    synchronized(this) { if (done) return; done = true }
                    runCatching { nsd.stopServiceDiscovery(discovery) }
                    cont.resume(address)
                }
                fun resolve(found: NsdServiceInfo, attemptsLeft: Int) {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(found, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val host = info.host
                            if (host != null) finish(InetSocketAddress(host, info.port)) else finish(null)
                        }

                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            // Only one name can be resolved at a time on older Android: try again shortly.
                            if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attemptsLeft > 0) Thread.sleep(200).also { resolve(found, attemptsLeft - 1) } else Unit
                        }
                    })
                }
                discovery = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(type: String) = Unit
                    override fun onServiceFound(info: NsdServiceInfo) {
                        // A name that was taken is made unique by Android with a suffix: match the start.
                        if (info.serviceName == wanted || info.serviceName.startsWith("$wanted ")) resolve(info, 10)
                    }
                    override fun onServiceLost(info: NsdServiceInfo) = Unit
                    override fun onDiscoveryStopped(type: String) = Unit
                    override fun onStartDiscoveryFailed(type: String, errorCode: Int) = finish(null)
                    override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit
                }
                nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
                cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(discovery) } }
            }
        }
    }

    private companion object {
        const val TYPE = "_owntally._tcp."
    }
}
