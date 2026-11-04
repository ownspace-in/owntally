package owntally.model

private const val MAX_MEMBERS = 20

/** Applies the log in its deterministic order and the rules of PROTOCOL.md section 4. */
fun replay(log: EventLog): GroupState {
    val order = log.applicationOrder()
    val held = log.heldBack().map { it.id }.distinct()
    // A removal cancels events by that member newer than what the remover had seen, even ones that sort before the
    // removal (but not the removal itself, which a member writing their own leave always is). Find them by replaying again with those events forced out, until nothing new turns up. The forced set
    // only grows, so this ends.
    val forced = LinkedHashSet<EventId>()
    while (true) {
        val run = Run(forced)
        order.forEach(run::apply)
        val late = run.state(held).removals.values.flatMap { r ->
            order.map { it.id }.filter { it.author == r.member && it.counter > r.lastSeenCounter && it != r.event && it in run.applied && it !in forced }
        }
        if (late.isEmpty()) return run.state(held)
        forced += late
    }
}

private class Run(private val forced: Set<EventId>) {
    var groupId: GroupId? = null
    var name: String? = null
    var currency: String? = null
    val members = LinkedHashMap<PublicKey, Member>()
    var owner: PublicKey? = null
    var pendingOffer: PublicKey? = null
    var epoch = 0L
    val epochStarts = LinkedHashMap<Long, EventId>()
    val expenses = LinkedHashMap<EventId, Expense>()
    val settlements = LinkedHashMap<EventId, Settlement>()
    val removals = LinkedHashMap<PublicKey, Removal>()
    val ignored = ArrayList<IgnoredEvent>()
    val applied = HashSet<EventId>()

    fun state(held: List<EventId>) =
        GroupState(groupId, name, currency, members.toMap(), owner, pendingOffer, epoch, epochStarts.toMap(), expenses.toMap(), settlements.toMap(), removals.toMap(), ignored.toList(), held)

    /**
     * Names are unique in a group, removed members included (their names stay reserved); capitals and extra spaces do not count
     * (see [NameKey]). A member may change the capitals of their own name, so [except] is left out. Owner decision, 4 Nov 2026.
     */
    private fun nameTaken(name: String, except: PublicKey?): Boolean {
        val key = NameKey.of(name)
        return members.values.any { it.key != except && it.name.isNotEmpty() && NameKey.of(it.name) == key }
    }

    private fun currentKeys() = members.filterValues { !it.removed }.keys

    private fun current(key: PublicKey) = members[key]?.takeIf { !it.removed }
    private fun roleOf(key: PublicKey) = current(key)?.role

    fun apply(o: Ordered) {
        val reason = check(o)
        if (reason == null) applied += o.id else ignored += IgnoredEvent(o.id, reason)
    }

    /** Applies the event if the rules allow it and returns null; otherwise returns why not and changes nothing. */
    private fun check(o: Ordered): IgnoreReason? {
        val event = o.event
        val author = event.author
        if (o.duplicateId) return IgnoreReason.DUPLICATE_ID
        if (o.id in forced) return IgnoreReason.AFTER_REMOVAL
        val body = event.body
        if (event.version > 1 || body is EventBody.Unknown) return IgnoreReason.UNKNOWN_TYPE
        if (body is EventBody.GroupCreated) return createGroup(event, body)
        if (author in removals) return IgnoreReason.AFTER_REMOVAL
        val role = roleOf(author) ?: return IgnoreReason.NOT_MEMBER

        return when (body) {
            is EventBody.GroupCreated, is EventBody.Unknown -> null // handled above
            is EventBody.MemberJoined -> {
                if (!role.isAdmin) return IgnoreReason.NOT_ALLOWED
                val existing = members[body.member]
                if (existing != null) return if (existing.removed) IgnoreReason.NOT_ALLOWED else IgnoreReason.ALREADY_DONE
                if (members.values.count { !it.removed } >= MAX_MEMBERS) return IgnoreReason.NOT_ALLOWED
                if (nameTaken(body.name, except = null)) return IgnoreReason.INVALID
                members[body.member] = Member(body.member, body.name, body.phone, Role.MEMBER, removed = false, encryptionKey = body.encryptionKey)
                null
            }
            is EventBody.ProfileUpdated -> {
                if (nameTaken(body.name, except = author)) return IgnoreReason.INVALID
                members[author] = members.getValue(author).copy(name = body.name, phone = body.phone)
                null
            }
            is EventBody.ExpenseAdded -> {
                if (current(body.paidBy) == null || body.split.participantKeys.any { current(it) == null }) return IgnoreReason.INVALID
                expenses[event.id] = Expense(event.id, author, body.description, body.total, body.paidBy, body.day, body.split, deleted = false, deletedBy = null)
                null
            }
            is EventBody.ExpenseDeleted -> {
                val expense = expenses[body.expense] ?: return IgnoreReason.UNKNOWN_TARGET
                if (expense.deleted) return IgnoreReason.ALREADY_DONE
                // Owner decision, 4 Nov 2026: the creator or the group owner, not admins.
                if (author != expense.creator && role != Role.OWNER) return IgnoreReason.NOT_ALLOWED
                expenses[expense.id] = expense.copy(deleted = true, deletedBy = author)
                null
            }
            is EventBody.SettlementRecorded -> {
                if (author != body.from && author != body.to) return IgnoreReason.NOT_ALLOWED
                if (current(body.from) == null || current(body.to) == null) return IgnoreReason.INVALID
                val status = if (author == body.from) SettlementStatus.PAID_MARKED else SettlementStatus.CONFIRMED
                settlements[event.id] = Settlement(event.id, body.from, body.to, body.amount, status, null)
                null
            }
            is EventBody.SettlementConfirmed -> {
                val s = settlements[body.settlement] ?: return IgnoreReason.UNKNOWN_TARGET
                if (author != s.to) return IgnoreReason.NOT_ALLOWED
                if (s.status == SettlementStatus.CONFIRMED) return IgnoreReason.ALREADY_DONE
                settlements[s.id] = s.copy(status = SettlementStatus.CONFIRMED, disputeReason = null)
                null
            }
            is EventBody.PaymentDisputed -> {
                val s = settlements[body.settlement] ?: return IgnoreReason.UNKNOWN_TARGET
                if (author != s.to) return IgnoreReason.NOT_ALLOWED
                if (s.status != SettlementStatus.PAID_MARKED) return IgnoreReason.ALREADY_DONE
                settlements[s.id] = s.copy(status = SettlementStatus.DISPUTED, disputeReason = body.reason)
                null
            }
            is EventBody.AdminAdded -> {
                if (role != Role.OWNER) return IgnoreReason.NOT_ALLOWED
                val target = current(body.member) ?: return IgnoreReason.UNKNOWN_TARGET
                if (target.role != Role.MEMBER) return IgnoreReason.ALREADY_DONE
                members[target.key] = target.copy(role = Role.ADMIN)
                null
            }
            is EventBody.AdminRemoved -> {
                if (role != Role.OWNER) return IgnoreReason.NOT_ALLOWED
                val target = current(body.member) ?: return IgnoreReason.UNKNOWN_TARGET
                when (target.role) {
                    Role.OWNER -> return IgnoreReason.NOT_ALLOWED
                    Role.MEMBER -> return IgnoreReason.ALREADY_DONE
                    Role.ADMIN -> members[target.key] = target.copy(role = Role.MEMBER)
                }
                null
            }
            is EventBody.OwnershipOffered -> {
                if (role != Role.OWNER) return IgnoreReason.NOT_ALLOWED
                if (body.to == author || current(body.to) == null) return IgnoreReason.INVALID
                pendingOffer = body.to
                null
            }
            is EventBody.OwnershipOfferCancelled -> {
                if (role != Role.OWNER) return IgnoreReason.NOT_ALLOWED
                if (pendingOffer == null) return IgnoreReason.ALREADY_DONE
                pendingOffer = null
                null
            }
            is EventBody.OwnershipAccepted -> {
                if (pendingOffer != author) return IgnoreReason.NOT_ALLOWED
                val previous = owner!!
                members[previous] = members.getValue(previous).copy(role = Role.ADMIN)
                members[author] = members.getValue(author).copy(role = Role.OWNER)
                owner = author
                pendingOffer = null
                null
            }
            is EventBody.KeyRotated -> {
                // Owner decision, 4 Nov 2026: only the owner changes the key (admins approve joins, which do not change it).
                if (role != Role.OWNER) return IgnoreReason.NOT_ALLOWED
                if (body.newEpoch != epoch + 1 || body.sealedKeys.keys != currentKeys()) return IgnoreReason.INVALID
                epoch = body.newEpoch
                epochStarts[epoch] = event.id
                null
            }
            is EventBody.MemberRemoved -> {
                val target = current(body.member) ?: return IgnoreReason.UNKNOWN_TARGET
                if (target.role == Role.OWNER) return IgnoreReason.NOT_ALLOWED
                // Owner decision, 4 Nov 2026: only the owner removes anyone. A plain member may leave; an admin may not (the owner
                // makes them a plain member first, or removes them).
                val allowed = when {
                    author == target.key -> role == Role.MEMBER
                    else -> role == Role.OWNER
                }
                if (!allowed) return IgnoreReason.NOT_ALLOWED
                // A leave keeps the epoch and carries no keys; a removal by someone else starts the next epoch and seals
                // the new key to exactly the members who stay.
                if (author == target.key) {
                    if (body.newEpoch != epoch || body.sealedKeys.isNotEmpty()) return IgnoreReason.INVALID
                } else {
                    if (body.newEpoch != epoch + 1 || body.sealedKeys.keys != currentKeys() - target.key) return IgnoreReason.INVALID
                    epoch = body.newEpoch
                    epochStarts[epoch] = event.id
                }
                members[target.key] = target.copy(removed = true)
                removals[target.key] = Removal(target.key, author, event.id, event.after[target.key] ?: 0L)
                if (pendingOffer == target.key) pendingOffer = null
                null
            }
        }
    }

    private fun createGroup(event: Event, body: EventBody.GroupCreated): IgnoreReason? {
        if (event.counter != 1L || event.after.isNotEmpty()) return IgnoreReason.INVALID
        // The group id must have been made for this author (GroupIds), so only the real creator can create the group: a member
        // holding the group key cannot write a rival creating event and take over (owner decision, 4 Nov 2026).
        if (!GroupIds.isCreatedBy(body.groupId, event.author)) return IgnoreReason.INVALID
        if (owner != null) return IgnoreReason.ALREADY_DONE
        groupId = body.groupId
        name = body.name
        currency = body.currency
        owner = event.author
        epoch = 1
        members[event.author] = Member(event.author, "", "", Role.OWNER, removed = false, encryptionKey = body.encryptionKey)
        return null
    }
}
