package owntally.ui.nearby

import owntally.data.session.NearbyPeer
import owntally.data.session.NearbyStatus
import owntally.model.PublicKey

/** One member on the Nearby screen. */
data class NearbyLine(val key: PublicKey, val name: String, val initial: String, val text: String, val tone: NearbyTone)

enum class NearbyTone { WORKING, GOOD, PROBLEM, NEUTRAL }

/** The Nearby screen as words (design/16 is the nearest mockup; there is none for Nearby itself). */
class NearbyScreenModel(
    val groupName: String,
    val intro: String,
    val headline: String,
    val lines: List<NearbyLine>,
    val startLabel: String?,
    val stopLabel: String?,
    val lookAgainLabel: String?,
    val note: String?,
)

object NearbyModels {
    const val INTRO = "Sit near your friends and open this screen on each phone. Phones in the same room find each other and swap notes, with no internet."

    /**
     * [names] are the other members of the group by key. [unavailable] says this phone has no Google Play services (Nearby needs them): the
     * screen then says so and offers nothing to start.
     */
    fun build(groupName: String, names: Map<PublicKey, String>, status: NearbyStatus, unavailable: Boolean): NearbyScreenModel {
        if (unavailable) {
            return NearbyScreenModel(
                groupName, INTRO, "Nearby is not available on this phone", emptyList(), null, null, null,
                "Nearby needs Google Play services, which this phone does not have. You can still sync over the same Wi-Fi when the other phones are on it.",
            )
        }
        fun lineOf(key: PublicKey, name: String): NearbyLine {
            val state = status.peers[key] ?: NearbyPeer.LOOKING
            val (text, tone) = if (!status.running && status.peers[key] == null) "Not started" to NearbyTone.NEUTRAL else when (state) {
                NearbyPeer.LOOKING -> "Looking for $name…" to NearbyTone.WORKING
                NearbyPeer.SYNCING -> "Syncing with $name…" to NearbyTone.WORKING
                NearbyPeer.DONE -> "Synced with $name" to NearbyTone.GOOD
                NearbyPeer.NOT_FOUND -> "$name was not found nearby. Ask them to open this screen too." to NearbyTone.PROBLEM
                NearbyPeer.FAILED -> "Syncing with $name did not finish." to NearbyTone.PROBLEM
            }
            return NearbyLine(key, name, name.trim().take(1).uppercase(), text, tone)
        }
        val lines = names.entries.sortedBy { it.value.lowercase() }.map { lineOf(it.key, it.value) }
        val states = status.peers.values
        val headline = when {
            !status.running -> "Ready"
            states.isNotEmpty() && states.all { it == NearbyPeer.DONE } -> "Everyone is up to date"
            states.any { it == NearbyPeer.SYNCING } -> "Syncing…"
            else -> "Looking for friends…"
        }
        val anyProblem = status.running && states.any { it == NearbyPeer.NOT_FOUND || it == NearbyPeer.FAILED }
        return NearbyScreenModel(
            groupName, INTRO, headline, lines,
            startLabel = if (!status.running) "Start" else null,
            stopLabel = if (status.running) "Stop" else null,
            lookAgainLabel = if (anyProblem) "Look again" else null,
            note = if (status.running) "Nearby uses Bluetooth and Wi-Fi and runs only while this screen is on. Stop turns it off." else null,
        )
    }
}
