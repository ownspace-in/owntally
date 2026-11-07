package owntally.data.session

import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import owntally.crypto.Clock
import owntally.crypto.EventSealing
import owntally.crypto.GroupKey
import owntally.crypto.GroupKeyring
import owntally.crypto.KeyRotation
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
import owntally.model.keyChangeNeeded
import owntally.model.replay
import owntally.model.settlementPlan

/**
 * What could and could not be read. [waitingForKey] are epochs whose key is not on this phone yet; their envelopes are kept and
 * opened when the key arrives. [waitingForMember] are envelopes with a good signature from an author who is not a known member (yet): kept and
 * tried again when more members are known. [unreadable] envelopes had the right epoch but did not open with the key held. [damaged] stored
 * entries could not even be decoded. None of these are shown as events; all stay stored.
 */
data class Counts(val opened: Int, val waitingForKey: Set<Long>, val unreadable: Int, val damaged: Int, val waitingForMember: Int = 0)

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
    /** Late entries of removed or departed members that the rules cancelled, in event order (M4, D5b). */
    val cancelled: List<owntally.model.CancelledEntry> = emptyList(),
)

sealed class AddResult {
    data class Added(val id: EventId) : AddResult()

    /** The core's rules would ignore this event, so it was not written. */
    data class Refused(val reason: IgnoreReason) : AddResult()

    /** There is no key for the group's current epoch on this phone, so nothing new can be sealed. */
    data object NoKey : AddResult()

    /** The event could not even be built, for example a blank name or a phone number with letters. [problem] says what is wrong. */
    data class Invalid(val problem: String) : AddResult()
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

    private var log = EventLog()
    private val seen = HashSet<String>()
    private val waiting = ArrayList<Envelope>()
    private val deferred = ArrayList<Envelope>()
    private var ring = GroupKeyring(groupId)
    private var unreadableKeyEpochs = emptySet<Long>()
    private var opened = 0
    private val rejected = ArrayList<Envelope>()
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
        if (leaveNotCoveredByTheKey()) rotateKey()
    }

    /**
     * Owner only (D2, and S7 in M5): the key in use no longer fits the group (someone left whom the key change had not seen, or a member
     * was admitted at the same time as it), so the key has to change. Both are covered by the new key change, so this cannot repeat.
     */
    private fun leaveNotCoveredByTheKey(): Boolean {
        val st = replay(log)
        if (st.groupId == null || st.owner != me.publicKey) return false
        return keyChangeNeeded(log, st)
    }

    /** The counter the next event written by this phone will get. The Add expense screen needs it to show the rounding exactly. */
    @Synchronized
    fun nextCounter(): Long {
        sync()
        return (log.summary()[me.publicKey] ?: 0L) + 1
    }

    fun addExpense(description: String, total: Money, paidBy: PublicKey, day: Long, split: Split): AddResult =
        append(EventBody.ExpenseAdded(description, total, paidBy, day, split))

    fun deleteExpense(expense: EventId): AddResult = append(EventBody.ExpenseDeleted(expense))

    // ---- membership (M4) -------------------------------------------------------------------------------------------

    /** Admits a person who asked to join. The owner or an admin may; the name must be free in this group. */
    fun approveJoin(member: PublicKey, encryptionKey: owntally.model.EncryptionKey, name: String, phone: String): AddResult =
        build { EventBody.MemberJoined(member, encryptionKey, name, phone) }

    /** Changes this person's own name and phone number in the group. */
    fun updateProfile(name: String, phone: String): AddResult = build { EventBody.ProfileUpdated(name, phone) }

    fun makeAdmin(member: PublicKey): AddResult = append(EventBody.AdminAdded(member))
    fun removeAdmin(member: PublicKey): AddResult = append(EventBody.AdminRemoved(member))
    fun offerOwnership(to: PublicKey): AddResult = append(EventBody.OwnershipOffered(to))
    fun cancelOffer(): AddResult = append(EventBody.OwnershipOfferCancelled)
    fun acceptOwnership(): AddResult = append(EventBody.OwnershipAccepted)
    fun declineOwnership(): AddResult = append(EventBody.OwnershipDeclined)

    /** This person leaves. No new key is made here: the owner's phone does that (docs/tasks/M4-membership.md, D2). */
    @Synchronized
    fun leave(): AddResult {
        sync()
        return append(EventBody.MemberRemoved(me.publicKey, replay(log).epoch, emptyMap()))
    }

    /** Owner only: removes [member] and changes the group key so that they cannot read anything written from now on. */
    fun removeMember(member: PublicKey): AddResult = rotating(setOf(member)) { epoch, sealed -> EventBody.MemberRemoved(member, epoch, sealed) }

    /** Owner only: changes the group key for the people who are in the group now. */
    fun rotateKey(): AddResult = rotating(emptySet()) { epoch, sealed -> EventBody.KeyRotated(epoch, sealed) }

    private fun build(make: () -> EventBody): AddResult {
        val body = try {
            make()
        } catch (e: IllegalArgumentException) {
            return AddResult.Invalid(e.message ?: "invalid")
        }
        return append(body)
    }

    /**
     * Makes the next key, saves it on this phone BEFORE the event that announces it is written (a crash in between must never leave an
     * event that nobody can open), and forgets it again if the rules refuse the event.
     */
    @Synchronized
    private fun rotating(excluding: Set<PublicKey>, body: (Long, Map<PublicKey, owntally.model.SealedKey>) -> EventBody): AddResult {
        sync()
        val now = replay(log)
        if (now.groupId == null || ring[now.epoch] == null) {
            publish()
            return AddResult.NoKey
        }
        val epoch = now.epoch + 1
        val fresh = GroupKey.generate()
        val sealed = KeyRotation.seal(me.publicKey, groupId, epoch, fresh, KeyRotation.recipients(now, excluding))
        secrets.saveGroupKey(groupId, epoch, fresh)
        val result = try {
            append(body(epoch, sealed))
        } catch (e: Throwable) {
            secrets.deleteGroupKey(groupId, epoch)
            throw e
        }
        if (result !is AddResult.Added) secrets.deleteGroupKey(groupId, epoch)
        refresh()
        return result
    }

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

    /** The members to check authors against, removed ones included (their earlier events stay valid); null until the group's creation is in the log. */
    private fun knownAuthors(): Collection<PublicKey>? = replay(log).takeIf { it.groupId != null }?.members?.keys

    /**
     * Opens what can be opened. An envelope whose author is not a known member is kept and tried again after the others have been read
     * (the admission may be in the same batch), until a pass opens nothing new.
     */
    private fun process(first: List<Envelope>) {
        var queue = first
        while (queue.isNotEmpty()) {
            val members = knownAuthors()
            val next = ArrayList<Envelope>()
            var progress = false
            for (e in queue) {
                when (val r = EventSealing.open(e, ring, members)) {
                    is OpenResult.Opened -> {
                        log.append(r.event)
                        opened++
                        progress = true
                    }
                    is OpenResult.NeedKey -> waiting += e
                    OpenResult.UnknownAuthor -> next += e
                    is OpenResult.Rejected -> rejected += e
                }
            }
            if (!progress) {
                deferred += next
                return
            }
            queue = next
        }
    }

    private fun sync() {
        val load = secrets.loadKeyring(groupId)
        ring = load.keyring
        unreadableKeyEpochs = load.unreadableEpochs
        val stored = repo.envelopes(groupId)
        damaged = stored.damaged
        // Only the two smallest versions of an id are read (S1). A newer arrival can push out one that was already taken in: start the
        // log again from what is stored now, so the result is the same as if everything had arrived at once.
        val current = stored.envelopes.map { fingerprint(it) }.toHashSet()
        if (seen.any { it !in current }) {
            log = EventLog()
            seen.clear()
            waiting.clear()
            deferred.clear()
            rejected.clear()
            opened = 0
        }
        val pending = ArrayList<Envelope>()
        pending += waiting
        waiting.clear()
        pending += deferred
        deferred.clear()
        for (e in stored.envelopes) if (seen.add(fingerprint(e))) pending += e
        process(pending)
        adoptKeys()
    }

    /**
     * For every epoch from 2 on, the key must be the one sealed in the event the rules accepted as its start. A key already stored for
     * that epoch (for example one left behind by an event that was refused) is replaced if it differs. Envelopes that were waiting for
     * a key are then tried again, until nothing changes.
     */
    private fun adoptKeys() {
        while (true) {
            val st = replay(log)
            var changed = false
            for ((epoch, id) in st.epochStarts) {
                if (epoch < 2) continue
                val event = log.allEvents().firstOrNull { it.id == id } ?: continue
                val temp = GroupKeyring(groupId)
                if (!KeyRotation.receive(st, event, me, temp)) continue
                val real = temp[epoch] ?: continue
                val held = ring[epoch]
                if (held == null || !held.bytes().contentEquals(real.bytes())) {
                    secrets.saveGroupKey(groupId, epoch, real)
                    ring.add(epoch, real)
                    changed = true
                }
            }
            if (!changed || (waiting.isEmpty() && rejected.isEmpty())) return
            // A key was replaced: what failed to open with the old one, and what was waiting, may open now.
            val again = ArrayList(waiting)
            again += rejected
            waiting.clear()
            rejected.clear()
            process(again)
        }
    }

    private fun compute(): GroupView {
        val s = replay(log)
        val b = balances(s)
        return GroupView(
            state = s,
            me = me.publicKey,
            balances = b,
            plan = settlementPlan(b),
            counts = Counts(opened, waiting.map { it.epoch }.toSortedSet(), rejected.size, damaged, deferred.size),
            unreadableKeyEpochs = unreadableKeyEpochs,
            cancelled = owntally.model.cancelledEntries(log, s),
        )
    }

    private fun publish() {
        state.value = compute()
        onChange()
    }
}
