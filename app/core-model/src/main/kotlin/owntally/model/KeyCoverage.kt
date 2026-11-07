package owntally.model

/**
 * Whether the owner's phone must change the group key again (M4 D2, extended by decision S7 in M5). The key in use was sealed by the
 * event that started the current epoch, to the members its author knew. It no longer fits if:
 * - someone who has left or been removed was sealed the key (or, in epoch 1, holds it), so they can read what is written next; or
 * - a current member was admitted at the same time as that event (neither saw the other), so the key was not sealed to them and the
 *   admitter, who had not seen the key change, cannot have given it to them either.
 * A member admitted after the key change was seen is not a gap: the admitter had the key and hands it over with the history.
 * After the new key change both are covered, so asking again is a no-op until something new happens.
 */
fun keyChangeNeeded(log: EventLog, state: GroupState): Boolean {
    if (state.groupId == null) return false
    val events = log.allEvents()
    val byId = events.groupBy { it.id }
    val start = state.epochStarts[state.epoch]?.let { byId[it]?.firstOrNull() }
    // Who the current key was sealed to (null in epoch 1, when everyone who was then a member holds it).
    val sealedTo: Set<PublicKey>? = when (val b = start?.body) {
        is EventBody.KeyRotated -> b.sealedKeys.keys
        is EventBody.MemberRemoved -> b.sealedKeys.keys
        else -> null
    }
    // Someone who has gone but holds the current key.
    for ((who, member) in state.members) if (member.removed && (sealedTo == null || who in sealedTo)) return true
    if (start == null) return false
    val ignored = state.ignored.map { it.id }.toSet()
    for (m in state.currentMembers.keys) {
        val join = events.firstOrNull { it.body is EventBody.MemberJoined && (it.body as EventBody.MemberJoined).member == m && it.id !in ignored } ?: continue
        val startSawJoin = (start.after[join.author] ?: 0L) >= join.counter
        val joinSawStart = (join.after[start.author] ?: 0L) >= start.counter
        if (!startSawJoin && !joinSawStart) return true
    }
    return false
}
