package owntally.ui.groups

import owntally.data.session.GroupView
import owntally.model.Permissions
import owntally.model.PublicKey
import owntally.model.Role

enum class OfferStage { CONFIRM, WAITING, DECLINED }

/** The owner's side of an ownership request (design/14b, 14c), one screen in three stages. */
data class OwnerOfferModel(
    val name: String,
    val initial: String,
    val colourIndex: Int,
    val line: String,
    val stage: OfferStage,
    val title: String,
    val body: List<String>,
    /** "This replaces your waiting request to Meera." when another person's request is still waiting. */
    val replaces: String?,
    /** The main button: Send request, Cancel request, or Ask again. */
    val primary: String,
    /** A second button, only after a decline. */
    val secondary: String?,
)

object OwnerOfferModels {
    /** Null when this phone may not make an offer (not the owner), or the person is the owner or not in the group. */
    fun build(view: GroupView, target: PublicKey): OwnerOfferModel? {
        val state = view.state
        if (target == view.me || !Permissions.offerOwnership(Permissions.roleOf(state, view.me))) return null
        val member = state.currentMembers[target] ?: return null
        val name = member.name.ifBlank { "Someone" }
        val role = when (member.role) { Role.OWNER -> "Owner"; Role.ADMIN -> "Admin"; Role.MEMBER -> "Member" }
        val stage = when {
            state.pendingOffer == target -> OfferStage.WAITING
            state.declinedOffer == target -> OfferStage.DECLINED
            else -> OfferStage.CONFIRM
        }
        val other = state.pendingOffer?.takeIf { it != target }?.let { state.members[it]?.name?.ifBlank { null } ?: "someone" }
        return when (stage) {
            OfferStage.CONFIRM -> OwnerOfferModel(
                name, name.take(1).uppercase(), colour(target), "${member.phone} · $role", stage, "Make $name the owner?",
                listOf(
                    "$name will be able to remove members, change the group key, and add and remove admins. You will become an admin and keep every expense and balance.",
                    "Nothing changes until $name accepts, which they can do the next time their phone syncs.",
                ),
                other?.let { "This replaces your waiting request to $it." }, "Send request", null,
            )
            OfferStage.WAITING -> OwnerOfferModel(
                name, name.take(1).uppercase(), colour(target), "${member.phone} · $role", stage, "Waiting for $name to accept",
                listOf("You stay the owner until they accept. If they don't, you can cancel this request at any time."), null, "Cancel request", null,
            )
            OfferStage.DECLINED -> OwnerOfferModel(
                name, name.take(1).uppercase(), colour(target), "${member.phone} · $role", stage, "$name declined",
                listOf("You are still the owner. You can ask $name again or choose someone else."), null, "Ask $name again", "Choose someone else",
            )
        }
    }

    private fun colour(k: PublicKey) = (k.bytes()[0].toInt() and 0xff) % MembersModels.COLOURS
}

/** The banner on the group screen for the person who was offered ownership (D1), and the confirmation text for Accept. */
data class OwnershipBannerModel(val text: String, val confirmTitle: String, val confirmBody: String)

object OwnershipBanners {
    fun build(view: GroupView): OwnershipBannerModel? {
        val state = view.state
        if (state.pendingOffer != view.me || Permissions.roleOf(state, view.me) == null) return null
        val group = state.name ?: "the group"
        val owner = state.owner?.let { state.members[it]?.name?.ifBlank { null } } ?: "The owner"
        return OwnershipBannerModel(
            "$owner wants to make you the owner of $group",
            "Become the owner of $group?",
            "You will be able to remove members, change the group key, and add and remove admins. $owner will become an admin.",
        )
    }
}
