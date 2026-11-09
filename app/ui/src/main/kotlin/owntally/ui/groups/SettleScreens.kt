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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.components.Notice
import owntally.model.DisputeReason
import owntally.model.Money
import owntally.model.format.MoneyFormat
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

/** What a person tapped on Settle up (M8). The route does the work, so the screen stays a picture of the model. */
sealed interface SettleAction {
    data class Copy(val row: PaymentRow) : SettleAction
    data class Call(val row: PaymentRow) : SettleAction
    data class IPaid(val row: PaymentRow) : SettleAction
    data class Reminder(val row: PaymentRow) : SettleAction
    data class MarkReceived(val row: PaymentRow) : SettleAction
    data class Confirm(val id: owntally.model.EventId) : SettleAction
    data class NotReceived(val row: WaitingRow) : SettleAction
    data class CancelDuplicate(val id: owntally.model.EventId) : SettleAction
}

@Composable
private fun ActionButton(text: String, modifier: Modifier, filled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val colour = if (danger) OwnTallyColors.Red else OwnTallyColors.Green
    Box(
        modifier.height(48.dp)
            .background(if (filled) colour else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(24.dp))
            .border(BorderStroke(1.dp, colour), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (filled) OwnTallyColors.OnDark else colour) }
}

@Composable
private fun PersonLine(title: String, subtitle: String?, amount: String, amountColour: androidx.compose.ui.graphics.Color, colourIndex: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(OwnTallyColors.GroupPastels[colourIndex % OwnTallyColors.GroupPastels.size], CircleShape), contentAlignment = Alignment.Center) {
            Text(title.take(1), style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
        }
        Text(amount, style = MaterialTheme.typography.titleLarge, color = amountColour)
    }
}

@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(OwnTallyColors.Card, cardShape).border(BorderStroke(1.dp, OwnTallyColors.CardBorder), cardShape).padding(16.dp)) { content() }
    Spacer(Modifier.height(12.dp))
}

/**
 * Settle up (design/04): the plan with what each person can do on it (M8), payments waiting to be confirmed, payments the receiver did not
 * accept, and the receiver's fix for a payment recorded twice. [onAction] is told what was tapped. [notReceivedAvailable] shows Not received
 * (it opens the screen of design/15).
 */
@Composable
fun SettleUpScreen(model: SettleUpModel, onBack: () -> Unit, onAction: (SettleAction) -> Unit = {}, notReceivedAvailable: Boolean = true) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 24.dp)) {
        item {
            BackHeader("Settle up", model.subtitle, onBack)
            Spacer(Modifier.height(12.dp))
            Text(SettleUpModel.INTRO, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.Ink)
            Spacer(Modifier.height(20.dp))
            for (d in model.duplicates) {
                Column(Modifier.fillMaxWidth().background(OwnTallyColors.WarningBackground, cardShape).padding(16.dp)) {
                    Text(d.message, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.WarningText)
                    Spacer(Modifier.height(12.dp))
                    ActionButton(d.buttonLabel, Modifier.fillMaxWidth(), filled = true) { onAction(SettleAction.CancelDuplicate(d.payerEntry)) }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (model.payments.isNotEmpty()) {
                Text("PAYMENTS TO MAKE", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
            }
        }
        items(model.payments, key = { "pay" + it.from.toHex() + it.to.toHex() }) { p ->
            CardBox {
                PersonLine(p.title, p.phone, p.amount, if (p.role == PaymentRole.FROM_ME) OwnTallyColors.Red else OwnTallyColors.Green, if (p.role == PaymentRole.FROM_ME) 1 else 3)
                val top = p.actions.filter { it == PaymentAction.COPY_NUMBER || it == PaymentAction.CALL || it == PaymentAction.SEND_REMINDER }
                val main = p.actions.filter { it == PaymentAction.I_PAID || it == PaymentAction.MARK_RECEIVED }
                if (p.actions.isNotEmpty()) Spacer(Modifier.height(12.dp))
                if (p.role == PaymentRole.TO_ME) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton("Send reminder", Modifier.weight(1f), filled = false) { onAction(SettleAction.Reminder(p)) }
                        ActionButton("Mark received", Modifier.weight(1f), filled = true) { onAction(SettleAction.MarkReceived(p)) }
                    }
                } else if (p.role == PaymentRole.FROM_ME) {
                    if (top.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (PaymentAction.COPY_NUMBER in top) ActionButton("Copy number", Modifier.weight(1f), filled = false) { onAction(SettleAction.Copy(p)) }
                            if (PaymentAction.CALL in top) ActionButton("Call", Modifier.weight(1f), filled = false) { onAction(SettleAction.Call(p)) }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    if (main.isNotEmpty()) ActionButton("I paid", Modifier.fillMaxWidth(), filled = true) { onAction(SettleAction.IPaid(p)) }
                }
            }
        }
        if (model.waiting.isNotEmpty()) item {
            Spacer(Modifier.height(8.dp))
            Text("WAITING FOR CONFIRMATION", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
            Spacer(Modifier.height(10.dp))
        }
        items(model.waiting, key = { "wait" + it.id.author.toHex() + it.id.counter }) { w ->
            CardBox {
                PersonLine(w.title, null, w.amount, OwnTallyColors.Green, 3)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().background(OwnTallyColors.WarningBackground, RoundedCornerShape(16.dp)).padding(12.dp)) {
                    Text(w.message, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.WarningText)
                }
                if (w.canRespond) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (notReceivedAvailable) ActionButton("Not received", Modifier.weight(1f), filled = false, danger = true) { onAction(SettleAction.NotReceived(w)) }
                        ActionButton("Confirm received", Modifier.weight(1f), filled = true) { onAction(SettleAction.Confirm(w.id)) }
                    }
                }
            }
        }
        if (model.disputed.isNotEmpty()) item {
            Spacer(Modifier.height(8.dp))
            Text("NOT ACCEPTED", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
            Spacer(Modifier.height(10.dp))
        }
        items(model.disputed, key = { "disp" + it.id.author.toHex() + it.id.counter }) { d ->
            CardBox {
                PersonLine(d.title, null, d.amount, OwnTallyColors.TextSecondary, 3)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().background(OwnTallyColors.WarningBackground, RoundedCornerShape(16.dp)).padding(12.dp)) {
                    Text(d.message, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.WarningText)
                }
                if (d.canConfirm) {
                    Spacer(Modifier.height(12.dp))
                    ActionButton("Confirm received", Modifier.fillMaxWidth(), filled = true) { onAction(SettleAction.Confirm(d.id)) }
                }
            }
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
            Notice(SettleUpModel.NOTE, background = OwnTallyColors.InfoBackground, content = OwnTallyColors.Green)
        }
    }
}

/** "Payment not received" (design/15 and 15b). The reason is chosen here; [onTell] writes it. */
@Composable
fun NotReceivedScreen(model: NotReceivedModel, onBack: () -> Unit, onTell: (DisputeReason) -> Unit, message: String? = null) {
    var chosen by remember { mutableStateOf(DisputeReason.NOTHING_RECEIVED) }
    Column(Modifier.fillMaxSize().padding(20.dp, 8.dp, 20.dp, 24.dp)) {
        BackHeader(model.screenTitle, null, onBack)
        Spacer(Modifier.height(16.dp))
        when (model) {
            is NotReceivedModel.Unavailable -> {
                Text(model.message, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.Ink)
                Spacer(Modifier.weight(1f))
                ActionButton("Back to settle up", Modifier.fillMaxWidth(), filled = true, onClick = onBack)
            }
            is NotReceivedModel.Form -> {
                CardBox { PersonLine(model.title, model.markedAgo, model.amount, OwnTallyColors.Ink, 3) }
                Spacer(Modifier.height(8.dp))
                Text("WHAT HAPPENED?", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                for (r in model.reasons) {
                    val selected = chosen == r.reason
                    Row(
                        Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(20.dp))
                            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) OwnTallyColors.Green else OwnTallyColors.CardBorder), RoundedCornerShape(20.dp))
                            .clickable { chosen = r.reason }.padding(16.dp)
                            .semantics(mergeDescendants = true) { role = Role.RadioButton; this.selected = selected },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(24.dp).border(BorderStroke(2.dp, if (selected) OwnTallyColors.Green else OwnTallyColors.Border), CircleShape), contentAlignment = Alignment.Center) {
                            if (selected) Box(Modifier.size(12.dp).background(OwnTallyColors.Green, CircleShape))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(r.label, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.Ink)
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(model.explanation, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                if (message != null) { Spacer(Modifier.height(12.dp)); Notice(message) }
                Spacer(Modifier.weight(1f))
                ActionButton(model.tellLabel, Modifier.fillMaxWidth(), filled = true, danger = true) { onTell(chosen) }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onBack).semantics { role = Role.Button }, contentAlignment = Alignment.Center) {
                    Text("Cancel", style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Green)
                }
            }
            is NotReceivedModel.Sent -> {
                CardBox { PersonLine(model.title, model.markedAgo, model.amount, OwnTallyColors.Ink, 3) }
                Column(Modifier.fillMaxWidth().background(OwnTallyColors.WarningBackground, cardShape).padding(18.dp)) {
                    Text(model.bannerTitle, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.WarningText)
                    Spacer(Modifier.height(6.dp))
                    Text(model.bannerBody, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.WarningText)
                }
                Spacer(Modifier.height(12.dp))
                CardBox { Text(model.tip, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary) }
                Text(model.laterNote, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.weight(1f))
                ActionButton(model.backLabel, Modifier.fillMaxWidth(), filled = true, onClick = onBack)
            }
        }
    }
}

/** I paid and Mark received open this: the amount, filled with what the plan asks for and free to change (decisions P2 and P5). */
@Composable
fun AmountDialog(title: String, planned: Money, onCancel: () -> Unit, onConfirm: (Money) -> Unit) {
    var typed by remember { mutableStateOf(MoneyFormat.editable(planned)) }
    val check = remember(typed) { AmountBoxState.check(typed, planned) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column {
                Text("Change it if the amount was different.", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = typed, onValueChange = { typed = it.take(16) }, singleLine = true, isError = check.error != null,
                    prefix = { Text("₹") },
                    supportingText = { val m = check.error ?: check.warning; if (m != null) Text(m) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Amount" },
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { check.money?.let(onConfirm) }, enabled = check.canConfirm) { Text("Save") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancel") } },
    )
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
