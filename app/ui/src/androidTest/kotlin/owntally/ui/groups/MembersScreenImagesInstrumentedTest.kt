package owntally.ui.groups

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import owntally.model.PublicKey
import owntally.model.Role
import owntally.ui.theme.OwnTallyColors
import owntally.ui.theme.OwnTallyTheme

/** Draws the Members screen with the people of design/12 and saves a picture to compare with it (pulled from the phone, see docs/tasks/M4-membership.md). */
@RunWith(AndroidJUnit4::class)
class MembersScreenImagesInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun key(n: Int) = PublicKey(ByteArray(32).also { it[0] = n.toByte() })
    private fun row(n: Int, label: String, phone: String, role: Role, me: Boolean = false, menu: Boolean = true) = MemberRowModel(
        key(n), label, phone, label.take(1), n % MembersModels.COLOURS, role,
        when (role) { Role.OWNER -> "Owner"; Role.ADMIN -> "Admin"; else -> null }, me, hasMenu = menu && !me,
    )

    private fun save(name: String) {
        owntally.ui.A11yChecks.assertAccessible(rule, name)
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun saveTheMembersScreens() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val owner = MembersScreenModel(
            "Goa Trip", "Goa Trip · 5 people",
            listOf(
                row(0, "You", "+91 98765 00001", Role.OWNER, me = true), row(1, "Rohan", "+91 98765 43210", Role.ADMIN),
                row(2, "Meera", "+91 99887 76655", Role.MEMBER), row(3, "Kabir", "+91 97000 12345", Role.MEMBER), row(4, "Isha", "+91 96000 55443", Role.MEMBER),
            ),
            2, true, MembersModels.roleLines, listOf(RemovedRowModel("Dev", "+91 95000 11111", "Left", key(9))),
        )
        rule.setContent { OwnTallyTheme { Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) { MembersScreen(owner, onBack = {}, onInvite = {}, onMenu = {}) } } }
        rule.onNodeWithText("Join requests").assertIsDisplayed()
        save("members-owner")
    }

    @Test
    fun saveTheMemberActionsScreen() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val model = MemberActionsModel(
            "Rohan", "R", 1, "+91 98765 43210 · Admin",
            listOf(
                MemberAction(ActionKind.MAKE_OWNER, "Make owner", "You become an admin. Rohan must accept."),
                MemberAction(ActionKind.REMOVE_ADMIN, "Remove admin role", "Rohan stays in the group as a member."),
                MemberAction(ActionKind.REMOVE, "Remove from group", "See how balances change first.", destructive = true),
            ),
        )
        rule.setContent { OwnTallyTheme { Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) { MemberActionsScreen(model, onClose = {}, onAction = {}) } } }
        rule.onNodeWithText("Make owner").assertIsDisplayed()
        save("member-actions")
    }

    @Test
    fun saveTheRemoveMemberScreen() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        fun r(n: Int, label: String, now: String, nk: owntally.model.format.BalanceKind, after: String, ak: owntally.model.format.BalanceKind?) = RemoveRow(key(n), label, now, nk, after, ak)
        val owed = owntally.model.format.BalanceKind.OWED
        val owes = owntally.model.format.BalanceKind.OWES
        val zero = owntally.model.format.BalanceKind.SETTLED
        val model = RemoveMemberModel(
            "Remove Kabir?",
            listOf(
                "Kabir will no longer see or add expenses. The group will switch to a new key so they can't read new entries.",
                "The group is recalculated as if Kabir had never joined. Kabir's entries stay in the history but don't count.",
            ),
            listOf(
                r(0, "You", "+₹3,200", owed, "+₹2,740", owed), r(1, "Rohan", "-₹1,850", owes, "-₹1,520", owes), r(2, "Meera", "-₹1,350", owes, "-₹1,220", owes),
                r(3, "Isha", "₹0", zero, "₹0", zero), r(4, "Kabir", "₹0", zero, "Removed", null),
            ),
            LeftOutBox("12 expenses Kabir paid, Kabir's share of 9 others", listOf("Kabir paid You ₹900. It no longer counts.")),
            "Remove Kabir",
        )
        rule.setContent { OwnTallyTheme { Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) { RemoveMemberScreen(model, onCancel = {}, onConfirm = {}) } } }
        rule.onNodeWithText("Remove Kabir?").assertIsDisplayed()
        save("remove-member")
    }

    @Test
    fun saveTheOwnershipScreens() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        fun model(stage: OfferStage) = when (stage) {
            OfferStage.CONFIRM -> OwnerOfferModel(
                "Rohan", "R", 1, "+91 98765 43210 · Admin", stage, "Make Rohan the owner?",
                listOf(
                    "Rohan will be able to remove members, change the group key, and add and remove admins. You will become an admin and keep every expense and balance.",
                    "Nothing changes until Rohan accepts, which they can do the next time their phone syncs.",
                ),
                null, "Send request", null,
            )
            OfferStage.WAITING -> OwnerOfferModel(
                "Rohan", "R", 1, "+91 98765 43210 · Admin", stage, "Waiting for Rohan to accept",
                listOf("You stay the owner until they accept. If they don't, you can cancel this request at any time."), null, "Cancel request", null,
            )
            OfferStage.DECLINED -> OwnerOfferModel(
                "Rohan", "R", 1, "+91 98765 43210 · Admin", stage, "Rohan declined",
                listOf("You are still the owner. You can ask Rohan again or choose someone else."), null, "Ask Rohan again", "Choose someone else",
            )
        }
        val stage = androidx.compose.runtime.mutableStateOf(OfferStage.CONFIRM)
        rule.setContent { OwnTallyTheme { Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) { OwnerOfferScreen(model(stage.value), onClose = {}, onPrimary = {}, onSecondary = {}) } } }
        rule.onNodeWithText("Make Rohan the owner?").assertIsDisplayed()
        save("ownership-confirm")
        stage.value = OfferStage.WAITING
        rule.onNodeWithText("Waiting for Rohan to accept").assertIsDisplayed()
        save("ownership-waiting")
        stage.value = OfferStage.DECLINED
        rule.onNodeWithText("Rohan declined").assertIsDisplayed()
        save("ownership-declined")
    }

    @Test
    fun saveTheOwnershipBanner() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val banner = OwnershipBannerModel("Asha wants to make you the owner of Goa Trip", "Become the owner of Goa Trip?", "x")
        rule.setContent { OwnTallyTheme { Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) { OwnershipBanner(banner, {}, {}) } } }
        rule.onNodeWithText("Accept").assertIsDisplayed()
        rule.onNodeWithText("Decline").assertIsDisplayed()
        save("ownership-banner")
    }

    @Test
    fun saveTheJoinRequestsScreen() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val model = JoinRequestsScreenModel(
            "Goa Trip", "Waiting for you · 2",
            listOf(
                JoinRequestRow(key(2), "Aarav", "+91 91234 56780", "A", 2, "12 min ago"),
                JoinRequestRow(key(3), "Naina", "+91 90000 11122", "N", 3, "1 hr ago"),
            ),
            null, false, "Approving lets the person read every expense in this group. Decline anyone you don't recognise.",
        )
        rule.setContent { OwnTallyTheme { Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) { JoinRequestsScreen(model, {}, {}, {}) } } }
        rule.onNodeWithText("Aarav").assertIsDisplayed()
        save("join-requests")
    }

    @Test
    fun saveTheSyncScreen() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        fun line(n: Int, name: String, detail: String, badge: String, tone: BadgeTone, explains: Boolean = false) = SyncMemberLine(key(n), name, name.take(1), n % MembersModels.COLOURS, detail, badge, tone, explains)
        val model = SyncScreenModel(
            "Goa Trip", SyncHeadline.WAITING, "Waiting for a friend to come online. 3 entries are saved on this phone and will be sent.",
            listOf(
                line(1, "Rohan", "2 hrs ago", "Direct", BadgeTone.GOOD), line(2, "Meera", "2 hrs ago", "Via relay", BadgeTone.GOOD),
                line(3, "Kabir", "3 entries not shared · 3 days ago · trying again in 30 min", "Offline", BadgeTone.OFFLINE, explains = true),
                line(5, "Dev", "Never synced · the relay can't be reached from this phone · trying again in 2 min", "Needs relay", BadgeTone.WARN, explains = true),
                line(4, "Isha", "Not reachable yet", "Not yet", BadgeTone.NEUTRAL),
            ),
            "Some entries from Isha were rejected.",
            listOf("2 entries from Isha were rejected: bad signature · 3 hrs ago"),
        )
        rule.setContent { OwnTallyTheme { Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) { SyncScreen(model, {}, {}, {}) } } }
        rule.onNodeWithText("Sync now").assertIsDisplayed()
        save("sync-screen")
    }
}

