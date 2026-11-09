package owntally.data.session

/**
 * Which links open the app (M9, T9.3, choice 1): `https://joinowntally.ownspace.in/j#<code>` and nothing else. A link that comes from outside the
 * app is checked here before anything reads it: the scheme, the host, no port and no user name, the path, and a code of a sane length.
 * The code is passed on as written; reading it is [owntally.crypto.Ticket.fromShared]'s job.
 */
object InviteLinks {
    const val HOST = "joinowntally.ownspace.in"
    const val PATH = "/j"
    const val MAX_CODE_LENGTH = 2_000

    /** The link text to hand to the join flow, or null if this is not an invite link. [port] is -1 when the link names none. */
    fun fromParts(scheme: String?, host: String?, port: Int, userInfo: String?, path: String?, fragment: String?): String? {
        if (scheme == null || !scheme.equals("https", ignoreCase = true)) return null
        if (host == null || !host.equals(HOST, ignoreCase = true)) return null
        if (port != -1 || userInfo != null) return null
        if (path != PATH) return null
        val code = fragment ?: return null
        if (code.isBlank() || code.length > MAX_CODE_LENGTH || '#' in code) return null
        return Invites.LINK_START + code
    }
}
