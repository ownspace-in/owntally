package owntally.model

/**
 * Who may do what in a group (owner decisions of 3 to 5 Nov 2026), in one place. The screens read this table to decide what to
 * offer; `PermissionsTest` runs every action by every role through replay and checks the table tells the truth. A person who is
 * not a current member ([role] null) may do nothing.
 */
object Permissions {
    /** The role of a current member, or null for someone who is not in the group (never joined, removed or left). */
    fun roleOf(state: GroupState, member: PublicKey): Role? = state.members[member]?.takeIf { !it.removed }?.role

    /** Approve a join request and invite people (owner and admins). */
    fun approveJoins(role: Role?) = role == Role.OWNER || role == Role.ADMIN

    /** Remove another member (the owner only). */
    fun removeOthers(role: Role?) = role == Role.OWNER

    /** Change the group key (the owner only). */
    fun changeKey(role: Role?) = role == Role.OWNER

    /** Make or unmake admins (the owner only). */
    fun changeAdmins(role: Role?) = role == Role.OWNER

    /** Offer ownership to another member (the owner only). */
    fun offerOwnership(role: Role?) = role == Role.OWNER

    /** Leave the group: a plain member only (an admin asks the owner; the owner hands over ownership first). */
    fun leave(role: Role?) = role == Role.MEMBER

    /** Delete an expense someone else created (the owner only; anyone may delete their own). */
    fun deleteOthersExpenses(role: Role?) = role == Role.OWNER
}
