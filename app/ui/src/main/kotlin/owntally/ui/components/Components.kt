package owntally.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import owntally.ui.theme.OwnTallyColors

/** The app mark from the mockups: a dark rounded square with a mint tick. */
@Composable
fun LogoMark(size: Int = 40) {
    Canvas(Modifier.size(size.dp).semantics { contentDescription = "OwnTally" }) {
        val s = this.size.minDimension
        drawRoundRect(OwnTallyColors.Ink, cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.25f, s * 0.25f))
        val tick = Path().apply {
            moveTo(s * 0.28f, s * 0.52f)
            lineTo(s * 0.44f, s * 0.68f)
            lineTo(s * 0.74f, s * 0.34f)
        }
        drawPath(tick, OwnTallyColors.Mint, style = Stroke(width = s * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LogoMark()
        Text("OwnTally", style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink)
    }
}

@Composable
fun StepLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
}

/** A labelled, rounded text field as in the mockups. A non-null [error] is shown in red under the field. */
@Composable
fun OwnTallyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = OwnTallyColors.TextHint) },
            isError = error != null,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                errorContainerColor = Color.White,
                focusedBorderColor = OwnTallyColors.Green,
                unfocusedBorderColor = OwnTallyColors.Border,
                errorBorderColor = OwnTallyColors.Red,
                cursorColor = OwnTallyColors.Green,
            ),
        )
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.Red)
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, destructive: Boolean = false) {
    val container = if (destructive) OwnTallyColors.Red else OwnTallyColors.Green
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(27.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = Color.White,
            disabledContainerColor = container.copy(alpha = 0.35f),
            disabledContentColor = Color.White,
        ),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
        else Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Green)
    }
}

/** A tinted box with a short message (the amber warning, the green hint). */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, background: Color = OwnTallyColors.WarningBackground, content: Color = OwnTallyColors.WarningText) {
    Box(modifier.fillMaxWidth().background(background, RoundedCornerShape(20.dp)).padding(20.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = content)
    }
}

@Composable
fun CenteredNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
}
