package owntally.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * M10, T10.8, finding F4 (docs/SECURITY_REVIEW.md; owner decision, 10 Nov 2026): a name may be in any language, but it may hold no invisible
 * character and no character that changes the direction text is shown in, because they let one name look like another or display reversed. The
 * joiner characters (ZWNJ, ZWJ) that Persian, Malayalam, Kannada and other scripts need to spell words stay allowed, but only joined to letters.
 * The rule is for names (the group's and the members'), not for the description of an expense.
 */
class NameTextTest {
    private fun ok(name: String) = assertNull(NameText.problem(name), "should be allowed: ${name.map { "U+%04X".format(it.code) }}")
    private fun bad(name: String) = assertNotNull(NameText.problem(name), "should be refused: ${name.map { "U+%04X".format(it.code) }}")

    @Test fun namesInManyScriptsAreAllowed() {
        for (n in listOf("Asha", "Rohan V", "रमेश", "അവൻ", "ಕನ್ನಡ", "தமிழ்", "অমিত", "مریم", "מיכל", "李雷", "さくら", "Ørjan O'Neil", "José-María", "Trip 2024"))
            ok(n)
    }

    @Test fun directionControlsAreRefused() {
        for (c in listOf('\u200E', '\u200F', '\u061C', '\u202A', '\u202B', '\u202C', '\u202D', '\u202E', '\u2066', '\u2067', '\u2068', '\u2069'))
            bad("Ro${c}han")
    }

    @Test fun invisibleCharactersAreRefused() {
        for (c in listOf('\u200B', '\u2060', '\uFEFF', '\u00AD', '\u2061', '\u2062', '\u2063', '\u2064', '\u180E', '\u0000', '\t', '\n', '\r', '\u007F', '\u0085'))
            bad("Ro${c}han")
        bad("Ro\uDB40\uDC01han") // a tag character (U+E0001)
        bad("Ro\u2028han") // a line separator
        bad("Ro\uD800han") // an unpaired surrogate
    }

    @Test fun theJoinersAreAllowedBetweenLettersAndMarksOnly() {
        ok("می\u200Cخواهم") // Persian: ZWNJ between two letters
        ok("ക\u200Dഷ") // ZWJ between two letters
        ok("ന്\u200D") // Malayalam chillu: ZWJ after the virama (a mark), at the end of the word
        bad("\u200Cمریم") // at the start
        bad("می\u200C\u200Cخواهم") // twice in a row
        bad("Asha\u200C") // at the end, after a letter (it would look like "Asha")
        bad("Asha \u200Dasha") // after a space
        bad("Asha\u200D 7") // before a space
        bad("A1\u200C2") // between digits
    }

    @Test fun anEmptyOrBlankNameIsNotWhatThisRuleChecksButTheLengthRuleStillDoes() {
        assertFailsWith<IllegalArgumentException> { EventBody.ProfileUpdated("  ", "+91 98765 43210") }
        assertFailsWith<IllegalArgumentException> { EventBody.ProfileUpdated("x".repeat(41), "+91 98765 43210") }
    }

    @Test fun theRuleHoldsForTheGroupNameTheJoinedNameAndTheUpdatedName() {
        val phone = "+91 98765 43210"
        assertFailsWith<IllegalArgumentException> { EventBody.GroupCreated(GroupId(ByteArray(16)), "Goa\u202ETrip", "INR", ENC) }
        assertFailsWith<IllegalArgumentException> { EventBody.MemberJoined(key(2), ENC, "Ro\u200Bhan", phone) }
        assertFailsWith<IllegalArgumentException> { EventBody.ProfileUpdated("Ro\u202Ehan", phone) }
        EventBody.ProfileUpdated("می\u200Cخواهم", phone)
    }

    @Test fun anExpenseDescriptionIsNotChecked() {
        EventBody.ExpenseAdded("Dinner\u200B at the beach", Money(10_000), key(1), 20_000, Split.Equal(setOf(key(1))))
    }

    @Test fun twoNamesThatDifferOnlyByAJoinerAreStillTheSameNameForUniqueness() {
        assertEquals(NameKey.of("می\u200Cخواهم"), NameKey.of("میخواهم"))
    }
}
