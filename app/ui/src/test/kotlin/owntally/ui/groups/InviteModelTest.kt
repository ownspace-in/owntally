package owntally.ui.groups

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.crypto.GroupKey
import owntally.crypto.Ticket
import owntally.model.GroupId
import owntally.model.PublicKey

class InviteModelTest {
    private val now = 1_800_000_000L
    private val link = Ticket(ByteArray(16) { 1 }, PublicKey(ByteArray(32) { 2 }), GroupId(ByteArray(16) { 3 }), 1, GroupKey(ByteArray(32) { 4 }), now + 27_720, "")
        .let { "https://joinowntally.ownspace.in/j#" + it.encode() }

    private fun open(expiresIn: Long = 27_720, revoked: Boolean = false, text: String? = link) = InviteModels.build("Goa Trip", text, now + expiresIn, revoked, now)

    // ---- the words ----------------------------------------------------------------------------------------------------------------

    @Test fun anOpenInviteShowsTheQrTheLinkAndHowLongItWorks() {
        val m = open()
        assertEquals("Goa Trip", m.groupName)
        assertEquals("Works once. Expires in 7 hrs 42 min.", m.notice)
        assertEquals(link, m.link)
        assertNotNull(m.qr)
        assertEquals("For friends in the same room", m.qrTitle)
        assertEquals("They scan this with their phone's camera", m.qrHint)
        assertTrue(m.canShare && m.canCopy && m.canRevoke)
        assertEquals("Anyone with this link can ask to join. Send it only in private chats. You still approve each request.", m.warning)
    }

    @Test fun theShareMessageNamesTheGroupAndHoldsTheLinkAndOnlyExistsForAnOpenInvite() {
        assertEquals("Join \"Goa Trip\" on OwnTally. This link works once and expires, so open it soon: $link", open().shareText)
        assertNull(open(revoked = true).shareText)
        assertNull(open(0).shareText)
    }

    @Test fun theTimeLeftIsInPlainWords() {
        fun left(sec: Long) = open(sec).notice
        assertEquals("Works once. Expires in 8 hrs.", left(8 * 3600))
        assertEquals("Works once. Expires in 1 hr.", left(3600))
        assertEquals("Works once. Expires in 1 hr 1 min.", left(3600 + 60))
        assertEquals("Works once. Expires in 59 min.", left(59 * 60 + 59))
        assertEquals("Works once. Expires in 1 min.", left(60))
        assertEquals("Works once. Expires in less than a minute.", left(30))
        assertEquals("Works once. Expires in 2 hrs 5 min.", left(2 * 3600 + 5 * 60 + 40))
    }

    @Test fun anExpiredInviteShowsNoQrNoLinkAndASaneMessage() {
        for (m in listOf(open(0), open(-5))) {
            assertEquals("This invite has expired. Make a new one.", m.notice)
            assertNull(m.qr); assertNull(m.link)
            assertFalse(m.canShare || m.canCopy || m.canRevoke)
        }
    }

    @Test fun aRevokedInviteShowsNoQrNoLinkAndSaysNobodyCanUseIt() {
        val m = open(revoked = true)
        assertEquals("Invite revoked. Nobody can use it any more.", m.notice)
        assertNull(m.qr); assertNull(m.link)
        assertFalse(m.canShare || m.canCopy || m.canRevoke)
    }

    @Test fun noInviteYetOffersOnlyToMakeOne() {
        val m = InviteModels.build("Goa Trip", null, 0, false, now)
        assertEquals("No invite yet.", m.notice)
        assertNull(m.qr); assertNull(m.link)
        assertFalse(m.canShare || m.canCopy || m.canRevoke)
        assertEquals("New invite", m.newLabel)
    }

    // ---- the QR code ----------------------------------------------------------------------------------------------------------------

    private fun decode(qr: QrMatrix): String {
        val quiet = 4; val scale = 8; val side = (qr.size + 2 * quiet) * scale
        val pixels = IntArray(side * side) { 0xFFFFFF }
        for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr[x, y]) {
            for (dy in 0 until scale) for (dx in 0 until scale) pixels[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0x000000
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test fun theQrCodeIsReadBackByAnIndependentReaderAsTheExactLink() {
        assertEquals(link, decode(QrEncoder.encode(link)))
        assertEquals(link, decode(open().qr!!))
    }

    /** The decoder alone, with no camera-style search for the code: is every bit of the encoding (format, mask, error correction, data) right? */
    private fun decodeModules(qr: QrMatrix): String {
        val bits = com.google.zxing.common.BitMatrix(qr.size)
        for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr[x, y]) bits.set(x, y)
        return com.google.zxing.qrcode.decoder.Decoder().decode(bits).text
    }

    @Test fun theCodeIsNotMirroredAndUsesMediumErrorCorrection() {
        val qr = QrEncoder.encode(link)
        val bits = com.google.zxing.common.BitMatrix(qr.size)
        for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr[x, y]) bits.set(x, y)
        val result = com.google.zxing.qrcode.decoder.Decoder().decode(bits)
        assertFalse((result.other as? com.google.zxing.qrcode.decoder.QRCodeDecoderMetaData)?.isMirrored == true, "a mirrored code is not read by every scanner")
        assertEquals("M", result.ecLevel)
    }

    @Test fun manyDifferentLinksAllDecodeToTheExactText() {
        val rnd = kotlin.random.Random(7)
        repeat(200) {
            val t = Ticket(rnd.nextBytes(16), PublicKey(rnd.nextBytes(32)), GroupId(rnd.nextBytes(16)), 1L + rnd.nextInt(5), GroupKey(rnd.nextBytes(32)), now + rnd.nextInt(100000), "")
            val text = "https://joinowntally.ownspace.in/j#" + t.encode()
            val qr = QrEncoder.encode(text)
            assertEquals(text, decodeModules(qr))
        }
    }

    @Test fun theMatrixIsSquareAndHasTheThreeFinderCorners() {
        val qr = QrEncoder.encode(link)
        assertTrue(qr.size >= 21 && (qr.size - 17) % 4 == 0, "a valid QR size: ${qr.size}")
        fun finder(ox: Int, oy: Int) = (0..6).all { i -> qr[ox + i, oy] && qr[ox + i, oy + 6] && qr[ox, oy + i] && qr[ox + 6, oy + i] } && !qr[ox + 1, oy + 1] && qr[ox + 3, oy + 3]
        assertTrue(finder(0, 0)); assertTrue(finder(qr.size - 7, 0)); assertTrue(finder(0, qr.size - 7))
    }

    @Test fun textTooLongForAQrCodeIsAnErrorNotACrash() {
        assertFailsWithMessage { QrEncoder.encode("x".repeat(5000)) }
    }

    private fun assertFailsWithMessage(block: () -> Unit) {
        val r = runCatching(block)
        assertTrue(r.isFailure && r.exceptionOrNull() is IllegalArgumentException, "${r.exceptionOrNull()}")
    }
}
