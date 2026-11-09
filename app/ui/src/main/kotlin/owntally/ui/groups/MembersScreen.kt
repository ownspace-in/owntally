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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import owntally.model.Role
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.theme.OwnTallyColors

/** Members and roles (design/12). [onInvite], [onJoinRequests] and [onMenu] are null until the screens behind them exist. */
@Composable
fun MembersScreen(
    model: MembersScreenModel,
    onBack: () -> Unit,
    onInvite: (() -> Unit)? = null,
    onJoinRequests: (() -> Unit)? = null,
    onMenu: ((MemberRowModel) -> Unit)? = null,
    onMyProfile: (() -> Unit)? = null,
    onLeave: (() -> Unit)? = null,
    onRemovedTap: ((RemovedRowModel) -> Unit)? = null,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 8.dp, 20.dp, 32.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = "Back"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.BACK, OwnTallyColors.Ink)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Members", style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink)
                    Text(model.subtitle, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                }
                if (model.canInvite && onInvite != null) {
                    Box(Modifier.size(44.dp).clickable(onClick = onInvite).semantics { contentDescription = "Invite"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.PERSON_ADD, OwnTallyColors.Ink)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (model.joinRequestsCount != null) {
                Row(
                    Modifier.fillMaxWidth().background(OwnTallyColors.Green, RoundedCornerShape(24.dp))
                        .then(if (onJoinRequests != null) Modifier.clickable(onClick = onJoinRequests) else Modifier)
                        .padding(horizontal = 24.dp, vertical = 22.dp)
                        .semantics { contentDescription = "Join requests, ${model.joinRequestsCount} waiting"; role = SemanticsRole.Button },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Join requests", style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.OnDark, modifier = Modifier.weight(1f))
                    if (model.joinRequestsCount > 0) {
                        Box(Modifier.size(32.dp).background(OwnTallyColors.OnDark, CircleShape), contentAlignment = Alignment.Center) {
                            Text("${model.joinRequestsCount}", style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Accent)
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    GlyphIcon(Glyph.CHEVRON, OwnTallyColors.OnDark)
                }
                Spacer(Modifier.height(16.dp))
            }
            Column(Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(24.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(24.dp))) {
                model.rows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = OwnTallyColors.CardBorder)
                    MemberRow(row, onMenu, if (row.isMe) onMyProfile else null)
                }
            }
            Spacer(Modifier.height(20.dp))
            for (line in model.roleLines) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${line.label}: ") }
                        append(line.text)
                    },
                    style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (onLeave != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Leave group", style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Red,
                    modifier = Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(20.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(20.dp))
                        .clickable(onClick = onLeave).padding(horizontal = 20.dp, vertical = 16.dp).semantics { role = SemanticsRole.Button },
                )
            }
            if (model.removed.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("NO LONGER IN THE GROUP", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                for (r in model.removed) {
                    Row(
                        Modifier.fillMaxWidth().then(if (r.tappable && onRemovedTap != null) Modifier.clickable { onRemovedTap(r) } else Modifier).padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.TextHint)
                            Text(r.phone, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextHint)
                            if (r.lateEntries > 0) Text("Late entries not counted: ${r.lateEntries}", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.WarningText)
                        }
                        Text(r.how, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextHint)
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberRow(row: MemberRowModel, onMenu: ((MemberRowModel) -> Unit)?, onTap: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).background(OwnTallyColors.GroupPastels[row.colourIndex % OwnTallyColors.GroupPastels.size], CircleShape), contentAlignment = Alignment.Center) {
            Text(row.initial, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.OnPastel)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Ink)
            Text(row.phone, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary, maxLines = 1)
        }
        if (row.badge != null) {
            val owner = row.role == Role.OWNER
            Text(
                row.badge, style = MaterialTheme.typography.labelLarge, color = if (owner) OwnTallyColors.OnDark else OwnTallyColors.Accent,
                modifier = Modifier.background(if (owner) OwnTallyColors.Green else OwnTallyColors.InfoBackground, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (row.hasMenu && onMenu != null) {
            Box(
                Modifier.size(40.dp).clickable { onMenu(row) }.semantics { contentDescription = "Actions for ${row.label}"; role = SemanticsRole.Button },
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.MORE, OwnTallyColors.TextSecondary) }
        }
    }
}
