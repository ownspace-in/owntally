package owntally.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import owntally.data.session.LeftGroup
import owntally.model.GroupId
import owntally.ui.theme.OwnTallyColors

data class LeftGroupRow(val id: GroupId, val name: String)

/** The words of the "Groups you left" part of Settings (decisions D4 and S6). */
object LeftGroupsModels {
    const val TITLE = "GROUPS YOU LEFT"
    const val EXPLANATION =
        "You left these groups. They are hidden, and kept on this phone until one of the other members has received that you left; then they are deleted from this phone."

    fun warning(name: String) = "Nobody has seen that you left $name. If you delete it now, the others will still count you as a member."

    fun rows(left: List<LeftGroup>): List<LeftGroupRow> = left.map { LeftGroupRow(it.id, it.name.ifBlank { "A group you left" }) }
}

/** Under Settings: every group the person left whose leave no member has received yet, each with "Delete now" and a warning first. Nothing is drawn when there are none. */
@Composable
fun LeftGroupsSection(rows: List<LeftGroupRow>, onDelete: (GroupId) -> Unit) {
    if (rows.isEmpty()) return
    var asking by remember { mutableStateOf<LeftGroupRow?>(null) }
    Spacer(Modifier.height(28.dp))
    Text(LeftGroupsModels.TITLE, style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
    Spacer(Modifier.height(10.dp))
    Text(LeftGroupsModels.EXPLANATION, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
    Spacer(Modifier.height(10.dp))
    for (row in rows) {
        Row(
            Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(20.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink, maxLines = 1)
            TextButton(onClick = { asking = row }) { Text("Delete now", color = OwnTallyColors.Red) }
        }
        Spacer(Modifier.height(10.dp))
    }
    val target = asking
    if (target != null) {
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Delete ${target.name}?") },
            text = { Text(LeftGroupsModels.warning(target.name)) },
            confirmButton = { TextButton(onClick = { asking = null; onDelete(target.id) }) { Text("Delete now", color = OwnTallyColors.Red) } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Keep it") } },
        )
    }
}
