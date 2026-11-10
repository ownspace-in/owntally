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
import androidx.compose.foundation.lazy.LazyColumn
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

/** "Can't reach <name>" (design/16, 16b): the reason, the automatic relay, and the two things a person can do. */
@Composable
fun CantReachScreen(model: CantReachModel, onBack: () -> Unit, onTryNow: () -> Unit, onNearby: (() -> Unit)?) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 8.dp, 20.dp, 32.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onBack).semantics { contentDescription = "Back"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.BACK, OwnTallyColors.Ink)
                }
                Spacer(Modifier.width(8.dp))
                Text(model.title, style = MaterialTheme.typography.headlineSmall, color = OwnTallyColors.Ink)
            }
            Spacer(Modifier.height(16.dp))
            val (bg, fg) = if (model.bannerTone == CantReachTone.WARNING) OwnTallyColors.WarningBackground to OwnTallyColors.WarningText else OwnTallyColors.InfoBackground to OwnTallyColors.Accent
            Column(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(24.dp)).padding(18.dp)) {
                Text(model.bannerTitle, style = MaterialTheme.typography.titleMedium, color = fg)
                Spacer(Modifier.height(6.dp))
                Text(model.bannerBody, style = MaterialTheme.typography.bodyMedium, color = fg)
            }
            Spacer(Modifier.height(22.dp))
            Text(model.waysHeading, style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(24.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(24.dp)).padding(18.dp)) {
                Text(model.relayTitle, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Ink)
                Spacer(Modifier.height(6.dp))
                Text(model.relayBody, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                if (model.relayStatus != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(model.relayStatus, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.Ink)
                }
                Spacer(Modifier.height(14.dp))
                val enabled = !model.trying
                Box(
                    Modifier.fillMaxWidth().background(OwnTallyColors.Green, RoundedCornerShape(24.dp))
                        .then(if (enabled) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onTryNow) else Modifier).padding(vertical = 14.dp)
                        .semantics { role = SemanticsRole.Button },
                    contentAlignment = Alignment.Center,
                ) { Text(model.tryNowLabel, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.OnDark) }
            }
            if (onNearby != null) {
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(24.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(24.dp))
                        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onNearby).semantics { role = SemanticsRole.Button }.padding(18.dp),
                ) {
                    Text(model.nearbyTitle, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Ink)
                    Text(model.nearbySubtitle, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(24.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(24.dp)).padding(18.dp)) {
                Text(model.laterTitle, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Ink)
                Text(model.laterSubtitle, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
            }
            Spacer(Modifier.height(22.dp))
            Text(model.footer, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
            Spacer(Modifier.height(24.dp))
        }
    }
}
