package owntally.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The "name already taken" check the screens use must be the very rule replay enforces (M4, T4.2). */
class NamesTest {
    private val a = key(1)
    private val b = key(2)
    private val c = key(3)

    @Test fun aNameOfACurrentMemberIsTakenWhateverTheCapitalsAndSpaces() {
        val s = standardGroup() // Asha, Ben, Chitra, Dev
        val state = s.state()
        for (name in listOf("Ben", "ben", "BEN", " Ben ", "b e n".replace(" ", ""), "Asha", "chitra", "DEV")) assertTrue(isNameTaken(state, name), name)
        assertFalse(isNameTaken(state, "Ben V"))
        assertFalse(isNameTaken(state, "Bennett"))
    }

    @Test fun aRemovedOrDepartedMembersNameStaysTaken() {
        val s = standardGroup()
        s.remove(a, c)
        s.remove(b, b)
        val state = s.state()
        assertTrue(isNameTaken(state, "Chitra"), "removed")
        assertTrue(isNameTaken(state, "ben"), "left")
    }

    @Test fun aMembersOwnNameIsNotTakenAgainstThemselves() {
        val state = standardGroup().state()
        assertFalse(isNameTaken(state, "Ben", except = b))
        assertFalse(isNameTaken(state, "BEN", except = b), "changing only the capitals of your own name is fine")
        assertTrue(isNameTaken(state, "Chitra", except = b), "someone else's name")
    }

    @Test fun anEmptyOwnerNameBeforeTheProfileArrivesTakesNothing() {
        val s = Sim()
        s.emit(a, EventBody.GroupCreated(GROUP, "G", "INR", encKeyFor(a)))
        assertFalse(isNameTaken(s.state(), ""))
        assertFalse(isNameTaken(s.state(), "Asha"))
    }

    @Test fun itAgreesWithWhatReplayDoes() {
        // For many names, joining is refused exactly when the screens' check says the name is taken.
        val names = listOf("Ben", "ben", " BEN", "Asha", "Chitra S", "Dev", "dev ", "Eve", "EVE", "Eve  Two", "eve two", "Ben V")
        for (name in names) {
            val s = standardGroup()
            val taken = isNameTaken(s.state(), name)
            val join = s.join(a, key(40), name)
            assertEquals(taken, join.reason(s.state()) == IgnoreReason.INVALID, "\"$name\"")
        }
    }

    @Test fun renamingFollowsTheSameRule() {
        for (name in listOf("chitra", "Chitra", "Ben", "BEN", "Chitra S", "Dev")) {
            val s = standardGroup()
            val taken = isNameTaken(s.state(), name, except = b)
            val rename = s.emit(b, EventBody.ProfileUpdated(name, PHONE))
            assertEquals(taken, rename.reason(s.state()) == IgnoreReason.INVALID, "\"$name\"")
            if (!taken) assertNull(rename.reason(s.state()))
        }
    }
}
