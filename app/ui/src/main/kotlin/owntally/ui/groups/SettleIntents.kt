package owntally.ui.groups

import android.content.Intent
import android.net.Uri

/**
 * The two hand-offs of Settle up, built apart from the screen so they can be checked (M8, decisions P1 and P6). Neither needs a permission:
 * the dialer opens with the number and places no call, and the share sheet carries only the text, to whichever app the person picks.
 */
object SettleIntents {
    /** The reminder the person owed sends: the text only (no group, no number), as plain text. */
    fun reminder(payerName: String, amount: String): Intent =
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SettleUpModel.reminderText(payerName, amount))

    fun chooser(reminder: Intent): Intent = Intent.createChooser(reminder, "Send reminder")

    /** Opens the dialer with the number filled in; ACTION_DIAL never places the call itself (ACTION_CALL would, and needs a permission). */
    fun dial(phone: String): Intent = Intent(Intent.ACTION_DIAL, Uri.parse(PhoneLinks.telUri(phone)))
}
