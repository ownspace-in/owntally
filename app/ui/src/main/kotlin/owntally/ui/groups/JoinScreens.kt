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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.components.Notice
import owntally.ui.components.OwnTallyField
import owntally.ui.components.PrimaryButton
import owntally.ui.theme.OwnTallyColors

/** The dark card of design/09 and 09b: "You were invited" and the group. */
@Composable
private fun InvitedCard(heading: String, initials: String, title: String, subtitle: String, expiry: String) {
    Text(heading, style = MaterialTheme.typography.labelLarge, color = OwnTallyColors.TextSecondary)
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().background(OwnTallyColors.Hero, RoundedCornerShape(28.dp)).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).background(OwnTallyColors.GroupPastels[0], RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Text(initials, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Ink)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.OnDark)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.OnDarkMuted)
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(expiry, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
}

@Composable
private fun BackRow(title: String?, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = "Back"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.BACK, OwnTallyColors.Ink)
        }
        if (title != null) {
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink)
        }
    }
}

/** "Join a group": paste the invite link or code (decision L4: no camera scanning in this version). [problem] is shown under the field. */
@Composable
fun PasteInviteScreen(text: String, onText: (String) -> Unit, problem: String?, busy: Boolean, onPaste: () -> Unit, onContinue: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp, 8.dp, 20.dp, 20.dp)) {
        BackRow("Join a group", onBack)
        Spacer(Modifier.height(16.dp))
        Text("Paste the invite link or code a friend sent you.", style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = text, onValueChange = onText, modifier = Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "Invite link or code" },
            placeholder = { Text("https://joinowntally.ownspace.in/j#…", color = OwnTallyColors.TextHint) }, isError = problem != null,
            shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = OwnTallyColors.Card, unfocusedContainerColor = OwnTallyColors.Card, errorContainerColor = OwnTallyColors.Card,
                focusedBorderColor = OwnTallyColors.Accent, unfocusedBorderColor = OwnTallyColors.Border, errorBorderColor = OwnTallyColors.Red, cursorColor = OwnTallyColors.Accent,
            ),
        )
        Spacer(Modifier.height(10.dp))
        Text("Paste from clipboard", style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Accent, modifier = Modifier.clickable(onClick = onPaste).padding(8.dp).semantics { role = SemanticsRole.Button })
        if (problem != null) {
            Spacer(Modifier.height(6.dp))
            Text(problem, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.Red)
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Continue", onContinue, Modifier.fillMaxWidth(), enabled = text.isNotBlank(), loading = busy)
    }
}

/** "You were invited" (design/09). */
@Composable
fun JoinFormScreen(
    model: JoinFormModel, name: String, phone: String, onName: (String) -> Unit, onPhone: (String) -> Unit,
    problem: String?, busy: Boolean, onRequest: () -> Unit, onBack: () -> Unit, onTryAgain: (() -> Unit)?, onDiscard: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp, 8.dp, 20.dp, 20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            BackRow(null, onBack)
            InvitedCard(model.heading, model.initials, model.title, model.subtitle, model.expiry)
            if (model.unreachableNote != null) {
                Spacer(Modifier.height(14.dp))
                Notice(model.unreachableNote)
                if (onTryAgain != null) Text("Try to reach them again", style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Accent, modifier = Modifier.clickable(onClick = onTryAgain).padding(8.dp).semantics { role = SemanticsRole.Button })
            }
            if (problem != null) {
                Spacer(Modifier.height(14.dp))
                Notice(problem)
            }
            Spacer(Modifier.height(24.dp))
            OwnTallyField("Your name in this group", name, onName, placeholder = "Rohan", error = model.shownNameError)
            Spacer(Modifier.height(16.dp))
            OwnTallyField("Phone number", phone, onPhone, placeholder = "+91 98765 43210", error = model.shownPhoneError, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            Spacer(Modifier.height(14.dp))
            Text(model.privacyNote, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton(model.button, onRequest, Modifier.fillMaxWidth(), enabled = !busy, loading = busy)
        Spacer(Modifier.height(8.dp))
        Text(model.footnote, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary, modifier = Modifier.fillMaxWidth())
        Text("Not for me", style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Red, modifier = Modifier.clickable(onClick = onDiscard).padding(8.dp).semantics { role = SemanticsRole.Button })
    }
}

/** "Waiting for approval" (design/09b), and "You're in" and "Your request was declined". */
@Composable
fun JoinWaitingScreen(model: JoinWaitingModel, note: String?, busy: Boolean, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp, 8.dp, 20.dp, 20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            BackRow(null, onBack)
            InvitedCard("You were invited", model.initials, model.groupTitle, model.subtitle, model.expiry)
            Spacer(Modifier.height(36.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).background(OwnTallyColors.InfoBackground, CircleShape), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.SYNC, OwnTallyColors.Accent, 32.dp)
                }
                Spacer(Modifier.height(18.dp))
                Text(model.title, style = MaterialTheme.typography.headlineMedium, color = OwnTallyColors.Ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(model.body, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            if (note != null) {
                Spacer(Modifier.height(16.dp))
                Notice(note)
            }
            if (model.helpTitle != null) {
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(20.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(model.helpTitle, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Ink)
                    for (line in model.helpLines) Text(line, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                }
            }
        }
        if (model.retry != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, OwnTallyColors.Accent)) {
                Text(if (busy) "Checking…" else model.retry, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Accent)
            }
        }
    }
}
