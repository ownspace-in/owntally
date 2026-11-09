package owntally.data.session

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import owntally.crypto.Clock
import owntally.crypto.Ticket
import owntally.data.secrets.SecretLoad
import owntally.data.secrets.SecretStore

/**
 * An invite link that came from outside the app (the person tapped it) and waits until the person can use it: past first-run setup and the lock
 * (decision A1, M9). One at a time, kept sealed in the [SecretStore] (it holds the group key), removed when used, refused or out of time.
 */
class PendingInvites(private val secrets: SecretStore, private val clock: Clock) {
    enum class Received { Kept, NotAnInvite, Expired }

    private val mutableChanges = MutableStateFlow(0)

    /** Goes up each time a link arrives (kept or refused), so a screen already showing notices it. */
    val changes: StateFlow<Int> get() = mutableChanges

    private val problem = AtomicReference<Received?>(null)

    /** Checks the link's ticket and keeps it if it is good and still has time; a refused link leaves a message for [takeProblem]. */
    fun receive(link: String): Received {
        val ticket = Ticket.fromShared(link)
        val result = when {
            ticket == null -> Received.NotAnInvite
            ticket.isExpiredForJoiner(clock.nowSeconds()) -> Received.Expired
            else -> { secrets.savePendingInvite(link, ticket.expiresAtSeconds); Received.Kept }
        }
        problem.set(if (result == Received.Kept) null else result)
        mutableChanges.value = mutableChanges.value + 1
        return result
    }

    /** A link that reached the app but is not an invite link (another host, no code, ...): nothing is kept; the message is left for [takeProblem]. */
    fun refuse() {
        problem.set(Received.NotAnInvite)
        mutableChanges.value = mutableChanges.value + 1
    }

    /** The waiting link, or null; a link whose time has run out, or that cannot be read, is forgotten here. */
    fun waiting(): String? = when (val r = secrets.loadPendingInvite()) {
        SecretLoad.Missing -> null
        SecretLoad.Unreadable -> { secrets.clearPendingInvite(); null }
        is SecretLoad.Found -> {
            val ticket = Ticket.fromShared(r.value.link)
            if (ticket == null || ticket.isExpiredForJoiner(clock.nowSeconds())) { secrets.clearPendingInvite(); null } else r.value.link
        }
    }

    /** What went wrong with the link that arrived last, once; null if it was fine or the message was already taken. */
    fun takeProblem(): Received? = problem.getAndSet(null)

    fun clear() = secrets.clearPendingInvite()
}
