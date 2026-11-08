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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import owntally.model.format.BalanceKind
import owntally.model.format.BalanceView
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.components.Notice
import owntally.ui.components.OwnTallyField
import owntally.ui.components.PrimaryButton
import owntally.ui.components.Wordmark
import owntally.ui.theme.OwnTallyColors

internal fun BalanceKind.colour(onDark: Boolean = false) = when (this) {
    BalanceKind.OWED -> if (onDark) OwnTallyColors.Mint else OwnTallyColors.Green
    BalanceKind.OWES -> if (onDark) OwnTallyColors.PeachOnDark else OwnTallyColors.Red
    BalanceKind.SETTLED -> if (onDark) OwnTallyColors.OnDarkMuted else OwnTallyColors.TextSecondary
}

@Composable
fun GroupIcon(initials: String, colourIndex: Int, size: Int = 48) {
    val colour = OwnTallyColors.GroupPastels[colourIndex.coerceIn(0, OwnTallyColors.GroupPastels.lastIndex)]
    Box(Modifier.size(size.dp).background(colour, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        Text(initials, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink)
    }
}

@Composable
fun StoredOnlyHereChip() {
    Row(
        Modifier.background(OwnTallyColors.InfoBackground, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.PHONE, OwnTallyColors.Green, 20.dp)
        Spacer(Modifier.width(8.dp))
        Text("Stored only on this phone", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.Green)
    }
}

@Composable
fun GroupsScreen(
    model: GroupsListModel, onOpen: (GroupCardModel) -> Unit, onCreate: () -> Unit,
    invitations: List<PendingCardModel> = emptyList(), onOpenInvitation: (PendingCardModel) -> Unit = {}, onJoin: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 16.dp, 20.dp, 112.dp)) {
            item {
                Wordmark()
                Spacer(Modifier.height(14.dp))
                StoredOnlyHereChip()
                Spacer(Modifier.height(20.dp))
                TotalsCard(model.owed, model.owe)
                Spacer(Modifier.height(24.dp))
                Text("GROUPS", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
            }
            if (invitations.isNotEmpty()) {
                items(invitations, key = { "invite-" + it.group.toHex() }) { card ->
                    InvitationCard(card) { onOpenInvitation(card) }
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (model.cards.isEmpty()) {
                item {
                    Text("No groups yet. Make one to start splitting expenses with friends.", style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
                }
            }
            items(model.cards, key = { it.id.toHex() }) { card ->
                GroupCard(card) { onOpen(card) }
                Spacer(Modifier.height(10.dp))
            }
            if (onJoin != null) {
                item {
                    Spacer(Modifier.height(6.dp))
                    Text("Have an invite? Join a group", style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Green, modifier = Modifier.clickable(onClick = onJoin).padding(8.dp).semantics { role = SemanticsRole.Button })
                }
            }
        }
        NewGroupButton(onCreate, Modifier.align(Alignment.BottomEnd).padding(20.dp))
    }
}

/** A group this person asked to join and is not in yet. */
@Composable
private fun InvitationCard(card: PendingCardModel, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(24.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(24.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).background(OwnTallyColors.GroupPastels[0], RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Text(card.initials, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Ink)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(card.title, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Ink)
            Text(card.status, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
        }
    }
}

@Composable
private fun NewGroupButton(onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier.background(OwnTallyColors.Green, RoundedCornerShape(30.dp)).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.PLUS, androidx.compose.ui.graphics.Color.White)
        Spacer(Modifier.width(10.dp))
        Text("New group", style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.White)
    }
}

@Composable
private fun TotalsCard(owed: BalanceView, owe: BalanceView) {
    Row(Modifier.fillMaxWidth().background(OwnTallyColors.Ink, RoundedCornerShape(28.dp)).padding(24.dp)) {
        Column(Modifier.weight(1f)) {
            Text("You are owed", style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.OnDarkMuted)
            Text(owed.text, style = MaterialTheme.typography.headlineMedium, color = OwnTallyColors.Mint)
        }
        Column(Modifier.weight(1f)) {
            Text("You owe", style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.OnDarkMuted)
            Text(owe.text, style = MaterialTheme.typography.headlineMedium, color = OwnTallyColors.PeachOnDark)
        }
    }
}

private fun BalanceView.caption() = when (kind) {
    BalanceKind.OWED -> "you are owed"
    BalanceKind.OWES -> "you owe"
    BalanceKind.SETTLED -> "settled up"
}

@Composable
fun GroupCard(card: GroupCardModel, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(24.dp))
            .border(BorderStroke(1.dp, OwnTallyColors.CardBorder), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupIcon(card.initials, card.colourIndex)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(card.name, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(card.subtitle, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
            if (card.ownershipOffer) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ownership request", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.WarningText,
                    modifier = Modifier.background(OwnTallyColors.WarningBackground, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (card.readable && !card.removed) {
            Column(horizontalAlignment = Alignment.End) {
                if (card.balance.kind == BalanceKind.SETTLED) {
                    Text("settled up", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
                } else {
                    Text(card.balance.caption(), style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                    Text(card.balance.text, style = MaterialTheme.typography.labelLarge, color = card.balance.kind.colour())
                }
            }
        }
    }
}

@Composable
fun GroupScreen(
    model: GroupScreenModel,
    onBack: () -> Unit,
    onAdd: (() -> Unit)? = null,
    onSettle: (() -> Unit)? = null,
    onOpenExpense: (ExpenseRow) -> Unit = {},
    onMembers: (() -> Unit)? = null,
    onProfile: (() -> Unit)? = null,
    banner: OwnershipBannerModel? = null,
    onAcceptOffer: () -> Unit = {},
    onDeclineOffer: () -> Unit = {},
    extra: @Composable () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 8.dp, 20.dp, 112.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = "Back"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.BACK, OwnTallyColors.Ink)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink)
                    Text(
                        model.roleText, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary,
                        modifier = if (onProfile != null) Modifier.clickable(onClick = onProfile) else Modifier,
                    )
                }
                if (onMembers != null) {
                    Box(Modifier.size(44.dp).clickable(onClick = onMembers).semantics { contentDescription = "Members"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.GROUPS, OwnTallyColors.Ink)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (banner != null) {
                OwnershipBanner(banner, onAcceptOffer, onDeclineOffer)
                Spacer(Modifier.height(14.dp))
            }
            BalanceCard(model, onSettle)
            extra()
            Spacer(Modifier.height(24.dp))
            Text("EXPENSES", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
            Spacer(Modifier.height(12.dp))
            if (model.expenses.isEmpty()) {
                Text("No expenses yet.", style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
            }
        }
        items(model.expenses, key = { it.id.author.toHex() + it.id.counter }) { row ->
            ExpenseRowView(row, Modifier.clickable { onOpenExpense(row) })
            Spacer(Modifier.height(14.dp))
        }
    }
    if (onAdd != null) {
        Box(
            Modifier.align(Alignment.BottomEnd).padding(20.dp).size(64.dp).background(OwnTallyColors.Green, CircleShape).clickable(onClick = onAdd)
                .semantics { contentDescription = "Add expense"; role = SemanticsRole.Button },
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.PLUS, androidx.compose.ui.graphics.Color.White, 28.dp) }
    }
    }
}

@Composable
private fun BalanceCard(model: GroupScreenModel, onSettle: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().background(OwnTallyColors.Ink, RoundedCornerShape(28.dp)).padding(24.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (model.balance.kind) {
                        BalanceKind.OWED -> "You are owed in total"
                        BalanceKind.OWES -> "You owe in total"
                        BalanceKind.SETTLED -> "You are settled up"
                    },
                    style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.OnDarkMuted,
                )
                Text(model.balance.text, style = MaterialTheme.typography.headlineLarge, color = model.balance.kind.colour(onDark = true))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Total spent", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.OnDarkMuted)
                Text(model.totalSpent, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.OnDark)
            }
        }
        if (model.memberLines.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = OwnTallyColors.OnDarkMuted.copy(alpha = 0.3f))
            Spacer(Modifier.height(10.dp))
            for (line in model.memberLines) {
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(line.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.OnDark)
                    if (line.amount != null) Text(line.amount, style = MaterialTheme.typography.labelLarge, color = line.kind.colour(onDark = true))
                }
            }
        }
        if (onSettle != null && model.memberLines.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().height(52.dp).background(OwnTallyColors.Mint, RoundedCornerShape(20.dp)).clickable(onClick = onSettle)
                    .semantics { role = SemanticsRole.Button },
                contentAlignment = Alignment.Center,
            ) { Text("Settle up the group", style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink) }
        }
    }
}

@Composable
private fun ExpenseRowView(row: ExpenseRow, modifier: Modifier = Modifier) {
    val strike = if (row.deleted) TextDecoration.LineThrough else null
    val grey = row.deleted || row.excluded
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(OwnTallyColors.InfoBackground, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Text(row.description.trim().take(1).uppercase(), style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.description, style = MaterialTheme.typography.labelLarge, color = if (grey) OwnTallyColors.TextHint else OwnTallyColors.Ink, textDecoration = strike, maxLines = 2)
            Text(row.subtitle, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (row.caption != null) Text(row.caption, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
            Text(
                row.amount, style = MaterialTheme.typography.labelLarge, textDecoration = strike,
                color = if (grey) OwnTallyColors.TextHint else row.kind.colour(),
            )
        }
    }
}

@Composable
fun CreateGroupScreen(model: CreateGroupModel, onChange: (CreateGroupModel) -> Unit, onCreate: () -> Unit, onBack: () -> Unit, error: String?) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = "Back"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.BACK, OwnTallyColors.Ink)
            }
            Spacer(Modifier.width(8.dp))
            Text("New group", style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink)
        }
        Spacer(Modifier.height(24.dp))
        OwnTallyField("Group name", model.name, { onChange(model.withName(it)) }, placeholder = "Goa Trip", error = model.problem)
        Spacer(Modifier.height(20.dp))
        Text("Icon colour", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (i in 0 until CreateGroupModel.COLOURS) {
                val selected = i == model.colourIndex
                Box(
                    Modifier.size(44.dp).background(OwnTallyColors.GroupPastels[i], CircleShape)
                        .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) OwnTallyColors.Green else OwnTallyColors.Border), CircleShape)
                        .clickable { onChange(model.withColour(i)) }
                        .semantics { role = SemanticsRole.RadioButton; this.selected = selected; contentDescription = "Colour ${i + 1}" },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Only you see this colour. It stays on this phone.", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
        Spacer(Modifier.height(20.dp))
        Text("Currency", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Text("Indian rupee (₹)", style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.Ink)
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Notice(error)
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Create group", onCreate, Modifier.fillMaxWidth(), enabled = model.canCreate)
    }
}

@Composable
fun MessageTab(title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = OwnTallyColors.Ink)
        Spacer(Modifier.height(14.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary, textAlign = TextAlign.Start)
    }
}

/** "Asha wants to make you the owner of Goa Trip", with Accept and Decline (D1). */
@Composable
fun OwnershipBanner(banner: OwnershipBannerModel, onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(OwnTallyColors.WarningBackground, RoundedCornerShape(20.dp)).padding(16.dp)) {
        Text(banner.text, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.WarningText)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.background(OwnTallyColors.Green, RoundedCornerShape(50)).clickable(onClick = onAccept).padding(horizontal = 20.dp, vertical = 10.dp)
                    .semantics { role = SemanticsRole.Button },
            ) { Text("Accept", style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.OnDark) }
            Box(
                Modifier.border(1.dp, OwnTallyColors.WarningText, RoundedCornerShape(50)).clickable(onClick = onDecline).padding(horizontal = 20.dp, vertical = 10.dp)
                    .semantics { role = SemanticsRole.Button },
            ) { Text("Decline", style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.WarningText) }
        }
    }
}
