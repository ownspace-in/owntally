package owntally.ui.groups

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.components.Notice
import owntally.ui.theme.OwnTallyColors

private val cardShape = RoundedCornerShape(24.dp)

@Composable
private fun BackHeader(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = "Back"; role = Role.Button }, contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.BACK, OwnTallyColors.Ink)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
        }
    }
}

/** A button that is drawn switched off: not yet able to do anything, and says so to screen readers. */
@Composable
private fun OffButton(text: String, modifier: Modifier, filled: Boolean) {
    Box(
        modifier.height(48.dp)
            .background(if (filled) OwnTallyColors.Border else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(24.dp))
            .border(BorderStroke(1.dp, OwnTallyColors.Border), RoundedCornerShape(24.dp))
            .semantics(mergeDescendants = true) { disabled(); role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextHint) }
}

/** Pay: opens the share sheet with the payment text. It moves no money; the person pays in their own UPI app or with cash. */
@Composable
private fun PayButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(48.dp).border(BorderStroke(1.dp, OwnTallyColors.Green), RoundedCornerShape(24.dp)).clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.Green) }
}

@Composable
fun SettleUpScreen(model: SettleUpModel, onBack: () -> Unit, onShare: (String) -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 24.dp)) {
        item {
            BackHeader("Settle up", model.subtitle, onBack)
            Spacer(Modifier.height(12.dp))
            Text(SettleUpModel.INTRO, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.Ink)
            Spacer(Modifier.height(20.dp))
            Text("PAYMENTS TO MAKE", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
            Spacer(Modifier.height(10.dp))
        }
        items(model.payments, key = { it.from.toHex() + it.to.toHex() }) { p ->
            Column(Modifier.fillMaxWidth().background(OwnTallyColors.Card, cardShape).border(BorderStroke(1.dp, OwnTallyColors.CardBorder), cardShape).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(OwnTallyColors.GroupPastels[if (p.role == PaymentRole.FROM_ME) 1 else 3], CircleShape), contentAlignment = Alignment.Center) {
                        Text(p.title.take(1), style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink)
                        if (p.phone != null) Text(p.phone, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                    }
                    Text(p.amount, style = MaterialTheme.typography.titleLarge, color = if (p.role == PaymentRole.FROM_ME) OwnTallyColors.Red else OwnTallyColors.Green)
                }
                val labels = when (p.role) {
                    PaymentRole.FROM_ME -> "Pay" to "I paid"
                    PaymentRole.TO_ME -> "Send reminder" to "Mark received"
                    PaymentRole.BETWEEN_OTHERS -> null
                }
                if (labels != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (p.canPay) PayButton(labels.first, Modifier.weight(1f)) { onShare(p.payText(model.groupName)) }
                        else OffButton(labels.first, Modifier.weight(1f), filled = false)
                        OffButton(labels.second, Modifier.weight(1f), filled = true)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        item {
            if (model.footer.isNotEmpty()) {
                Row(verticalAlignment = Alignment.Top) {
                    Text("✓", color = OwnTallyColors.Green, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(10.dp))
                    Text(model.footer, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                }
                Spacer(Modifier.height(16.dp))
            }
            Notice(SettleUpModel.ACTIONS_NOTE, background = OwnTallyColors.InfoBackground, content = OwnTallyColors.Green)
        }
    }
}

@Composable
fun HowItWorksScreen(onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { BackHeader("How OwnTally works", null, onBack) }
        items(HowItWorks.cards) { c ->
            Row(Modifier.fillMaxWidth().background(OwnTallyColors.Card, cardShape).border(BorderStroke(1.dp, OwnTallyColors.CardBorder), cardShape).padding(16.dp)) {
                Box(
                    Modifier.size(40.dp).background(OwnTallyColors.GroupPastels[(c.number.toInt() - 1) % OwnTallyColors.GroupPastels.size], RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(c.number, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(c.title, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink)
                    Spacer(Modifier.height(4.dp))
                    Text(c.body, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(OwnTallyColors.InfoBackground, cardShape).padding(16.dp)) {
                Text(HowItWorks.note.title, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Green)
                Spacer(Modifier.height(4.dp))
                Text(HowItWorks.note.body, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.Green)
            }
        }
    }
}
