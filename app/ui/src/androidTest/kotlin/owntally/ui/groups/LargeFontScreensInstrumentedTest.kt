package owntally.ui.groups

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import owntally.data.session.PeerState
import owntally.data.session.PeerStatus
import owntally.model.DisputeReason
import owntally.model.EventId
import owntally.model.Money
import owntally.model.PublicKey
import owntally.sync.RelayResult
import owntally.ui.A11yChecks
import owntally.ui.theme.OwnTallyColors
import owntally.ui.theme.OwnTallyTheme

/**
 * M10, T10.4: screens at the largest font size Android offers (200%), in the theme the phone is in. At that size nothing may stick out of the screen
 * sideways, every button must be on the screen or be reachable by scrolling (a screen that cannot scroll hides what does not fit), and each screen
 * is saved as a picture. The functional tests of the other screens are written for the normal size.
 */
@RunWith(AndroidJUnit4::class)
class LargeFontScreensInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun key(n: Int) = PublicKey(ByteArray(32).also { it[0] = n.toByte() })

    private fun show(name: String, scale: Float = 2.0f, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = scale)) {
                OwnTallyTheme { Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) { content() } }
            }
        }
        A11yChecks.assertAccessible(rule, "$name at ${(scale * 100).toInt()}% text", reachableByScrolling = true)
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "large-$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun settleUpWithEveryKindOfRow() = show("settle-up") {
        val me = key(1); val rohan = key(2); val meera = key(3)
        val owed = PaymentRow(rohan, me, Money(185_000), "₹1,850", "Rohan → You", "+91 98765 43210", PaymentRole.TO_ME, "Rohan", listOf(PaymentAction.SEND_REMINDER, PaymentAction.MARK_RECEIVED))
        val owe = PaymentRow(me, meera, Money(135_000), "₹1,350", "You → Meera", "+91 99887 76655", PaymentRole.FROM_ME, "Meera", listOf(PaymentAction.COPY_NUMBER, PaymentAction.CALL, PaymentAction.I_PAID))
        val waiting = WaitingRow(EventId(me, 1), meera, me, Money(100_000), "₹1,000", "Meera → You", PaymentRole.TO_ME, "Meera marked ₹1,000 as paid. Waiting for you to confirm.", true, "Meera")
        SettleUpScreen(SettleUpModel("Goa Trip · whole group", listOf(owed, owe), "Kabir and Isha need no payments.", "Goa Trip", listOf(waiting)), onBack = {})
    }

    @Test fun paymentNotReceivedForm() = show("not-received") {
        NotReceivedScreen(
            NotReceivedModel.Form(
                "Meera → You", "₹1,350", "Marked as paid 2 hrs ago", "Meera",
                listOf(NotReceivedReason(DisputeReason.NOTHING_RECEIVED, "I haven't received anything"), NotReceivedReason(DisputeReason.DIFFERENT_AMOUNT, "The amount is different"), NotReceivedReason(DisputeReason.NOT_CHECKED, "I haven't checked my account yet")),
                "Meera will see your answer and the payment goes back to \"Payments to make\". Nothing is deleted, and either of you can change it later.", "Tell Meera",
            ),
            onBack = {}, onTell = {},
        )
    }

    @Test fun paymentNotReceivedAnswered() = show("not-received-sent") {
        NotReceivedScreen(
            NotReceivedModel.Sent("Meera → You", "₹1,350", "Marked as paid 2 hrs ago", "Meera has been told", "Meera will see this the next time Meera's phone syncs with yours. The payment shows as \"Not received\" until you both agree.", "Tip: send Meera a reminder message so Meera knows to check, or mark it received once the money arrives.", "If the money arrives later, tap Confirm received on Settle up.", "Back to settle up"),
            onBack = {}, onTell = {},
        )
    }

    @Test fun cantReachAMember() = show("cant-reach") {
        CantReachScreen(CantReachModels.build("Meera", PeerStatus(PeerState.UNREACHABLE, attempts = 2, failures = 2, lastAttemptSeconds = 100, nextTrySeconds = 1_900, notShared = 3, relay = RelayResult.PEER_NOT_THERE), 1_000), onBack = {}, onTryNow = {}, onNearby = {})
    }

    @Test fun memberActions() = show("member-actions") {
        MemberActionsScreen(
            MemberActionsModel("Rohan", "R", 1, "+91 98765 43210 · Admin", listOf(
                MemberAction(ActionKind.MAKE_OWNER, "Make owner", "You become an admin. Rohan must accept."),
                MemberAction(ActionKind.REMOVE_ADMIN, "Remove admin role", "Rohan stays in the group as a member."),
                MemberAction(ActionKind.REMOVE, "Remove from group", "Rohan loses access to the group's future entries.", destructive = true),
            )),
            onClose = {}, onAction = {},
        )
    }

    @Test fun ownershipRequestToConfirm() = show("owner-offer") {
        OwnerOfferScreen(
            OwnerOfferModel("Rohan", "R", 1, "+91 98765 43210 · Admin", OfferStage.CONFIRM, "Make Rohan the owner?", listOf("Rohan will be asked first and becomes the owner only if they accept.", "Until then you stay the owner."), "This replaces your waiting request to Meera.", "Send request", null),
            onClose = {}, onPrimary = {}, onSecondary = {},
        )
    }
}
