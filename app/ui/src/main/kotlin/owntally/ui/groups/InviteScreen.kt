package owntally.ui.groups

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.theme.OwnTallyColors

/**
 * Invite friends (design/10): the QR code for people in the same room, how long the invite works, a button to share the link, the
 * link itself with Copy, and Revoke and New invite. Nothing here sends anything: the callbacks do the sharing and copying.
 */
@Composable
fun InviteScreen(model: InviteModel, onBack: () -> Unit, onShare: () -> Unit, onCopy: () -> Unit, onRevoke: () -> Unit, onNew: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp, 8.dp, 20.dp, 20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = "Back"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.BACK, OwnTallyColors.Ink)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Invite friends", style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink)
                    Text(model.groupName, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (model.qr != null) {
                Column(Modifier.fillMaxWidth().background(OwnTallyColors.Ink, RoundedCornerShape(32.dp)).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    QrView(model.qr, Modifier.size(232.dp))
                    Spacer(Modifier.height(18.dp))
                    Text(model.qrTitle, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.OnDark)
                    Text(model.qrHint, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.OnDarkMuted)
                }
                Spacer(Modifier.height(14.dp))
            }
            Row(Modifier.fillMaxWidth().background(OwnTallyColors.WarningBackground, RoundedCornerShape(16.dp)).padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(model.notice, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.WarningText)
            }
            if (model.link != null) {
                Spacer(Modifier.height(20.dp))
                Text("SEND TO A FRIEND", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onShare, enabled = model.canShare, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OwnTallyColors.Green, contentColor = OwnTallyColors.OnDark),
                ) { Text("Share invite link", style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(20.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(20.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Invite code", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                        Text(model.link.removePrefix("https://"), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = OwnTallyColors.Ink, maxLines = 2)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (copied) "Copied" else "Copy", style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Green,
                        modifier = Modifier.clickable(enabled = model.canCopy) { onCopy(); copied = true }.padding(8.dp).semantics { role = SemanticsRole.Button },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(model.warning, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onRevoke, enabled = model.canRevoke, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, if (model.canRevoke) OwnTallyColors.Red else OwnTallyColors.Border),
            ) { Text("Revoke invite", style = MaterialTheme.typography.titleMedium, color = if (model.canRevoke) OwnTallyColors.Red else OwnTallyColors.TextHint) }
            OutlinedButton(
                onClick = { copied = false; onNew() }, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, OwnTallyColors.Green),
            ) { Text(model.newLabel, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Green) }
        }
    }
}

/** The QR code on white with the four-square quiet border a reader needs, drawn square by square. */
@Composable
fun QrView(qr: QrMatrix, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color.White, RoundedCornerShape(20.dp)).padding(14.dp).semantics { contentDescription = "QR code for the invite" }) {
        val quiet = 2
        val cell = size.width / (qr.size + 2 * quiet)
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr[x, y]) drawRect(OwnTallyColors.Ink, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
