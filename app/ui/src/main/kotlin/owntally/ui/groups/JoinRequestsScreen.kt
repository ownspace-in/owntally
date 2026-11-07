package owntally.ui.groups

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import owntally.data.session.GroupView
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.theme.OwnTallyColors

/** Join requests (design/11). The "Open invites" section of the mockup arrives with invitations in M6. */
@Composable
fun JoinRequestsScreen(model: JoinRequestsScreenModel, onBack: () -> Unit, onApprove: (JoinRequestRow) -> Unit, onDecline: (JoinRequestRow) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 8.dp, 20.dp, 16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = "Back"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.BACK, OwnTallyColors.Ink)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Join requests", style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink)
                        Text(model.groupName, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(model.heading.uppercase(), style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                if (model.empty != null) Text(model.empty, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
                if (model.full && model.rows.isNotEmpty()) {
                    Text(
                        "This group already has the most people it can have. Remove someone before approving.",
                        style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.Red,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(model.rows, key = { it.member.toHex() }) { row ->
                Column(Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(24.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(24.dp)).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).background(OwnTallyColors.GroupPastels[row.colourIndex % OwnTallyColors.GroupPastels.size], CircleShape), contentAlignment = Alignment.Center) {
                            Text(row.initial, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Ink)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.name, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Ink, maxLines = 1)
                            Text(row.phone, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary, maxLines = 1)
                        }
                        Text(row.ago, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { onDecline(row) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, OwnTallyColors.Red),
                        ) { Text("Decline", style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Red) }
                        Button(
                            onClick = { onApprove(row) }, enabled = !model.full, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = OwnTallyColors.Green, contentColor = OwnTallyColors.OnDark),
                        ) { Text("Approve", style = MaterialTheme.typography.labelLarge) }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
        Box(Modifier.padding(20.dp, 0.dp, 20.dp, 20.dp).fillMaxWidth().background(OwnTallyColors.InfoBackground, RoundedCornerShape(20.dp)).padding(18.dp)) {
            Text(model.note, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
        }
    }
}

/** Approve opens this: the name the person asked for, which the admin may change, checked live against the group's names. */
@Composable
fun ApproveDialog(view: GroupView, row: JoinRequestRow, onCancel: () -> Unit, onApprove: (String) -> Unit) {
    var typed by remember { mutableStateOf(row.name) }
    val check = remember(typed, view) { ApproveCheck.of(view, typed) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Approve ${row.name}?") },
        text = {
            Column {
                Text("This is the name everyone in the group will see.", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it.take(60) }, singleLine = true, isError = check.problem != null,
                    supportingText = { if (check.problem != null) Text(check.problem) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Name in this group" },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onApprove(check.name) }, enabled = check.canApprove) { Text("Approve") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
