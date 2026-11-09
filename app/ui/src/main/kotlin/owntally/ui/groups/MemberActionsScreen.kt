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
import owntally.ui.theme.OwnTallyColors

/** One member's actions (design/14). The close button is the "X" of the mockup. [error] is a refusal from the group's rules. */
@Composable
fun MemberActionsScreen(model: MemberActionsModel, onClose: () -> Unit, onAction: (ActionKind) -> Unit, error: String? = null) {
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
        Spacer(Modifier.height(24.dp))
        Text("ROLES", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
        Spacer(Modifier.height(10.dp))
        for (a in model.actions) {
            Column(
                Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(20.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(20.dp))
                    .clickable { onAction(a.kind) }.padding(horizontal = 20.dp, vertical = 16.dp).semantics { role = SemanticsRole.Button },
            ) {
                Text(a.title, style = MaterialTheme.typography.labelLarge, color = if (a.destructive) OwnTallyColors.Red else OwnTallyColors.Ink)
                Text(a.subtitle, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
            }
            Spacer(Modifier.height(10.dp))
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.Red)
        }
    }
}
