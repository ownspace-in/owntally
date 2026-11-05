package owntally.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.components.OwnTallyField
import owntally.ui.components.PrimaryButton
import owntally.ui.theme.OwnTallyColors

/** "Your name and phone in Goa Trip" (D3), with the red Leave group at the bottom (D4). */
@Composable
fun ProfileScreen(
    title: String,
    name: String,
    phone: String,
    check: ProfileCheck,
    error: String?,
    onName: (String) -> Unit,
    onPhone: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onLeave: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp, 8.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = "Back"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.BACK, OwnTallyColors.Ink)
            }
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink)
        }
        Spacer(Modifier.height(16.dp))
        Text("Everyone in this group sees this name and number. Changing it here changes nothing in your other groups.", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
        Spacer(Modifier.height(16.dp))
        OwnTallyField("Name", name, onName, "Your name", error = check.nameProblem)
        if (check.nameProblem != null) Text(check.nameProblem, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.Red)
        Spacer(Modifier.height(16.dp))
        OwnTallyField("Phone number", phone, onPhone, "+91 98765 43210", error = check.phoneProblem, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        if (check.phoneProblem != null) Text(check.phoneProblem, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.Red)
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.Red)
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Save", onSave, enabled = check.canSave)
        Spacer(Modifier.height(32.dp))
        Text(
            "Leave group", style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Red,
            modifier = Modifier.clickable(onClick = onLeave).padding(vertical = 12.dp).semantics { role = SemanticsRole.Button },
        )
    }
}
