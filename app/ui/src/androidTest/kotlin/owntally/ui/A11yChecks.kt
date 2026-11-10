package owntally.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals

/**
 * M10, T10.4: what every screen must satisfy for a person using TalkBack or large fingers, checked on the screen as it is drawn: every element that can
 * be tapped has a name (its text or a description) and a role (so TalkBack says "button", "tab" or "radio button", not just "double tap to activate"),
 * and a touch target of at least 48 dp each way (Material's minimum). Called by the screen tests where they take their picture, so every screen is
 * checked in the states the tests put it in.
 */
object A11yChecks {
    fun assertAccessible(rule: ComposeTestRule, screen: String, minimumDp: Int = 48, reachableByScrolling: Boolean = false) {
        rule.waitForIdle()
        val density = rule.density
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val problems = ArrayList<String>()
        for (n in nodes) {
            val label = listOfNotNull(
                n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
                n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
            ).joinToString(" ").trim()
            val what = label.ifEmpty { "(no name) at ${n.boundsInRoot}" }
            if (label.isEmpty()) problems += "no name: $what"
            val editable = n.config.getOrNull(SemanticsProperties.EditableText) != null
            if (!editable && n.config.getOrNull(SemanticsProperties.Role) == null) problems += "no role: $what"
            // The touch bounds include what Compose adds to a small element (it enlarges every tappable one to 48 dp for the pointer); the plain size
            // is used when the element is partly off the screen, where the touch bounds are cut.
            val touch = n.touchBoundsInRoot
            val width = maxOf(touch.width, n.size.width.toFloat()); val height = maxOf(touch.height, n.size.height.toFloat())
            val min = with(density) { minimumDp.dp.toPx() }
            if (width < min - 1 || height < min - 1) problems += "touch target %.0f x %.0f dp, below $minimumDp: %s".format(width / density.density, height / density.density, what)
        }
        // A button that is not on the screen must have a scrolling container around it, or there is no way to reach it (at a large font size).
        if (reachableByScrolling) {
            // A layout that cannot scroll does not push what does not fit off the screen: it squeezes and cuts it, which no bounds check can see. So a
            // screen at a large font size must have a scrolling region (a lazy list or a scrolling column) somewhere in it.
            val scrollers = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes()
            if (scrollers.isEmpty()) problems += "nothing on this screen scrolls, so at a large font size what does not fit is cut off"
            val rootBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
            for (n in nodes) {
                val b = n.boundsInRoot
                val inside = b.top >= rootBounds.top - 1 && b.bottom <= rootBounds.bottom + 1
                var scrolls = false
                var p = n.parent
                while (p != null) { if (p.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null) scrolls = true; p = p.parent }
                if (!inside && !scrolls) problems += "out of reach (below or above the screen, and nothing scrolls): ${n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")}"
            }
        }
        // Nothing may stick out of the screen sideways (what a large font does to a layout that was only tried at the normal size).
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        for (n in rule.onAllNodes(androidx.compose.ui.test.hasText("", substring = true)).fetchSemanticsNodes()) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: continue
            val b = n.boundsInRoot
            if (b.width > 0 && (b.left < root.left - 2 || b.right > root.right + 2)) problems += "text sticks out of the screen sideways: \"${text.take(40)}\" (%.0f to %.0f of %.0f px)".format(b.left, b.right, root.right)
        }
        // `-e a11yReport yes` collects every problem of every screen into a file (for fixing them all in one go) instead of stopping at the first.
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("a11yReport") == "yes") {
            val dir = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
            java.io.File(dir, "a11y-report.txt").appendText(problems.joinToString("") { "$screen: $it\n" })
            return
        }
        assertEquals(emptyList(), problems, "accessibility problems on the screen \"$screen\"")
    }
}
