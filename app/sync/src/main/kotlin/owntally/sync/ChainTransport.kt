package owntally.sync

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import owntally.model.PublicKey
import kotlin.coroutines.coroutineContext

/** Why a member could not be reached by any way, so the screen can say what is wrong and whose it is. */
enum class RelayResult {
    /** The chain has no relay way. */
    NOT_TRIED,

    /** This phone could not even open the relay (no internet, or the relay is down). */
    RELAY_DOWN,

    /** The relay was there but the other phone did not answer through it (and not directly): it is offline or somewhere neither way reaches. */
    PEER_NOT_THERE,
}

/** No way connected. [relay] says how the relay went. */
class ChainUnreachable(peer: PublicKey, val relay: RelayResult, cause: Throwable?) : PeerUnreachable(peer, cause)

class ChainConfig(
    /** How long the same-network link gets to connect before the internet is tried (a phone on the same Wi-Fi is not made to wait for it). */
    val localLookMs: Long = 2_000,
    /** DESIGN.md: the WebSocket relay joins after 3 s if Iroh has not connected (at once if Iroh has failed). */
    val relayDelayMs: Long = 3_000,
    /** DESIGN.md: connect 15 s, overall. */
    val connectMs: Long = 15_000,
)

/**
 * The way a phone reaches another when the app syncs by itself (M7, T7.5; DESIGN.md "Sync transports"): the same-network link first, for a short
 * while; then the internet, **Iroh first, the WebSocket relay joining after 3 s if Iroh has not connected, the first to connect winning** and the
 * other being cancelled; 15 s for all of it. (Nearby is not here: it runs only when the person asks.) A phone that answers with the wrong key
 * on one way is a refusal on that way only: the others are still tried, because every one of them proves the key by itself, and if none
 * connects the refusal is what is reported. Incoming connections from all of them arrive together.
 */
class ChainTransport(
    private val scope: CoroutineScope,
    private val local: Transport?,
    private val iroh: Transport?,
    private val relay: Transport?,
    private val config: ChainConfig = ChainConfig(),
) : Transport {
    private val all = listOfNotNull(local, iroh, relay)

    private val merged = java.util.concurrent.ConcurrentHashMap<GroupRoom, Channel<Link>>()

    override fun incoming(room: GroupRoom): Flow<Link> {
        var created = false
        val into = merged.computeIfAbsent(room) { created = true; Channel(Channel.UNLIMITED) }
        if (created) {
            // The flows are asked for at once, so a way that starts listening when asked does so now.
            val flows = all.map { it.incoming(room) }
            for (f in flows) scope.launch { f.collect { into.trySend(it) } }
        }
        return into.receiveAsFlow()
    }

    override suspend fun connect(room: GroupRoom, peer: PublicKey): Link {
        val failures = CopyOnWriteArrayList<Failure>()
        try {
            return withTimeout(config.connectMs) { attempt(room, peer, failures) }
        } catch (e: TimeoutCancellationException) {
            throw failures.firstOrNull { it.error is LinkAuthFailed }?.error ?: ChainUnreachable(peer, relayResult(failures), e)
        }
    }

    private enum class Way { LOCAL, IROH, RELAY }
    private class Failure(val way: Way, val error: Exception)

    private fun relayResult(failures: List<Failure>): RelayResult {
        if (relay == null) return RelayResult.NOT_TRIED
        val f = failures.lastOrNull { it.way == Way.RELAY } ?: return RelayResult.PEER_NOT_THERE
        // Opening the relay itself failed (a plain I/O error), as opposed to the other phone not answering through it (unreachable or refused).
        return if (f.error is java.io.IOException && f.error !is PeerUnreachable && f.error !is LinkAuthFailed) RelayResult.RELAY_DOWN else RelayResult.PEER_NOT_THERE
    }

    private suspend fun attempt(room: GroupRoom, peer: PublicKey, failures: MutableList<Failure>): Link {
        if (local != null) {
            try {
                withTimeoutOrNull(config.localLookMs) { local.connect(room, peer) }?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += Failure(Way.LOCAL, e)
            }
        }
        return coroutineScope {
            val winner = CompletableDeferred<Link>()
            val irohDone = CompletableDeferred<Unit>()

            suspend fun race(way: Way, t: Transport) {
                try {
                    val link = t.connect(room, peer)
                    if (!winner.complete(link)) link.close() // somebody else won in the same moment
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += Failure(way, e)
                }
            }

            val irohJob = launch { try { if (iroh != null) race(Way.IROH, iroh) } finally { irohDone.complete(Unit) } }
            val relayJob = launch {
                if (relay == null) return@launch
                // The relay joins after the delay if Iroh has not connected, or at once if Iroh has already given up.
                withTimeoutOrNull(config.relayDelayMs) { irohDone.await() }
                if (!winner.isCompleted) race(Way.RELAY, relay)
            }
            launch {
                joinAll(irohJob, relayJob)
                if (!winner.isCompleted) winner.completeExceptionally(verdict(peer, failures))
            }
            val link = winner.await()
            coroutineContext.cancelChildren()
            link
        }
    }

    /** What to report when no way connected: a refusal beats "not there", and "not available" is only said when nothing was even tried. */
    private fun verdict(peer: PublicKey, failures: List<Failure>): Exception =
        failures.firstOrNull { it.error is LinkAuthFailed }?.error
            ?: (if (failures.isNotEmpty() && failures.all { it.error is TransportUnavailable }) failures.first().error else null)
            ?: ChainUnreachable(peer, relayResult(failures), failures.firstOrNull()?.error)
}
