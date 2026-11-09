package owntally.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import owntally.crypto.Clock
import owntally.data.repo.GroupRepository
import owntally.data.repo.IdentityRepository
import owntally.data.secrets.SecretStore
import owntally.data.store.SettingsStore
import owntally.model.GroupId
import owntally.model.Money

/** A group this person has left: hidden from every screen, its data kept until the leave has reached another member (D4) or they delete it (S6). */
data class LeftGroup(val id: GroupId, val name: String, val leaveCounter: Long)

/** The local choice of icon colour for a group (not shared with other members). */
object GroupLooks {
    const val PALETTE_SIZE = 5

    /** The colour used until one is chosen: always the same for a given group. */
    fun defaultFor(groupId: GroupId): Int = (groupId.bytes()[0].toInt() and 0xFF) % PALETTE_SIZE
}

/** One line of the Groups list. A group whose events cannot be opened (no key on this phone) is still listed, with [readable] false. */
data class GroupSummary(
    val groupId: GroupId,
    val name: String,
    val colourIndex: Int,
    /** Current members, the person themself included. */
    val memberCount: Int,
    /** Expenses that have not been deleted. */
    val expenseCount: Int,
    /** Positive: the group owes this person. Negative: they owe the group. */
    val myBalance: Money,
    val readable: Boolean,
    /** Someone offered this person the ownership of the group and they have not answered yet (shown as a tag on the card). */
    val ownershipOfferForMe: Boolean = false,
    /** The owner removed this person from the group (S2): the card says so and the group no longer counts in the totals. */
    val youWereRemoved: Boolean = false,
    /** Payments marked as paid to this person that they have not confirmed or refused (M8): the card says so. */
    val paymentsWaitingForMe: Int = 0,
    /** Payments this person marked as paid and the other person has not answered. */
    val paymentsIMarked: Int = 0,
    /** Payments of the plan that involve this person (to make or to receive). */
    val paymentsToSettle: Int = 0,
)

/**
 * All the groups on this phone, each as a [GroupSession], and a [StateFlow] of one summary per group that updates whenever any
 * of them changes. Sessions are made when first needed and kept.
 */
class Sessions(
    private val groups: GroupRepository,
    private val secrets: SecretStore,
    private val identity: IdentityRepository,
    private val clock: Clock,
    private val settings: SettingsStore,
) {
    private val sessions = LinkedHashMap<GroupId, GroupSession>()
    private val mutableSummaries = MutableStateFlow<List<GroupSummary>>(emptyList())
    private var batching = false

    /** Empty until [refresh] is called, which reads the database: call it off the main thread (the constructor does no I/O). */
    val summaries: StateFlow<List<GroupSummary>> get() = mutableSummaries

    private val mutableLeft = MutableStateFlow<List<LeftGroup>>(emptyList())

    /** The groups this person has left and that are still kept (see [LeftGroup]); shown under Settings. */
    val left: StateFlow<List<LeftGroup>> get() = mutableLeft

    private fun colourKey(id: GroupId) = "group_colour_${id.toHex()}"

    /** The groups this person has left and that have not yet been deleted. */
    @Synchronized
    fun leftGroups(): List<LeftGroup> = groups.listGroups().filter { settings.get(leftKey(it.groupId)) != null }.mapNotNull { row ->
        val session = sessions[row.groupId] ?: return@mapNotNull null
        val view = session.view.value
        val counter = view.state.removals[view.me]?.event?.counter ?: return@mapNotNull null
        LeftGroup(row.groupId, view.state.name.orEmpty(), counter)
    }

    /** Deletes a group this person has left, events and keys included, without waiting for the leave to be delivered ("Delete now"). Does nothing for any other group. */
    @Synchronized
    fun deleteLeft(id: GroupId) {
        if (settings.get(leftKey(id)) != null) deleteGroup(id)
    }

    private fun leftKey(id: GroupId) = "group_left_${id.toHex()}"

    /** Hides a group this person has left. Its stored events stay; only the list skips it. */
    @Synchronized
    fun markLeft(id: GroupId) {
        settings.put(leftKey(id), "1")
        refresh()
    }

    /** This person leaves the group. If the rules accept it, the group disappears from the list; otherwise nothing changes. */
    @Synchronized
    fun leave(id: GroupId): AddResult {
        val result = session(id).leave()
        if (result is AddResult.Added) markLeft(id)
        return result
    }

    fun colourOf(id: GroupId): Int = settings.get(colourKey(id))?.toIntOrNull()?.takeIf { it in 0 until GroupLooks.PALETTE_SIZE } ?: GroupLooks.defaultFor(id)

    /** Looks at the stored groups again (for example after one was added elsewhere) and updates the summaries. */
    @Synchronized
    fun refresh() {
        batching = true
        try {
            val rows = groups.listGroups()
            sessions.keys.retainAll(rows.map { it.groupId }.toSet())
            for (row in rows) {
                if (row.groupId !in sessions) {
                    sessions[row.groupId] = try {
                        GroupSession(row.groupId, groups, secrets, identity, clock) { recompute() }
                    } catch (e: IllegalStateException) {
                        continue // no usable seed: the group is listed as unreadable below
                    }
                }
            }
        } finally {
            batching = false
        }
        recompute()
    }

    /** The session of a group, made (and its events opened) the first time. */
    @Synchronized
    fun session(id: GroupId): GroupSession {
        if (id !in sessions) refresh()
        return checkNotNull(sessions[id]) { "no such group, or no usable seed" }
    }

    /**
     * A new group owned by this person. [colourIndex] (null for the default) must be in the palette. Throws
     * [IllegalArgumentException] for a bad name, currency or colour, in which case nothing is created.
     */
    @Synchronized
    fun createGroup(name: String, currency: String, colourIndex: Int?): GroupId {
        require(colourIndex == null || colourIndex in 0 until GroupLooks.PALETTE_SIZE) { "colour is not in the palette" }
        val id = groups.createGroup(name, currency)
        if (colourIndex != null) settings.put(colourKey(id), colourIndex.toString())
        refresh()
        return id
    }

    @Synchronized
    fun deleteGroup(id: GroupId) {
        groups.deleteGroup(id)
        settings.remove(colourKey(id))
        settings.remove(leftKey(id))
        sessions.remove(id)
        refresh()
    }

    @Synchronized
    private fun recompute() {
        if (batching) return
        mutableLeft.value = leftGroups()
        mutableSummaries.value = groups.listGroups().filter { settings.get(leftKey(it.groupId)) == null }.map { row ->
            val session = sessions[row.groupId]
            val view = session?.view?.value
            val state = view?.state
            GroupSummary(
                groupId = row.groupId,
                name = state?.name.orEmpty(),
                colourIndex = colourOf(row.groupId),
                memberCount = state?.currentMembers?.size ?: 0,
                expenseCount = state?.expenses?.values?.count { !it.deleted } ?: 0,
                myBalance = view?.balances?.get(view.me) ?: Money.ZERO,
                readable = state?.groupId != null,
                ownershipOfferForMe = view != null && state?.pendingOffer == view.me,
                youWereRemoved = view?.youWereRemoved == true,
                paymentsWaitingForMe = view?.let { v -> v.overview.waiting.count { it.to == v.me } } ?: 0,
                paymentsIMarked = view?.let { v -> v.overview.waiting.count { it.from == v.me } } ?: 0,
                paymentsToSettle = view?.let { v -> v.overview.toMake.count { it.from == v.me || it.to == v.me } } ?: 0,
            )
        }
    }
}
