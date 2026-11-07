package owntally.ui.lock

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import owntally.model.GroupId
import owntally.ui.theme.OwnTallyTheme

/** The "Groups you left" part of Settings on a real screen: the list, the warning before "Delete now", and what each answer does. */
@RunWith(AndroidJUnit4::class)
class LeftGroupsSectionInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val goa = GroupId(ByteArray(16) { 1 })
    private val flat = GroupId(ByteArray(16) { 2 })

    @Test
    fun theListWarnsBeforeDeletingAndDeletesOnlyWhenConfirmed() {
        val deleted = mutableListOf<GroupId>()
        rule.setContent {
            OwnTallyTheme {
                // In Settings the section sits in a column; without one the rows would be drawn on top of each other.
                androidx.compose.foundation.layout.Column {
                    LeftGroupsSection(listOf(LeftGroupRow(goa, "Goa Trip"), LeftGroupRow(flat, "Flat")), onDelete = { deleted += it })
                }
            }
        }
        rule.onNodeWithText("GROUPS YOU LEFT").assertIsDisplayed()
        rule.onNodeWithText(LeftGroupsModels.EXPLANATION).assertIsDisplayed()
        rule.onNodeWithText("Goa Trip").assertIsDisplayed()
        rule.onNodeWithText("Flat").assertIsDisplayed()
        assertEquals(2, rule.onAllNodesWithText("Delete now").fetchSemanticsNodes().size)

        // Delete now: the warning first, and nothing is deleted yet. Keep it closes it.
        rule.onAllNodesWithText("Delete now")[0].performClick()
        rule.waitForIdle()
        try {
            rule.onNodeWithText(LeftGroupsModels.warning("Goa Trip")).assertExists()
        } catch (e: AssertionError) {
            throw AssertionError("on screen after the tap:\n" + rule.onAllNodes(androidx.compose.ui.test.isRoot()).printToString(4000), e)
        }
        assertEquals(emptyList(), deleted)
        rule.onNodeWithText("Keep it").performClick()
        rule.onNodeWithText(LeftGroupsModels.warning("Goa Trip")).assertDoesNotExist()
        assertEquals(emptyList(), deleted)

        // Confirm on the second one: only that group is deleted.
        rule.onAllNodesWithText("Delete now")[1].performClick()
        rule.onNodeWithText(LeftGroupsModels.warning("Flat")).assertExists()
        rule.onNode(hasText("Delete now") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf(flat), deleted)
    }

    @Test
    fun withNothingLeftNothingIsShown() {
        rule.setContent { OwnTallyTheme { androidx.compose.foundation.layout.Column { LeftGroupsSection(emptyList(), onDelete = {}) } } }
        rule.onNodeWithText("GROUPS YOU LEFT").assertDoesNotExist()
    }
}
