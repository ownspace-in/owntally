package owntally.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.components.PrimaryButton
import owntally.ui.components.TextAction
import owntally.ui.theme.OwnTallyColors

/** The owner's side of an ownership request (design/14b: confirm, 14c: waiting; plus the stage after a decline). */
@Composable
fun OwnerOfferScreen(model: OwnerOfferModel, onClose: () -> Unit, onPrimary: () -> Unit, onSecondary: () -> Unit, busy: Boolean = false, error: String? = null) {
    Column(Modifier.fillMaxSize().padding(20.dp, 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onClose).semantics { contentDescription = "Close"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.CLOSE, OwnTallyColors.Ink)
            }
            Spacer(Modifier.width(8.dp))
            Text(model.name, style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).background(OwnTallyColors.GroupPastels[model.colourIndex % OwnTallyColors.GroupPastels.size], CircleShape), contentAlignment = Alignment.Center) {
                Text(model.initial, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.OnPastel)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(model.name, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Ink)
                Text(model.line, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(20.dp))
        val waiting = model.stage != OfferStage.CONFIRM
        Column(
            Modifier.fillMaxWidth()
                .background(if (waiting) OwnTallyColors.WarningBackground else OwnTallyColors.Card, RoundedCornerShape(24.dp))
                .then(if (waiting) Modifier else Modifier.border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(24.dp)))
                .padding(20.dp),
        ) {
            Text(model.title, style = if (waiting) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge, color = if (waiting) OwnTallyColors.WarningText else OwnTallyColors.Ink)
            for (p in model.body) {
                Spacer(Modifier.height(8.dp))
                Text(p, style = MaterialTheme.typography.bodyLarge, color = if (waiting) OwnTallyColors.WarningText else OwnTallyColors.TextSecondary)
            }
            if (model.replaces != null) {
                Spacer(Modifier.height(8.dp))
                Text(model.replaces, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.Red)
            }
        }
        Spacer(Modifier.weight(1f))
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.Red)
            Spacer(Modifier.height(8.dp))
        }
        if (model.stage == OfferStage.WAITING) {
            OutlinedButton(
                onClick = onPrimary, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(27.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, OwnTallyColors.Red),
            ) { Text(model.primary, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Red) }
        } else {
            PrimaryButton(model.primary, onPrimary, loading = busy)
            TextAction(model.secondary ?: "Cancel", if (model.secondary != null) onSecondary else onClose)
        }
    }
}
