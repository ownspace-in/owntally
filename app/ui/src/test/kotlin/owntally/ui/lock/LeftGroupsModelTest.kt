package owntally.ui.lock

import kotlin.test.Test
import kotlin.test.assertEquals
import owntally.data.session.LeftGroup
import owntally.model.GroupId

class LeftGroupsModelTest {
    private val a = GroupId(ByteArray(16) { 1 })
    private val b = GroupId(ByteArray(16) { 2 })

    @Test fun eachGroupThePersonLeftIsListedByName() {
        val rows = LeftGroupsModels.rows(listOf(LeftGroup(a, "Goa Trip", 3), LeftGroup(b, "", 1)))
        assertEquals(listOf("Goa Trip", "A group you left"), rows.map { it.name }, "a group whose name cannot be read still has a line")
        assertEquals(listOf(a, b), rows.map { it.id })
    }

    @Test fun nothingLeftMeansNoSection() {
        assertEquals(emptyList(), LeftGroupsModels.rows(emptyList()))
    }

    @Test fun theWarningSaysWhatDeletingNowMeansForTheOthers() {
        assertEquals(
            "Nobody has seen that you left Goa Trip. If you delete it now, the others will still count you as a member.",
            LeftGroupsModels.warning("Goa Trip"),
        )
    }

    @Test fun theExplanationSaysWhyTheGroupIsStillHere() {
        assertEquals(
            "You left these groups. They are hidden, and kept on this phone until one of the other members has received that you left; then they are deleted from this phone.",
            LeftGroupsModels.EXPLANATION,
        )
    }
}
