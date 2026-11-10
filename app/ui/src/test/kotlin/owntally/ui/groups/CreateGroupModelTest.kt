package owntally.ui.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.data.session.GroupLooks

class CreateGroupModelTest {
    @Test fun startsEmptyWithTheFirstColourAndRupees() {
        val m = CreateGroupModel()
        assertEquals("", m.name)
        assertEquals(0, m.colourIndex)
        assertEquals("INR", CreateGroupModel.CURRENCY)
        assertFalse(m.canCreate)
    }

    @Test fun nameRules() {
        assertFalse(CreateGroupModel(name = "   ").canCreate)
        assertTrue(CreateGroupModel(name = "Goa Trip").canCreate)
        assertTrue(CreateGroupModel(name = "x".repeat(40)).canCreate)
        assertFalse(CreateGroupModel(name = "x".repeat(41)).canCreate)
        assertEquals("Goa Trip", CreateGroupModel(name = "  Goa Trip ").trimmedName)
    }

    @Test fun nameTooLongSaysSo() {
        assertNull(CreateGroupModel(name = "x".repeat(40)).problem)
        assertNull(CreateGroupModel(name = "").problem) // nothing typed yet is not an error
        assertEquals("Use at most 40 letters", CreateGroupModel(name = "x".repeat(41)).problem)
    }

    @Test fun typingIsCappedAtAFewMoreThanTheLimit() {
        // A paste of a long text is cut, so the field cannot hold megabytes.
        assertEquals(CreateGroupModel.INPUT_LIMIT, CreateGroupModel().withName("x".repeat(10_000)).name.length)
    }

    @Test fun colourChoices() {
        assertEquals(GroupLooks.PALETTE_SIZE, CreateGroupModel.COLOURS)
        assertEquals(3, CreateGroupModel().withColour(3).colourIndex)
        assertEquals(0, CreateGroupModel().withColour(GroupLooks.PALETTE_SIZE).colourIndex) // out of range is ignored
        assertEquals(2, CreateGroupModel(colourIndex = 2).withColour(-1).colourIndex)
    }

    @Test fun aGroupNameWithAHiddenOrDirectionCharacterCannotBeCreatedAndSaysWhy() {
        val m = CreateGroupModel(name = "Goa" + 0x202E.toChar() + "Trip")
        assertFalse(m.canCreate)
        assertEquals(owntally.model.NameText.INVISIBLE, m.problem)
        assertTrue(CreateGroupModel(name = "\u0917\u094B\u0935\u093E \u092F\u093E\u0924\u094D\u0930\u093E").canCreate)
    }
}
