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
import androidx.compose.foundation.lazy.items
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

/** Late entries of someone who was removed or left, with "Add again" on each expense (D5b, D5c). */
@Composable
fun CancelledScreen(model: CancelledListModel, onBack: () -> Unit, onAddAgain: (CancelledExpenseRow) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 8.dp, 20.dp, 32.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = "Back"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.BACK, OwnTallyColors.Ink)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(model.name, style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink)
                    Text(model.heading, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "These were written after ${model.name} went, so they do not count. You can add one again as your own entry.",
                style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary,
            )
            Spacer(Modifier.height(16.dp))
            if (model.empty != null) Text(model.empty, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
        }
        items(model.expenses, key = { it.id.author.toHex() + it.id.counter }) { row ->
            Row(
                Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(20.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.description, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink, maxLines = 2)
                    Text(row.detail, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                }
                Text(
                    "Add again", style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Accent,
                    modifier = Modifier.clickable { onAddAgain(row) }.padding(8.dp).semantics { role = SemanticsRole.Button },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        if (model.others.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("Also not counted: ${model.others.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
            }
        }
    }
}
