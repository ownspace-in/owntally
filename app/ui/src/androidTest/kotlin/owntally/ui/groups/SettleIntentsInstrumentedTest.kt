package owntally.ui.groups

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.runner.RunWith

/** The two hand-offs of Settle up, as Android sees them (decisions P1 and P6): built, not launched. */
@RunWith(AndroidJUnit4::class)
class SettleIntentsInstrumentedTest {
    @Test
    fun theReminderIsPlainTextWithTheNameAndTheAmountOnly() {
        val i = SettleIntents.reminder("Rohan", "₹1,850")
        assertEquals(Intent.ACTION_SEND, i.action)
        assertEquals("text/plain", i.type)
        val text = i.getStringExtra(Intent.EXTRA_TEXT)!!
        assertEquals("Hi Rohan, OwnTally says you owe me ₹1,850. Pay however suits you, then mark it as paid in the app.", text)
        assertNull(i.data, "no address and no number: the person picks who gets it")
        assertEquals(setOf(Intent.EXTRA_TEXT), i.extras!!.keySet(), "nothing else travels with it (no subject, no phone number, no group)")
    }

    @Test
    fun theChooserWrapsTheReminderAndAddsNothingToIt() {
        val chooser = SettleIntents.chooser(SettleIntents.reminder("Rohan", "₹5"))
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
    }

    @Test
    fun callOpensTheDialerWithTheNumberAndNeverPlacesTheCall() {
        val i = SettleIntents.dial("+91 98765-43210")
        assertEquals(Intent.ACTION_DIAL, i.action)
        assertEquals("tel:+919876543210", i.data.toString())
        assertFalse(i.action == Intent.ACTION_CALL, "ACTION_CALL would place the call and needs a permission")
    }
}
