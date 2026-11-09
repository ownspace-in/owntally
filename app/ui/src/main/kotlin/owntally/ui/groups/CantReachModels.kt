package owntally.ui.groups

import owntally.data.session.GroupView
import owntally.data.session.PeerState
import owntally.data.session.PeerStatus
import owntally.model.PublicKey
import owntally.sync.LinkKind
import owntally.sync.RelayResult

enum class CantReachTone { WARNING, GOOD }

/**
 * "Can't reach <name>" (design/16 and 16b, as the app decides about the relay since 4 Nov: there is no "turn on the relay" button). It says why,
 * that the relay is used by itself, and the two things the person can do: meet up (Nearby) or try again later.
 */
class CantReachModel(
    val title: String,
    val bannerTitle: String,
    val bannerBody: String,
    val bannerTone: CantReachTone,
    val waysHeading: String,
    val relayTitle: String,
    val relayBody: String,
    /** What the relay did the last time (design/16b: "Synced via relay"), or null. */
    val relayStatus: String?,
    val tryNowLabel: String,
    val nearbyTitle: String,
    val nearbySubtitle: String,
    val laterTitle: String,
    val laterSubtitle: String,
    val footer: String,
    /** True while a try is under way (the button says so and does nothing). */
    val trying: Boolean,
)

object CantReachModels {
    fun build(view: GroupView, member: PublicKey, status: PeerStatus, nowSeconds: Long): CantReachModel =
        build(view.state.members[member]?.name?.takeIf { it.isNotBlank() } ?: "Someone", status, nowSeconds)

    fun build(name: String, status: PeerStatus, nowSeconds: Long): CantReachModel {
        val n = status.notShared
        val safe = when (n) {
            0 -> "Nothing is waiting to be sent."
            1 -> "Your 1 new entry is safe on this phone and will sync as soon as you connect."
            else -> "Your $n new entries are safe on this phone and will sync as soon as you connect."
        }
        val reached = status.state == PeerState.OK
        val (bannerTitle, bannerBody, tone) = when {
            reached -> Triple(
                "Connected to $name",
                "Synced ${if (status.kind == LinkKind.RELAYED) "through the relay" else "directly"}" + if (status.notShared == 0) ". Nothing is waiting to be sent." else ". $safe",
                CantReachTone.GOOD,
            )
            status.relay == RelayResult.RELAY_DOWN -> Triple(
                "No way to $name from here",
                "This phone can't reach the relay, so only direct links can be tried, and those did not work. Check your internet connection. $safe",
                CantReachTone.WARNING,
            )
            else -> Triple(
                "No connection to $name",
                "$name's phone is offline, or on a network that blocks direct links, and the relay found no one there either. $safe",
                CantReachTone.WARNING,
            )
        }
        val wait = status.nextTrySeconds?.let { SyncModels.waitText(it - nowSeconds) }
        val relayStatus = when {
            status.state == PeerState.SYNCING -> "Trying now…"
            reached && status.kind == LinkKind.RELAYED -> "Synced through the relay."
            status.relay == RelayResult.RELAY_DOWN -> "The relay can't be reached from this phone." + (wait?.let { " Trying again in $it." } ?: "")
            status.state == PeerState.UNREACHABLE || status.state == PeerState.FAILED -> "Not connected." + (wait?.let { " Trying again in $it." } ?: "")
            else -> null
        }
        return CantReachModel(
            title = "Can't reach $name",
            bannerTitle = bannerTitle, bannerBody = bannerBody, bannerTone = tone,
            waysHeading = "WAYS TO CONNECT",
            relayTitle = "The relay is used automatically",
            relayBody = "When a direct link fails, OwnTally passes encrypted data through relay.ownspace.in. It sees that two phones connect, never what you sync. Nobody has to turn it on.",
            relayStatus = relayStatus,
            tryNowLabel = if (status.state == PeerState.SYNCING) "Trying…" else "Try now",
            nearbyTitle = "Meet and sync nearby",
            nearbySubtitle = "No internet or relay needed",
            laterTitle = "Try again later",
            laterSubtitle = "$name may be offline or on a different network" + (wait?.let { ". OwnTally tries again in $it." } ?: ""),
            footer = "Entries are never lost. Any member who has them can pass them on, not only you.",
            trying = status.state == PeerState.SYNCING,
        )
    }
}
