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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.theme.OwnTallyColors

/**
 * The per-group Sync screen (design/05): "Sync now", what the sync is doing, one line per member, and (decision S4) the one notice of the
 * latest run that dropped entries and the note listing the latest dropped entries. Nearby (M6) and the relay card (the app decides) are not drawn.
 */
@Composable
fun SyncScreen(model: SyncScreenModel, onBack: () -> Unit, onSyncNow: () -> Unit, onDismissNotice: () -> Unit, onNearby: (() -> Unit)? = null, onExplain: (SyncMemberLine) -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 8.dp, 20.dp, 32.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = "Back"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.BACK, OwnTallyColors.Ink)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Sync", style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink)
                    Text(model.groupName, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().background(OwnTallyColors.Green, RoundedCornerShape(24.dp)).clickable(onClick = onSyncNow).padding(vertical = 20.dp)
                    .semantics { contentDescription = "Sync now"; role = SemanticsRole.Button },
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.SYNC, OwnTallyColors.OnDark)
                Spacer(Modifier.width(10.dp))
                Text("Sync now", style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.OnDark)
            }
            if (onNearby != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().border(1.dp, OwnTallyColors.Accent, RoundedCornerShape(24.dp)).clickable(onClick = onNearby).padding(vertical = 16.dp)
                        .semantics { contentDescription = "Nearby"; role = SemanticsRole.Button },
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) { Text("Nearby: sync in the same room", style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Accent) }
            }
            Spacer(Modifier.height(14.dp))
            Text(model.headlineText, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
            if (model.notice != null) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().background(OwnTallyColors.WarningBackground, RoundedCornerShape(20.dp)).padding(start = 18.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(model.notice, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.WarningText)
                    Box(Modifier.size(44.dp).clickable(onClick = onDismissNotice).semantics { contentDescription = "Dismiss"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.CLOSE, OwnTallyColors.WarningText)
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("MEMBERS", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
            Spacer(Modifier.height(10.dp))
            if (model.members.isEmpty()) {
                Text("No one else is in this group yet.", style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
            } else {
                Column(Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(24.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(24.dp))) {
                    model.members.forEachIndexed { i, m ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = OwnTallyColors.CardBorder)
                        MemberSyncRow(m, if (m.explains) ({ onExplain(m) }) else null)
                    }
                }
            }
            if (model.rejected.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Text("REJECTED ENTRIES", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().background(OwnTallyColors.InfoBackground, RoundedCornerShape(20.dp)).padding(18.dp)) {
                    for (line in model.rejected) {
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberSyncRow(m: SyncMemberLine, onOpen: (() -> Unit)?) {
    val tap = if (onOpen != null) Modifier.clickable(onClick = onOpen).semantics { role = SemanticsRole.Button } else Modifier
    Row(Modifier.fillMaxWidth().then(tap).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(OwnTallyColors.GroupPastels[m.colourIndex % OwnTallyColors.GroupPastels.size], CircleShape), contentAlignment = Alignment.Center) {
            Text(m.initial, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.OnPastel)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Ink, maxLines = 1)
            Text(
                m.detail, style = MaterialTheme.typography.bodyMedium,
                color = if (m.tone == BadgeTone.OFFLINE && m.detail.contains("not shared")) OwnTallyColors.WarningText else OwnTallyColors.TextSecondary,
            )
        }
        Spacer(Modifier.width(8.dp))
        val (background, content) = when (m.tone) {
            BadgeTone.GOOD -> OwnTallyColors.InfoBackground to OwnTallyColors.Accent
            BadgeTone.NEUTRAL -> OwnTallyColors.Chip to OwnTallyColors.TextSecondary
            BadgeTone.WARN -> OwnTallyColors.WarningBackground to OwnTallyColors.WarningText
            BadgeTone.OFFLINE -> OwnTallyColors.Chip to OwnTallyColors.TextSecondary
        }
        Text(m.badge, style = MaterialTheme.typography.labelLarge, color = content, modifier = Modifier.background(background, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** The Sync tab: every group that can be opened, with a short line about its sync; tap one for the Sync screen. */
@Composable
fun SyncTab(cards: List<GroupCardModel>, statusLine: @Composable (GroupCardModel) -> String, onOpen: (GroupCardModel) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 16.dp, 20.dp, 20.dp)) {
        item {
            Text("Sync", style = MaterialTheme.typography.headlineLarge, color = OwnTallyColors.Ink)
            Spacer(Modifier.height(6.dp))
            Text("Choose a group to see who it has reached.", style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
            Spacer(Modifier.height(18.dp))
            if (cards.isEmpty()) Text("No groups yet.", style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
        }
        cards.forEach { card ->
            item(key = card.id.toHex()) {
                Column(
                    Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(24.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(24.dp))
                        .clickable { onOpen(card) }.padding(16.dp),
                ) {
                    Text(card.name, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink)
                    Text(statusLine(card), style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}
