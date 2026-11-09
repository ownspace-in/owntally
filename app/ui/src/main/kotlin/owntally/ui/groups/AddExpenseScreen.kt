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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import owntally.model.PublicKey
import owntally.model.format.DateText
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.components.Notice
import owntally.ui.components.PrimaryButton
import owntally.ui.theme.OwnTallyColors

private val card = RoundedCornerShape(24.dp)

@Composable
private fun Boxed(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.background(OwnTallyColors.Card, card).border(BorderStroke(1.dp, OwnTallyColors.CardBorder), card).padding(16.dp),
    ) { content() }
}

@Composable
private fun PlainField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, decimal: Boolean = false, large: Boolean = false, align: TextAlign = TextAlign.Start) {
    val style = (if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.bodyLarge).copy(color = OwnTallyColors.Ink, textAlign = align)
    BasicTextField(
        value, onChange, modifier, singleLine = true, textStyle = style, cursorBrush = SolidColor(OwnTallyColors.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text),
        decorationBox = { inner ->
            Box { if (value.isEmpty()) Text(hint, style = style.copy(color = OwnTallyColors.TextHint)); inner() }
        },
    )
}

@Composable
fun AddExpenseScreen(
    model: AddExpenseModel,
    groupName: String,
    onChange: (AddExpenseModel) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    error: String?,
    saving: Boolean,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp, 8.dp, 20.dp, 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable(onClick = onClose).semantics { contentDescription = "Close"; role = Role.Button }, contentAlignment = Alignment.Center) {
                    CrossGlyph()
                }
                Spacer(Modifier.width(8.dp))
                Text("Add expense", style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink, modifier = Modifier.weight(1f))
                Text(groupName, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary, maxLines = 1)
            }
            Spacer(Modifier.height(14.dp))
            Boxed(Modifier.fillMaxWidth()) {
                Text("Amount", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("₹", style = MaterialTheme.typography.headlineLarge, color = OwnTallyColors.TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    PlainField(model.amountText, { onChange(model.withAmount(it)) }, "0", Modifier.fillMaxWidth().semantics { contentDescription = "Amount" }, decimal = true, large = true)
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(OwnTallyColors.CardBorder))
                Spacer(Modifier.height(12.dp))
                Text("What was it for?", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                PlainField(model.description, { onChange(model.withDescription(it)) }, "For example, Seafood lunch", Modifier.fillMaxWidth().semantics { contentDescription = "What was it for?" })
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PaidByCard(model, onChange, Modifier.weight(1f))
                DateCard(model, onChange, Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SPLIT", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary, modifier = Modifier.weight(1f))
                ModeSwitch(model.mode) { onChange(model.withMode(it)) }
            }
            Spacer(Modifier.height(10.dp))
            for (p in model.people) PersonRow(model, p.key, p.label, onChange)
            if (model.mode == SplitMode.EXACT && model.exactStatus != null) {
                Spacer(Modifier.height(4.dp))
                Text(model.exactStatus!!, style = MaterialTheme.typography.labelMedium, color = if (model.exactStatus == "All assigned") OwnTallyColors.Accent else OwnTallyColors.Red)
            }
            val message = error ?: model.problem
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Notice(message)
            }
        }
        Column(Modifier.fillMaxWidth().background(OwnTallyColors.Background).padding(20.dp, 10.dp, 20.dp, 16.dp)) {
            Text("Saved on this phone. Entries can't be edited later, only deleted.", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Save expense", onSave, Modifier.fillMaxWidth(), enabled = model.canSave && !saving, loading = saving)
        }
    }
}

@Composable
private fun CrossGlyph() {
    val c = OwnTallyColors.Ink
    androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
        val w = size.width
        drawLine(c, androidx.compose.ui.geometry.Offset(w * .25f, w * .25f), androidx.compose.ui.geometry.Offset(w * .75f, w * .75f), w * .085f, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(c, androidx.compose.ui.geometry.Offset(w * .75f, w * .25f), androidx.compose.ui.geometry.Offset(w * .25f, w * .75f), w * .085f, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

@Composable
private fun PaidByCard(model: AddExpenseModel, onChange: (AddExpenseModel) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Boxed(modifier.clickable { open = true }.semantics { role = Role.DropdownList; contentDescription = "Paid by ${model.labelOf(model.paidBy)}" }) {
        Text("Paid by", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Initial(model.labelOf(model.paidBy))
            Spacer(Modifier.width(8.dp))
            Text(model.labelOf(model.paidBy), style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink, maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            for (p in model.people) DropdownMenuItem(text = { Text(p.label) }, onClick = { open = false; onChange(model.withPaidBy(p.key)) })
        }
    }
}

@Composable
private fun Initial(label: String) {
    Box(Modifier.size(28.dp).background(OwnTallyColors.GroupPastels[0], androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
        Text(label.take(1).uppercase(), style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.OnPastel)
    }
}

@Composable
private fun DateCard(model: AddExpenseModel, onChange: (AddExpenseModel) -> Unit, modifier: Modifier) {
    Boxed(modifier) {
        Text("Date", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clickable { onChange(model.earlier()) }.semantics { contentDescription = "Earlier day"; role = Role.Button }, contentAlignment = Alignment.Center) { GlyphIcon(Glyph.BACK, OwnTallyColors.Ink, 20.dp) }
            Text(DateText.format(model.day, model.today), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.Ink, textAlign = TextAlign.Center)
            val canLater = model.day < model.today
            Box(
                Modifier.size(32.dp).then(if (canLater) Modifier.clickable { onChange(model.later()) } else Modifier).semantics { contentDescription = "Later day"; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.BACK, if (canLater) OwnTallyColors.Ink else OwnTallyColors.Border, 20.dp, Modifier.rotate(180f))
            }
        }
    }
}

@Composable
private fun ModeSwitch(mode: SplitMode, onSelect: (SplitMode) -> Unit) {
    Row(Modifier.background(OwnTallyColors.InfoBackground, RoundedCornerShape(24.dp)).padding(4.dp)) {
        for ((m, text) in listOf(SplitMode.EQUAL to "Equally", SplitMode.EXACT to "Exact ₹")) {
            val on = m == mode
            Box(
                Modifier.background(if (on) OwnTallyColors.Green else Color.Transparent, RoundedCornerShape(20.dp)).clickable { onSelect(m) }
                    .padding(horizontal = 16.dp, vertical = 8.dp).semantics { role = Role.RadioButton; selected = on },
            ) { Text(text, style = MaterialTheme.typography.labelMedium, color = if (on) OwnTallyColors.OnDark else OwnTallyColors.Ink) }
        }
    }
}

@Composable
private fun PersonRow(model: AddExpenseModel, key: PublicKey, label: String, onChange: (AddExpenseModel) -> Unit) {
    val exact = model.mode == SplitMode.EXACT
    val on = key in model.selected
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).background(OwnTallyColors.Card, card).border(BorderStroke(1.dp, OwnTallyColors.CardBorder), card)
            .then(if (!exact) Modifier.clickable { onChange(model.toggle(key)) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { if (!exact) { role = Role.Checkbox; selected = on } ; contentDescription = "Split with $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!exact) {
            Box(
                Modifier.size(26.dp).background(if (on) OwnTallyColors.Green else Color.Transparent, RoundedCornerShape(8.dp))
                    .border(BorderStroke(2.dp, if (on) OwnTallyColors.Accent else OwnTallyColors.TextHint), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { if (on) Text("✓", color = Color.White, style = MaterialTheme.typography.labelMedium) }
            Spacer(Modifier.width(14.dp))
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.Ink, maxLines = 1)
        if (exact) {
            Text("₹", color = OwnTallyColors.TextSecondary)
            Spacer(Modifier.width(4.dp))
            PlainField(
                model.exactText[key].orEmpty(), { onChange(model.withExact(key, it)) }, "0",
                Modifier.width(110.dp).semantics { contentDescription = "Amount for $label" }, decimal = true, align = TextAlign.End,
            )
        } else {
            Text(model.shareTexts[key] ?: "–", style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.Ink)
        }
    }
}
