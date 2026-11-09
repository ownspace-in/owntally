package owntally.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M10, T10.3: every colour a screen draws comes from the palette of the theme in use, so a screen cannot stay light in the dark theme. The only fixed
 * colours are white (on the green fills, the spinner, and behind the QR code, which is always dark on white) and transparent.
 */
class NoHardCodedColoursTest {
    private val sources = File("src/main/kotlin/owntally/ui").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Theme.kt" }.toList()

    @Test fun noScreenWritesAColourValueOrBlackOrALightSchemeOfItsOwn() {
        val offenders = sources.flatMap { f -> f.readLines().withIndex().filter { Regex("""Color\(0x|Color\.Black|lightColorScheme|darkColorScheme|Color\.(Red|Green|Blue|Gray|DarkGray|LightGray|Yellow|Cyan|Magenta)\b""").containsMatchIn(it.value) && !it.value.trim().startsWith("//") }.map { "${f.name}:${it.index + 1}" } }
        assertEquals(emptyList(), offenders)
    }

    @Test fun whiteIsUsedOnlyWhereItIsRightInBothThemes() {
        val allowed = setOf("Components.kt", "GroupsScreens.kt", "AddExpenseScreen.kt", "InviteScreen.kt")
        val offenders = sources.filter { it.name !in allowed }.flatMap { f -> f.readLines().withIndex().filter { Regex("""Color\.White""").containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" } }
        assertEquals(emptyList(), offenders)
    }

    @Test fun thePaletteReadersAreTheOnlyWayToTheColours() {
        // The old constant-style access cannot come back: OwnTallyColors has no plain values.
        val theme = File("src/main/kotlin/owntally/ui/theme/Theme.kt").readText()
        val objectBody = theme.substringAfter("object OwnTallyColors {").substringBefore("\n}\n")
        val plain = objectBody.lines().filter { it.trim().startsWith("val ") && "@Composable" !in it }
        assertEquals(emptyList(), plain)
    }
}
