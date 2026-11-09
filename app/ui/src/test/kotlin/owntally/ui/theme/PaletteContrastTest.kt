package owntally.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M10, T10.3 and T10.4: every pair of text (or icon, or outline) and background the app draws keeps its contrast in both themes (WCAG 2.1 AA: 4.5:1
 * for text, 3:1 for the edges of controls). The pairs are what the screens really put together: text on the page, on cards, on chips, on the
 * information and warning bands, on the green and mint fills and on the hero cards.
 */
class PaletteContrastTest {
    private fun channel(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    private fun luminance(color: Color): Double = color.toArgb().let { 0.2126 * channel((it shr 16) and 0xFF) + 0.7152 * channel((it shr 8) and 0xFF) + 0.0722 * channel(it and 0xFF) }
    private fun contrast(a: Color, b: Color): Double = luminance(a).let { la -> luminance(b).let { lb -> (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05) } }

    private class Pair(val name: String, val text: (OwnTallyPalette) -> Color, val background: (OwnTallyPalette) -> Color, val minimum: Double)

    private val pairs = listOf(
        Pair("ink on page", { it.ink }, { it.background }, 4.5), Pair("ink on card", { it.ink }, { it.card }, 4.5), Pair("ink on chip", { it.ink }, { it.chip }, 4.5),
        Pair("ink on information band", { it.ink }, { it.infoBackground }, 4.5),
        Pair("secondary text on page", { it.textSecondary }, { it.background }, 4.5), Pair("secondary text on card", { it.textSecondary }, { it.card }, 4.5),
        Pair("secondary text on chip", { it.textSecondary }, { it.chip }, 4.5), Pair("secondary text on information band", { it.textSecondary }, { it.infoBackground }, 4.5),
        Pair("hint on card", { it.textHint }, { it.card }, 4.5), Pair("hint on page", { it.textHint }, { it.background }, 4.5),
        Pair("accent on card", { it.accent }, { it.card }, 4.5), Pair("accent on page", { it.accent }, { it.background }, 4.5),
        Pair("accent on chip", { it.accent }, { it.chip }, 4.5), Pair("accent on information band", { it.accent }, { it.infoBackground }, 4.5),
        Pair("accent on warning band", { it.accent }, { it.warningBackground }, 4.5),
        Pair("red on card", { it.red }, { it.card }, 4.5), Pair("red on page", { it.red }, { it.background }, 4.5),
        Pair("warning text on warning band", { it.warningText }, { it.warningBackground }, 4.5),
        Pair("white on green fill", { it.onDark }, { it.green }, 4.5),
        Pair("white on hero card", { it.onDark }, { it.hero }, 4.5), Pair("muted white on hero card", { it.onDarkMuted }, { it.hero }, 4.5),
        Pair("mint on hero card", { it.mint }, { it.hero }, 4.5), Pair("peach on hero card", { it.peachOnDark }, { it.hero }, 4.5),
        Pair("text on mint fill", { it.onMint }, { it.mint }, 4.5),
        Pair("green fill against the page (a button's edge)", { it.green }, { it.background }, 3.0), Pair("green fill against a card", { it.green }, { it.card }, 3.0),
        Pair("control outline on card", { it.border }, { it.card }, 3.0), Pair("control outline on page", { it.border }, { it.background }, 3.0),
    )

    /** What is still below its minimum in the light theme: T10.4 removes these one by one and then this set must be empty. */
    private val lightKnownShortfalls = setOf("hint on card", "hint on page", "control outline on card", "control outline on page")

    private fun failures(p: OwnTallyPalette) = pairs.filter { contrast(it.text(p), it.background(p)) < it.minimum }.map { it.name }.toSet()

    @Test fun theDarkThemeKeepsEveryContrast() {
        assertEquals(emptySet(), failures(DarkPalette))
    }

    @Test fun theLightThemeFailsOnlyWhereTheAccessibilityPassHasNotYetReached() {
        assertEquals(lightKnownShortfalls, failures(LightPalette))
    }

    @Test fun theTextOnTheGroupIconsReadsOnEveryPastelInBothThemes() {
        for (p in listOf(LightPalette, DarkPalette)) for (pastel in p.groupPastels) assertTrue(contrast(p.onPastel, pastel) >= 4.5, "$pastel")
    }

    @Test fun everyColourRoleDiffersBetweenTheThemesWhereItMustAndTheLightOnesAreUnchanged() {
        assertEquals(0xFFF3F5F1.toInt(), LightPalette.background.toArgb(), "the mockups' page colour")
        assertEquals(0xFF0E6B5C.toInt(), LightPalette.green.toArgb())
        assertEquals(0xFF10231F.toInt(), LightPalette.ink.toArgb())
        assertTrue(LightPalette.dark.not() && DarkPalette.dark)
        for ((role, pair) in listOf("background" to (LightPalette.background to DarkPalette.background), "card" to (LightPalette.card to DarkPalette.card), "ink" to (LightPalette.ink to DarkPalette.ink), "accent" to (LightPalette.accent to DarkPalette.accent)))
            assertTrue(pair.first != pair.second, "$role has a dark value")
        assertTrue(luminance(DarkPalette.background) < 0.05 && luminance(LightPalette.background) > 0.8, "dark is dark and light is light")
    }
}
