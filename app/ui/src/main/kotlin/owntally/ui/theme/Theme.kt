package owntally.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Colours read from the mockups in design/ (light theme first, as BUILD_PLAN says). */
object OwnTallyColors {
    val Background = Color(0xFFF3F5F1)
    val Green = Color(0xFF0E6B5C)
    val Ink = Color(0xFF10231F)
    val Mint = Color(0xFF7FE0C4)
    val WarningBackground = Color(0xFFFFF1D0)
    val WarningText = Color(0xFF6B4A00)
    val InfoBackground = Color(0xFFE3EDE8)
    val Red = Color(0xFFB4412B)
    val Border = Color(0xFFD5DAD3)
    val TextSecondary = Color(0xFF45524C)
    val TextHint = Color(0xFF7B867F)
    val Card = Color.White
    val Chip = Color(0xFFF3F5F1)
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

@Composable
fun OwnTallyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = OwnTallyColors.Green,
            onPrimary = Color.White,
            background = OwnTallyColors.Background,
            onBackground = OwnTallyColors.Ink,
            surface = OwnTallyColors.Card,
            onSurface = OwnTallyColors.Ink,
            error = OwnTallyColors.Red,
            outline = OwnTallyColors.Border,
        ),
        typography = OwnTallyTypography,
        content = content,
    )
}
