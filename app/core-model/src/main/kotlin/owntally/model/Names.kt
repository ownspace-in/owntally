package owntally.model

/**
 * Whether [name] is already taken in the group (owner decision, 4 Nov 2026): capitals, extra spaces and invisible characters do not
 * count (see [NameKey]), removed and departed members' names stay taken, and a member's own name is not taken against themselves
 * ([except]), so changing only its capitals is allowed. Replay enforces exactly this rule on `MemberJoined` and `ProfileUpdated`;
 * the screens call this same function, so a "name already taken" message can never disagree with it.
 */
fun isNameTaken(state: GroupState, name: String, except: PublicKey? = null): Boolean = isNameTaken(state.members.values, name, except)

internal fun isNameTaken(members: Collection<Member>, name: String, except: PublicKey?): Boolean {
    val key = NameKey.of(name)
    return members.any { it.key != except && it.name.isNotEmpty() && NameKey.of(it.name) == key }
}
