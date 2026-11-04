package owntally.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import owntally.model.GroupId

class NavStackTest {
    private val g1 = GroupId(ByteArray(16) { 1 })
    private val g2 = GroupId(ByteArray(16) { 2 })

    @Test fun startsAtHomeWithNowhereToGoBack() {
        val n = NavStack()
        assertEquals(Screen.Home, n.current)
        assertFalse(n.canGoBack)
    }

    @Test fun pushThenBackReturnsToWhereYouWere() {
        val n = NavStack().push(Screen.Group(g1)).push(Screen.AddExpense(g1))
        assertEquals(Screen.AddExpense(g1), n.current)
        assertTrue(n.canGoBack)
        val back = n.pop()
        assertEquals(Screen.Group(g1), back.current)
        assertEquals(Screen.Home, back.pop().current)
    }

    @Test fun backAtHomeStaysAtHome() {
        val n = NavStack()
        assertEquals(n, n.pop())
        assertEquals(Screen.Home, n.pop().pop().current)
    }

    @Test fun aDoubleTapDoesNotPushTheSameScreenTwice() {
        val n = NavStack().push(Screen.Group(g1)).push(Screen.Group(g1))
        assertEquals(2, n.screens.size)
        assertEquals(Screen.Home, n.pop().current)
    }

    @Test fun differentGroupsAreDifferentScreens() {
        val n = NavStack().push(Screen.Group(g1)).push(Screen.Group(g2))
        assertEquals(3, n.screens.size)
        assertEquals(Screen.Group(g1), n.pop().current)
    }

    @Test fun theStackIsNotChangedByPushOrPop() {
        val n = NavStack().push(Screen.HowItWorks)
        val before = n.screens
        n.push(Screen.Settings); n.pop()
        assertSame(before, n.screens)
        assertEquals(Screen.HowItWorks, n.current)
    }

    @Test fun backToHomeFromAnywhere() {
        val n = NavStack().push(Screen.Group(g1)).push(Screen.SettleUp(g1)).home()
        assertEquals(listOf<Screen>(Screen.Home), n.screens)
    }

    @Test fun leavingAGroupScreenWhoseGroupIsGoneGoesBackToItsParent() {
        val n = NavStack().push(Screen.Group(g1)).push(Screen.SettleUp(g1))
        assertEquals(Screen.Home, n.without(g1).current)
        assertEquals(listOf<Screen>(Screen.Home), n.without(g1).screens)
        assertEquals(n.screens, n.without(g2).screens)
    }
}
