package owntally.ui.groups

import owntally.data.session.GroupView
import owntally.data.session.PeerState
import owntally.data.session.PeerStatus
import owntally.data.session.SyncStatus
import owntally.data.store.ProblemKind
import owntally.data.store.SyncProblem
import owntally.model.PublicKey
import owntally.sync.LinkKind

enum class SyncHeadline { NOT_STARTED, SYNCING, UP_TO_DATE, WAITING }

enum class BadgeTone { GOOD, NEUTRAL, WARN, OFFLINE }

/** One member's line on the Sync screen (design/05): who, how they were reached or why not, and when. */
data class SyncMemberLine(
    val key: PublicKey,
    val name: String,
    val initial: String,
    val colourIndex: Int,
    val detail: String,
    val badge: String,
    val tone: BadgeTone,
)

data class SyncScreenModel(
    val groupName: String,
    val headline: SyncHeadline,
    val headlineText: String,
    val members: List<SyncMemberLine>,
    /** The one dismissible notice of the latest run in which entries were dropped (S4), or null. */
    val notice: String?,
    /** The note: the latest lines of the log of dropped entries (S4), never any content. */
    val rejected: List<String>,
)

/** Turns the sync status of a group into what the Sync screen shows. Nearby and the relay card of the mockup are left out (the app decides, 4 Nov; Nearby is M6). */
object SyncModels {
    private const val MAX_NOTE_LINES = 5

    fun build(view: GroupView, status: SyncStatus, problems: List<SyncProblem>, nowSeconds: Long): SyncScreenModel {
        val state = view.state
        fun nameOf(k: PublicKey) = state.members[k]?.name?.takeIf { it.isNotBlank() } ?: "Someone"
        val peers = state.currentMembers.keys.filter { it != view.me }.sortedBy { nameOf(it).lowercase() }
        val lines = peers.map { line(it, nameOf(it), status.peers[it] ?: PeerStatus(), nowSeconds) }
        val statuses = peers.map { status.peers[it] ?: PeerStatus() }
        val notShared = statuses.maxOfOrNull { it.notShared } ?: 0
        val (headline, text) = when {
            peers.isEmpty() -> SyncHeadline.NOT_STARTED to "There is no one to sync with yet."
            statuses.any { it.state == PeerState.SYNCING } -> SyncHeadline.SYNCING to "Syncing…"
            statuses.all { it.state == PeerState.NEVER } -> SyncHeadline.NOT_STARTED to "Not syncing yet. Your entries are saved on this phone."
            statuses.all { it.state == PeerState.OK } && notShared == 0 ->
                SyncHeadline.UP_TO_DATE to "Up to date with ${peers.size} member${if (peers.size == 1) "" else "s"}"
            else -> SyncHeadline.WAITING to "Waiting for a friend to come online." + when {
                notShared == 0 -> ""
                notShared == 1 -> " 1 entry is saved on this phone and will be sent."
                else -> " $notShared entries are saved on this phone and will be sent."
            }
        }
        val notice = status.notice?.let {
            if (it.cutOff) "${nameOf(it.peer)} sent too many invalid entries, so syncing with them was stopped." else "Some entries from ${nameOf(it.peer)} were rejected."
        }
        val note = problems.take(MAX_NOTE_LINES).map { p ->
            val who = nameOf(p.peer)
            val ago = RelativeTime.ago(nowSeconds, p.atSeconds)
            if (p.kind == ProblemKind.CUT_OFF) "Syncing with $who was stopped after too many invalid entries · $ago"
            else "${p.count} ${if (p.count == 1) "entry" else "entries"} from $who ${if (p.count == 1) "was" else "were"} rejected: ${kindText(p.kind)} · $ago"
        }
        return SyncScreenModel(state.name ?: "Group", headline, text, lines, notice, note)
    }

    /** One short line for a group in the Sync tab, from the status alone. */
    fun shortHeadline(status: SyncStatus): String {
        val peers = status.peers.values
        return when {
            peers.isEmpty() || peers.all { it.state == PeerState.NEVER } -> "Not syncing yet"
            peers.any { it.state == PeerState.SYNCING } -> "Syncing…"
            peers.all { it.state == PeerState.OK } && peers.all { it.notShared == 0 } -> "Up to date"
            else -> "Waiting for a friend to come online"
        }
    }

    private fun entries(n: Int) = "$n ${if (n == 1) "entry" else "entries"} not shared"

    private fun kindText(k: ProblemKind) = when (k) {
        ProblemKind.MALFORMED -> "not a valid entry"
        ProblemKind.WRONG_GROUP -> "for another group"
        ProblemKind.TOO_LARGE -> "too large"
        ProblemKind.BAD_SIGNATURE -> "bad signature"
        ProblemKind.CUT_OFF -> "stopped"
    }

    private fun waitText(seconds: Long): String {
        val minutes = maxOf(1L, (seconds + 59) / 60)
        return if (minutes < 120) "$minutes min" else "${(minutes + 30) / 60} hrs"
    }

    /** Which kind of local link carried the last sync (decision L6): the status line says so. */
    internal fun securityText(s: owntally.sync.LinkSecurity?): String? = when (s) {
        owntally.sync.LinkSecurity.TLS -> "Encrypted link"
        owntally.sync.LinkSecurity.PLAIN -> "Unencrypted link"
        null -> null
    }

    private fun line(key: PublicKey, name: String, p: PeerStatus, now: Long): SyncMemberLine {
        val colour = (key.bytes()[0].toInt() and 0xff) % MembersModels.COLOURS
        fun mk(detail: String, badge: String, tone: BadgeTone) = SyncMemberLine(key, name, name.trim().take(1).uppercase(), colour, detail, badge, tone)
        val shared = if (p.notShared > 0) entries(p.notShared) else null
        val when_ = p.lastSuccessSeconds?.let { RelativeTime.ago(now, it) }
        return when (p.state) {
            PeerState.NEVER -> mk("Not reachable yet", "Not yet", BadgeTone.NEUTRAL)
            PeerState.SYNCING -> mk("Syncing…", "Syncing", BadgeTone.NEUTRAL)
            PeerState.OK -> mk(listOfNotNull(shared, when_, securityText(p.security)).joinToString(" · "), when (p.kind) {
                LinkKind.RELAYED -> "Via relay"
                LinkKind.NEARBY -> "Nearby"
                else -> "Direct"
            }, BadgeTone.GOOD)
            PeerState.UNREACHABLE, PeerState.FAILED -> mk(
                listOfNotNull(shared, when_ ?: "Never synced", p.nextTrySeconds?.let { "trying again in ${waitText(it - now)}" }).joinToString(" · "),
                "Offline", BadgeTone.OFFLINE,
            )
        }
    }
}
