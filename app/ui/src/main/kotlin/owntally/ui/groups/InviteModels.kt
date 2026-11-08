package owntally.ui.groups

import io.nayuki.qrcodegen.DataTooLongException
import io.nayuki.qrcodegen.QrCode

/** A QR code as dark and light squares. [size] squares each way, without the quiet border (the screen draws that). */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && dark[y * size + x]
}

/** QR codes by Nayuki's generator (MIT, `io.nayuki.qrcodegen`). Medium error correction: a screen is read at an angle and with glare. */
object QrEncoder {
    /** Throws [IllegalArgumentException] if the text does not fit in a QR code. */
    fun encode(text: String): QrMatrix {
        val qr = try {
            QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        } catch (e: DataTooLongException) {
            throw IllegalArgumentException("too much text for a QR code", e)
        }
        return QrMatrix(qr.size, BooleanArray(qr.size * qr.size) { qr.getModule(it % qr.size, it / qr.size) })
    }
}

/** The invite screen (design/10) as words and flags. [qr] and [link] are null when there is nothing to scan or share. */
class InviteModel(
    val groupName: String,
    val qr: QrMatrix?,
    val link: String?,
    val notice: String,
    val canShare: Boolean,
    val canCopy: Boolean,
    val canRevoke: Boolean,
) {
    val qrTitle = "For friends in the same room"
    val qrHint = "They scan this with OwnTally"
    val warning = "Anyone with this link can ask to join. Send it only in private chats. You still approve each request."
    val newLabel = "New invite"

    /** The message put in the share sheet with the link. */
    val shareText: String? get() = link?.let { "Join \"$groupName\" on OwnTally. This link works once and expires, so open it soon: $it" }
}

object InviteModels {
    /** [link] is null when the group has no open invite yet; [revoked] is true after "Revoke invite". */
    fun build(groupName: String, link: String?, expiresAtSeconds: Long, revoked: Boolean, nowSeconds: Long): InviteModel = when {
        link == null -> InviteModel(groupName, null, null, "No invite yet.", false, false, false)
        revoked -> InviteModel(groupName, null, null, "Invite revoked. Nobody can use it any more.", false, false, false)
        nowSeconds >= expiresAtSeconds -> InviteModel(groupName, null, null, "This invite has expired. Make a new one.", false, false, false)
        else -> InviteModel(groupName, QrEncoder.encode(link), link, "Works once. Expires in ${timeLeft(expiresAtSeconds - nowSeconds)}.", true, true, true)
    }

    internal fun timeLeft(seconds: Long): String {
        val minutes = seconds / 60
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 -> "$h ${if (h == 1L) "hr" else "hrs"}" + if (m > 0) " $m min" else ""
            m > 0 -> "$m min"
            else -> "less than a minute"
        }
    }
}
