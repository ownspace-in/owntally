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
            2, true, MembersModels.roleLines, listOf(RemovedRowModel("Dev", "+91 95000 11111", "Left")),
        )
        rule.setContent { OwnTallyTheme { Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) { MembersScreen(owner, onBack = {}, onInvite = {}, onMenu = {}) } } }
        rule.onNodeWithText("Join requests").assertIsDisplayed()
        save("members-owner")
    }
}
