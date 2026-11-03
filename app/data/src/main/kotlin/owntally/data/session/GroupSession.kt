package owntally.data.session

import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import owntally.crypto.Clock
import owntally.crypto.EventSealing
import owntally.crypto.GroupKeyring
import owntally.crypto.OpenResult
import owntally.data.repo.AppendResult
import owntally.data.repo.GroupRepository
import owntally.data.repo.IdentityRepository
import owntally.data.secrets.SecretStore
import owntally.model.Envelope
import owntally.model.Event
import owntally.model.EventBody
import owntally.model.EventId
import owntally.model.EventLog
import owntally.model.GroupId
import owntally.model.GroupState
import owntally.model.IgnoreReason
import owntally.model.Money
import owntally.model.Payment
import owntally.model.PublicKey
import owntally.model.Split
import owntally.model.balances
import owntally.model.replay
import owntally.model.settlementPlan

/**
 * What could and could not be read. [waitingForKey] are epochs whose key is not on this phone yet; their envelopes are kept and
 * opened when the key arrives. [unreadable] envelopes had the right epoch but did not open with the key held. [damaged] stored
 * entries could not even be decoded. None of these are shown as events; all stay stored.
 */
data class Counts(val opened: Int, val waitingForKey: Set<Long>, val unreadable: Int, val damaged: Int)

/** Everything a screen needs about one group, derived from the stored events. */
data class GroupView(
    val state: GroupState,
    /** This phone's member key in this group. */
    val me: PublicKey,
    val balances: Map<PublicKey, Money>,
    val plan: List<Payment>,
    val counts: Counts,
    /** Epochs whose key is stored but cannot be opened (for example the Keystore key was lost). */
    val unreadableKeyEpochs: Set<Long>,
)

sealed class AddResult {
    data class Added(val id: EventId) : AddResult()

    /** The core's rules would ignore this event, so it was not written. */
    data class Refused(val reason: IgnoreReason) : AddResult()

    /** There is no key for the group's current epoch on this phone, so nothing new can be sealed. */
    data object NoKey : AddResult()
}

/**
 * One group as the screens see it: its stored envelopes opened with the group's keys, replayed with the rules of `core-model`,
 * and published as a [StateFlow]. New events by this phone's user are checked against those rules first, then sealed, signed
 * and stored. Envelopes that cannot be opened stay stored, are counted, and are tried again when keys arrive ([refresh]).
 */
class GroupSession(
    private val groupId: GroupId,
    private val repo: GroupRepository,
    private val secrets: SecretStore,
    identity: IdentityRepository,
    private val clock: Clock,
    /** Called after every new state is published (for example to refresh a list of groups). */
    private val onChange: () -> Unit = {},
) {
    private val me = checkNotNull(
        repo.group(groupId)?.let { identity.groupIdentity(it.keySlot) },
    ) { "no such group, or no usable seed" }

    private val log = EventLog()
    private val seen = HashSet<String>()
    private val waiting = ArrayList<Envelope>()
    private var ring = GroupKeyring(groupId)
    private var unreadableKeyEpochs = emptySet<Long>()
    private var opened = 0
    private var unreadable = 0
    private var damaged = 0

    private val state = MutableStateFlow(compute())
    val view: StateFlow<GroupView> get() = state

    init {
        refresh()
    }

    /** Looks for envelopes stored since last time and for keys that have arrived, and publishes the new state. */
    @Synchronized
    fun refresh() {
        sync()
        publish()
    }

    fun addExpense(description: String, total: Money, paidBy: PublicKey, day: Long, split: Split): AddResult =
        append(EventBody.ExpenseAdded(description, total, paidBy, day, split))

    fun deleteExpense(expense: EventId): AddResult = append(EventBody.ExpenseDeleted(expense))

    /**
     * Writes an event by this phone's user. The event is first put through the core's replay rules on a copy of the log; if the
     * rules would ignore it, nothing is sealed or stored and the reason is returned.
     */
    @Synchronized
    fun append(body: EventBody): AddResult {
        sync()
        val current = replay(log)
        val epoch = current.epoch
        val key = if (epoch >= 1) ring[epoch] else null
        if (key == null) {
            publish()
            return AddResult.NoKey
        }
        val summary = log.summary()
        val counter = (summary[me.publicKey] ?: 0L) + 1
        check(log.allEvents().none { it.author == me.publicKey && it.counter >= counter }) { "this phone's own events have a gap" }
        val event = Event(1, me.publicKey, counter, summary, clock.nowSeconds() * 1000, body)

        val trial = log.copy().also { it.append(event) }
        replay(trial).ignored.firstOrNull { it.id == event.id }?.let {
            publish()
            return AddResult.Refused(it.reason)
        }

        val envelope = EventSealing.seal(me, groupId, epoch, key, event)
        when (val stored = repo.appendEnvelope(groupId, envelope)) {
            AppendResult.Added -> Unit
            AppendResult.Duplicate -> error("this phone's own event was already stored")
            is AppendResult.Rejected -> error("the store refused this phone's own event (${stored.because})")
        }
        seen += fingerprint(envelope)
        log.append(event)
        opened++
        publish()
        return AddResult.Added(event.id)
    }

    private fun fingerprint(e: Envelope): String = MessageDigest.getInstance("SHA-256").digest(e.encode()).joinToString("") { "%02x".format(it) }

    private fun handle(envelope: Envelope) {
        when (val r = EventSealing.open(envelope, ring)) {
            is OpenResult.Opened -> {
                log.append(r.event)
                opened++
            }
            is OpenResult.NeedKey -> waiting += envelope
            is OpenResult.Rejected -> unreadable++
        }
    }

    private fun sync() {
        val load = secrets.loadKeyring(groupId)
        ring = load.keyring
        unreadableKeyEpochs = load.unreadableEpochs
        val stored = repo.envelopes(groupId)
        damaged = stored.damaged
        val retry = waiting.toList()
        waiting.clear()
        retry.forEach(::handle)
        for (e in stored.envelopes) if (seen.add(fingerprint(e))) handle(e)
    }

    private fun compute(): GroupView {
        val s = replay(log)
        val b = balances(s)
        return GroupView(
            state = s,
            me = me.publicKey,
            balances = b,
            plan = settlementPlan(b),
            counts = Counts(opened, waiting.map { it.epoch }.toSortedSet(), unreadable, damaged),
            unreadableKeyEpochs = unreadableKeyEpochs,
        )
    }

    private fun publish() {
        state.value = compute()
        onChange()
    }
}
