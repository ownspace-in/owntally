package owntally.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The small line icons of the mockups, drawn here so the app needs no icon library. */
enum class Glyph { GROUPS, SETTLE, SYNC, ABOUT, BACK, PLUS, PHONE }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val s = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * w, y * w)
        when (glyph) {
            Glyph.GROUPS -> {
                drawCircle(color, w * 0.16f, p(0.38f, 0.32f), style = s)
                drawArc(color, 180f, 180f, false, p(0.14f, 0.62f), Size(w * 0.48f, w * 0.5f), style = s)
                drawCircle(color, w * 0.13f, p(0.68f, 0.28f), style = s)
                drawArc(color, 200f, 150f, false, p(0.56f, 0.52f), Size(w * 0.34f, w * 0.4f), style = s)
            }
            Glyph.SETTLE -> {
                drawLine(color, p(0.18f, 0.35f), p(0.82f, 0.35f), s.width, StrokeCap.Round)
                drawLine(color, p(0.82f, 0.35f), p(0.66f, 0.2f), s.width, StrokeCap.Round)
                drawLine(color, p(0.82f, 0.35f), p(0.66f, 0.5f), s.width, StrokeCap.Round)
                drawLine(color, p(0.82f, 0.68f), p(0.18f, 0.68f), s.width, StrokeCap.Round)
                drawLine(color, p(0.18f, 0.68f), p(0.34f, 0.53f), s.width, StrokeCap.Round)
                drawLine(color, p(0.18f, 0.68f), p(0.34f, 0.83f), s.width, StrokeCap.Round)
            }
            Glyph.SYNC -> {
                drawArc(color, 200f, 250f, false, p(0.16f, 0.16f), Size(w * 0.68f, w * 0.68f), style = s)
                drawLine(color, p(0.2f, 0.28f), p(0.22f, 0.12f), s.width, StrokeCap.Round)
                drawLine(color, p(0.2f, 0.28f), p(0.36f, 0.3f), s.width, StrokeCap.Round)
            }
            Glyph.ABOUT -> {
                drawCircle(color, w * 0.38f, p(0.5f, 0.5f), style = s)
                drawLine(color, p(0.5f, 0.3f), p(0.5f, 0.55f), s.width, StrokeCap.Round)
                drawCircle(color, w * 0.04f, p(0.5f, 0.69f))
            }
            Glyph.BACK -> {
                val path = Path().apply { moveTo(w * 0.62f, w * 0.2f); lineTo(w * 0.3f, w * 0.5f); lineTo(w * 0.62f, w * 0.8f) }
                drawPath(path, color, style = s)
            }
            Glyph.PLUS -> {
                drawLine(color, p(0.5f, 0.22f), p(0.5f, 0.78f), s.width, StrokeCap.Round)
                drawLine(color, p(0.22f, 0.5f), p(0.78f, 0.5f), s.width, StrokeCap.Round)
            }
            Glyph.PHONE -> phone(color, s)
        }
    }
}

private fun DrawScope.phone(color: Color, s: Stroke) {
    val w = size.width
    drawRoundRect(color, Offset(w * 0.3f, w * 0.14f), Size(w * 0.4f, w * 0.72f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = s)
    drawLine(color, Offset(w * 0.45f, w * 0.74f), Offset(w * 0.55f, w * 0.74f), s.width, StrokeCap.Round)
}
