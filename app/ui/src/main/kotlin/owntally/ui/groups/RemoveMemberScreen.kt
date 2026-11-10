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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.components.PrimaryButton
import owntally.ui.components.TextAction
import owntally.ui.theme.OwnTallyColors

/** Remove a member (design/13): what changes for everyone, what is left out, then the red button. */
@Composable
fun RemoveMemberScreen(model: RemoveMemberModel, onCancel: () -> Unit, onConfirm: () -> Unit, busy: Boolean = false, error: String? = null) {
    Column(Modifier.fillMaxSize().padding(20.dp, 8.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onCancel).semantics { contentDescription = "Close"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.CLOSE, OwnTallyColors.Ink)
                }
                Spacer(Modifier.width(8.dp))
                Text(model.title, style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink)
            }
            Spacer(Modifier.height(10.dp))
            for (p in model.intro) {
                Text(p, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("WHAT CHANGES FOR EVERYONE", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(24.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text("Member", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
                    Text("Now", Modifier.width(88.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
                    Text("After", Modifier.width(96.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
                }
                for (r in model.rows) {
                    HorizontalDivider(color = OwnTallyColors.CardBorder)
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink)
                        Text(r.now, Modifier.width(88.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge, color = r.nowKind.colour())
                        Text(
                            r.after, Modifier.width(96.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge,
                            color = r.afterKind?.colour() ?: OwnTallyColors.TextSecondary,
                        )
                    }
                }
            }
            if (model.alsoLeftOut != null) {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().background(OwnTallyColors.WarningBackground, RoundedCornerShape(20.dp)).padding(20.dp)) {
                    Text("Also left out of the group", style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.WarningText)
                    Spacer(Modifier.height(6.dp))
                    val text = buildString {
                        append(model.alsoLeftOut.summary)
                        if (model.alsoLeftOut.summary.isNotEmpty() && model.alsoLeftOut.payments.isNotEmpty()) append(", and ")
                        if (model.alsoLeftOut.payments.isNotEmpty()) append(if (model.alsoLeftOut.payments.size == 1) "1 payment" else "${model.alsoLeftOut.payments.size} payments")
                        append(".")
                    }
                    Text(text, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.WarningText)
                    for (line in model.alsoLeftOut.payments) {
                        Spacer(Modifier.height(4.dp))
                        Text(line, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.WarningText)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.Red)
            Spacer(Modifier.height(8.dp))
        }
        PrimaryButton(model.button, onConfirm, destructive = true, loading = busy)
        TextAction("Cancel", onCancel)
    }
}
