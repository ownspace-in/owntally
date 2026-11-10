package owntally.ui.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.theme.OwnTallyColors

/** Privacy (M10, T10.5): who sees what, in plain words, from [PrivacyFacts]. */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = "Back"; role = Role.Button }, contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.BACK, OwnTallyColors.Ink)
                }
                Spacer(Modifier.width(8.dp))
                Text("Privacy", style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink, modifier = Modifier.semantics { heading() })
            }
        }
        items(PrivacyFacts.sections, key = { it.id }) { section ->
            Column(
                Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(24.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(24.dp)).padding(18.dp),
            ) {
                Text(section.heading, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink, modifier = Modifier.semantics { heading() })
                for (line in section.lines) {
                    Spacer(Modifier.height(8.dp))
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                }
            }
        }
        item {
            Text("The same text is on ${PrivacyFacts.PAGE}", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
        }
    }
}
