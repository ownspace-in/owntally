package owntally.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import owntally.crypto.Bip39
import owntally.crypto.RecoveryPhrase
import owntally.model.EventId
import owntally.model.GroupId
import owntally.model.PublicKey
import owntally.model.format.BalanceKind
import owntally.model.format.MoneyFormat
import owntally.model.Money
import owntally.ui.groups.CreateGroupModel
import owntally.ui.groups.CreateGroupScreen
import owntally.ui.groups.ExpenseRow
import owntally.ui.groups.GroupCardModel
import owntally.ui.groups.GroupScreen
import owntally.ui.groups.GroupScreenModel
import owntally.ui.groups.GroupsListModel
import owntally.ui.groups.GroupsScreen
import owntally.ui.groups.MemberLine
import owntally.ui.theme.OwnTallyTheme

/**
 * Saves a picture of each first-run screen, drawn edge to edge as in the app, so they can be compared with design/07, 08 and 08b.
 * Reads nothing and checks nothing: the pictures are pulled from the phone (see docs/tasks/M3-storage-app-shell.md).
 */
@RunWith(AndroidJUnit4::class)
class ScreenImagesInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val phrase = Bip39.fromEntropy(ByteArray(16) { it.toByte() })
    private val firstRun = object : FirstRun {
        override fun newPhrase() = phrase
        override fun complete(phrase: RecoveryPhrase, name: String, phone: String) = Unit
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun saveTheFirstRunScreens() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val vm = OnboardingViewModel(firstRun, Random(3), Dispatchers.IO)
        rule.setContent { OwnTallyTheme { OnboardingFlow(vm) { } } }
        save("01-welcome-empty")
        rule.onNodeWithText("Continue").performClick()
        save("02-welcome-errors")
        rule.onNodeWithText("For example, Asha").performTextInput("Asha")
        rule.onNodeWithText("+91 98765 43210").performTextInput("98765 43210")
        save("03-welcome-filled")
        rule.onNodeWithText("Continue").performClick()
        save("04-phrase")
        rule.onNodeWithText("I've written it down").performClick()
        save("05-confirm-empty")
        val pos = vm.state.value.positions
        rule.onNodeWithText("Type word ${pos[0]}").performTextInput(phrase.words()[pos[0] - 1])
        rule.onNodeWithText("Type word ${pos[1]}").performTextInput("qqqq")
        rule.onNodeWithText("Type word ${pos[2]}").performTextInput(phrase.words()[pos[2] - 1])
        rule.onNodeWithText("Finish").performClick()
        save("06-confirm-mismatch")
    }

    @Test
    fun savePhraseAtTheLargestTextSize() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2.0f),
            ) { OwnTallyTheme { PhraseScreen(Bip39.fromEntropy(ByteArray(16) { (it * 7).toByte() })) { } } }
        }
        save("08-phrase-large-text")
    }

    private fun card(name: String, initials: String, colour: Int, sub: String, paise: Long) =
        GroupCardModel(GroupId(ByteArray(16) { (name.length + it).toByte() }), name, initials, colour, sub, MoneyFormat.describe(Money(paise)), true)

    @Test
    fun saveTheGroupsScreens() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        rule.setContent {
            OwnTallyTheme {
                GroupsScreen(
                    GroupsListModel(
                        MoneyFormat.describe(Money(4_820_00)), MoneyFormat.describe(Money(-1_150_00)),
                        listOf(
                            card("Goa Trip", "GT", 0, "5 members · 23 expenses", 3_200_00),
                            card("Flat 4B", "F4", 1, "3 members · 41 expenses", -1_150_00),
                            card("Office Lunches", "OL", 2, "6 members · 12 expenses", 1_620_00),
                            card("Sunday Football", "SF", 3, "8 members · 9 expenses", 0),
                        ),
                    ),
                    onOpen = { }, onCreate = { },
                )
            }
        }
        save("10-groups")
    }

    @Test
    fun saveTheGroupScreen() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val key = PublicKey(ByteArray(32))
        fun row(n: Long, d: String, sub: String, cap: String?, amount: String, kind: BalanceKind, deleted: Boolean = false) =
            ExpenseRow(EventId(key, n), d, sub, cap, amount, kind, deleted)
        rule.setContent {
            OwnTallyTheme {
                GroupScreen(
                    GroupScreenModel(
                        "Goa Trip", "You are the owner", MoneyFormat.describe(Money(3_200_00)), "₹38,460",
                        listOf(
                            MemberLine("Rohan owes", "₹1,850", BalanceKind.OWED), MemberLine("Meera owes", "₹1,350", BalanceKind.OWED),
                            MemberLine("Isha & Kabir", "settled", BalanceKind.SETTLED),
                        ),
                        listOf(
                            row(4, "Beach shack dinner", "You paid ₹4,800 · split 5 ways", "you lent", "₹3,840", BalanceKind.OWED),
                            row(3, "Scooter rental", "Deleted by Rohan · not counted", null, "₹2,500", BalanceKind.SETTLED, deleted = true),
                            row(2, "Villa, 3 nights", "Meera paid ₹21,000 · split 5 ways", "you owe", "₹4,200", BalanceKind.OWES),
                            row(1, "Cab from airport", "You paid ₹1,900 · split 5 ways", "you lent", "₹1,520", BalanceKind.OWED),
                        ),
                        allSettled = false,
                    ),
                    onBack = { },
                )
            }
        }
        save("11-group")
    }

    @Test
    fun saveTheCreateGroupScreen() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        rule.setContent { OwnTallyTheme { CreateGroupScreen(CreateGroupModel("Goa Trip", 1), { }, { }, { }, null) } }
        save("12-create-group")
    }
}
