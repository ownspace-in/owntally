package owntally.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Every colour of the app for one theme (M10, T10.3). The light values are the ones read from the mockups in design/ and are unchanged; the dark ones
 * are chosen so that every pair of text and background the app uses keeps its contrast (PaletteContrastTest checks them all, in both themes).
 *
 * [green] is the fill of buttons and of the dark hero cards, with [onDark] text on it; [accent] is the same green as text, outline and icon on the
 * page, which has to be lighter on a dark page; [mint] is the second fill, with [onMint] text on it; [hero] is the dark card that carries the big
 * balances, with [onDark] and [onDarkMuted] text, [mint] and [peachOnDark] amounts on it; [onPastel] is the text on the group icons.
 */
@Immutable
class OwnTallyPalette(
    val background: Color, val card: Color, val chip: Color, val cardBorder: Color, val border: Color,
    val ink: Color, val textSecondary: Color, val textHint: Color,
    val green: Color, val accent: Color, val mint: Color, val onMint: Color, val hero: Color,
    val infoBackground: Color, val warningBackground: Color, val warningText: Color, val red: Color,
    val onDark: Color, val onDarkMuted: Color, val peachOnDark: Color, val onPastel: Color,
    val groupPastels: List<Color>,
    val dark: Boolean,
)

private val Pastels = listOf(Color(0xFFBFE8DA), Color(0xFFFFD9CC), Color(0xFFF6E3A6), Color(0xFFD6DDF5), Color(0xFFE7ECE7))

val LightPalette = OwnTallyPalette(
    background = Color(0xFFF3F5F1), card = Color.White, chip = Color(0xFFF3F5F1), cardBorder = Color(0xFFE1E7E2), border = Color(0xFFD5DAD3),
    ink = Color(0xFF10231F), textSecondary = Color(0xFF45524C), textHint = Color(0xFF7B867F),
    green = Color(0xFF0E6B5C), accent = Color(0xFF0E6B5C), mint = Color(0xFF7FE0C4), onMint = Color(0xFF10231F), hero = Color(0xFF10231F),
    infoBackground = Color(0xFFE3EDE8), warningBackground = Color(0xFFFFF1D0), warningText = Color(0xFF6B4A00), red = Color(0xFFB4412B),
    onDark = Color.White, onDarkMuted = Color(0xFFB9C7C1), peachOnDark = Color(0xFFFFB4A0), onPastel = Color(0xFF10231F),
    groupPastels = Pastels, dark = false,
)

val DarkPalette = OwnTallyPalette(
    background = Color(0xFF0E1512), card = Color(0xFF17211D), chip = Color(0xFF1E2A25), cardBorder = Color(0xFF2B3A34), border = Color(0xFF5A6F66),
    ink = Color(0xFFE8F0EC), textSecondary = Color(0xFFB0C0B8), textHint = Color(0xFF93A39B),
    green = Color(0xFF12806D), accent = Color(0xFF6FD8BF), mint = Color(0xFF6FD8BF), onMint = Color(0xFF06241D), hero = Color(0xFF0F3D34),
    infoBackground = Color(0xFF1C2B26), warningBackground = Color(0xFF3A2F14), warningText = Color(0xFFFFD98A), red = Color(0xFFFF8A73),
    onDark = Color.White, onDarkMuted = Color(0xFFD9EBE3), peachOnDark = Color(0xFFFFD0C2), onPastel = Color(0xFF10231F),
    groupPastels = Pastels, dark = true,
)

val LocalOwnTallyPalette = staticCompositionLocalOf { LightPalette }

/** The colours of the theme in use. Read them where they are drawn, so the screen follows the theme. */
object OwnTallyColors {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.background
    val Card: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.card
    val Chip: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.chip
    val CardBorder: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.cardBorder
    val Border: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.border
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.ink
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.textSecondary
    val TextHint: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.textHint
    /** The fill of buttons and hero cards (with [OnDark] on it). For green text, outlines and icons use [Accent]. */
    val Green: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.green
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.accent
    val Mint: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.mint
    val Hero: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.hero
    val OnMint: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.onMint
    val InfoBackground: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.infoBackground
    val WarningBackground: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.warningBackground
    val WarningText: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.warningText
    val Red: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.red
    val OnDark: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.onDark
    val OnDarkMuted: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.onDarkMuted
    val PeachOnDark: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.peachOnDark
    val OnPastel: Color @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.onPastel

    /** The icon colours a group can have, in the order of the colour index kept for each group. */
    val GroupPastels: List<Color> @Composable @ReadOnlyComposable get() = LocalOwnTallyPalette.current.groupPastels
}

private val OwnTallyTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
)

/** The app's theme: light or dark as the phone says ([dark] is for previews and tests that need to force one). */
@Composable
fun OwnTallyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onMint, background = p.background, onBackground = p.ink, surface = p.card, onSurface = p.ink,
            error = p.red, outline = p.border,
        )
    } else {
        lightColorScheme(
            primary = p.green, onPrimary = Color.White, background = p.background, onBackground = p.ink, surface = p.card, onSurface = p.ink,
            error = p.red, outline = p.border,
        )
    }
    CompositionLocalProvider(LocalOwnTallyPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = OwnTallyTypography, content = content)
    }
}
