package owntally.app

import android.content.Intent
import owntally.data.session.InviteLinks

/** Reads the intent that started or reached the app: the invite link it carries, if it is one (M9). The checks themselves are [InviteLinks]'s. */
object InviteIntents {
    /** True for an intent that asks to view some address: whatever it carries, the app must either use it as an invite link or say it cannot. */
    fun isViewLink(intent: Intent?): Boolean = intent?.action == Intent.ACTION_VIEW && intent.data != null

    /** The invite link text, or null if this intent does not carry a good one (another host, no code, ...). */
    fun linkFrom(intent: Intent?): String? {
        if (!isViewLink(intent)) return null
        val u = intent!!.data!!
        return InviteLinks.fromParts(u.scheme, u.host, u.port, u.userInfo, u.path, u.encodedFragment)
    }
}
