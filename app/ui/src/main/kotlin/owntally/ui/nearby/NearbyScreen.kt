package owntally.ui.nearby

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import owntally.ui.components.Notice
import owntally.ui.components.PrimaryButton
import owntally.ui.theme.OwnTallyColors

/** What stands between the person and Nearby running: nothing, the explanation before the system asks, or "you said no". */
enum class NearbyGate { READY, EXPLAIN, DENIED }

/**
 * The Nearby screen: opened from the group's Sync screen, runs only while on and only after Start. Before the system asks for any permission
 * the screen says why ([gate] EXPLAIN); if the person refused for good it points to the phone's settings ([gate] DENIED).
 */
@Composable
fun NearbyScreen(
    model: NearbyScreenModel, gate: NearbyGate, explanation: String,
    onBack: () -> Unit, onContinue: () -> Unit, onOpenSettings: () -> Unit, onStart: () -> Unit, onStop: () -> Unit, onLookAgain: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp, 8.dp, 20.dp, 20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onBack).semantics { contentDescription = "Back"; role = SemanticsRole.Button }, contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.BACK, OwnTallyColors.Ink)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Nearby", style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink)
                    Text(model.groupName, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(model.intro, style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
            Spacer(Modifier.height(18.dp))
            when (gate) {
                NearbyGate.EXPLAIN -> Notice(explanation, background = OwnTallyColors.InfoBackground, content = OwnTallyColors.TextSecondary)
                NearbyGate.DENIED -> Notice("Nearby cannot look for friends without the permission it asked for. You can allow it in your phone's settings for OwnTally.")
                NearbyGate.READY -> {
                    Text(model.headline, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Ink)
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (line in model.lines) {
                            Row(
                                Modifier.fillMaxWidth().background(OwnTallyColors.Card, RoundedCornerShape(20.dp)).border(1.dp, OwnTallyColors.CardBorder, RoundedCornerShape(20.dp)).padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(40.dp).background(OwnTallyColors.GroupPastels[(line.key.bytes()[0].toInt() and 0xff) % OwnTallyColors.GroupPastels.size], CircleShape), contentAlignment = Alignment.Center) {
                                    Text(line.initial, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.OnPastel)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    line.text, style = MaterialTheme.typography.bodyLarge,
                                    color = when (line.tone) { NearbyTone.PROBLEM -> OwnTallyColors.Red; NearbyTone.GOOD -> OwnTallyColors.Accent; else -> OwnTallyColors.Ink },
                                )
                            }
                        }
                    }
                }
            }
            if (model.note != null && gate == NearbyGate.READY) {
                Spacer(Modifier.height(14.dp))
                Text(model.note, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        when (gate) {
            NearbyGate.EXPLAIN -> PrimaryButton("Continue", onContinue, Modifier.fillMaxWidth())
            NearbyGate.DENIED -> PrimaryButton("Open settings", onOpenSettings, Modifier.fillMaxWidth())
            NearbyGate.READY -> {
                if (model.startLabel != null) PrimaryButton(model.startLabel, onStart, Modifier.fillMaxWidth())
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (model.lookAgainLabel != null) PrimaryButton(model.lookAgainLabel, onLookAgain, Modifier.fillMaxWidth())
                    if (model.stopLabel != null) OutlinedButton(
                        onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(27.dp), border = BorderStroke(1.dp, OwnTallyColors.Red),
                    ) { Text(model.stopLabel, style = MaterialTheme.typography.titleMedium, color = OwnTallyColors.Red) }
                }
            }
        }
    }
}
