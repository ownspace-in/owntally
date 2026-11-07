package owntally.ui.lock

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.components.Notice
import owntally.ui.components.PrimaryButton
import owntally.ui.components.Wordmark
import owntally.ui.theme.OwnTallyColors

/**
 * Holds the lock state for the screens: the state machine, the stored mode, and the phone's lock screen. Changes happen on the
 * main thread; saving the mode is done off it.
 */
class LockController(
    initialMode: LockMode,
    private val store: LockSettingStore,
    val authenticator: PhoneAuthenticator,
    private val nowMillis: () -> Long,
) {
    var state by mutableStateOf(LockState.start(initialMode))
        private set

    /** Goes up whenever the phone's lock should be looked at again (coming back to the app, or "Check again"), because the state alone may not change. */
    var refreshCount by mutableStateOf(0)
        private set

    fun refresh() { refreshCount++ }

    fun backgrounded() { state = state.backgrounded(nowMillis()) }
    fun foregrounded() { state = state.foregrounded(nowMillis()); refresh() }
    fun unlocked() { state = state.unlocked() }
    fun phoneLocked() { state = state.phoneLocked() }

    /** Sets and stores the mode. */
    suspend fun setMode(mode: LockMode) {
        withContext(Dispatchers.IO) { store.save(mode) }
        state = state.withMode(mode)
    }
}

/** Shows [content] only while the app is not locked. While locked, nothing of the person's data is composed. */
@Composable
fun LockHost(controller: LockController, content: @Composable () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.backgrounded()
                Lifecycle.Event.ON_START -> controller.foregrounded()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // The phone locking (its screen going off) locks the app at once.
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(context, controller) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, intent: android.content.Intent) = controller.phoneLocked()
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    val availability = remember(controller.refreshCount) { controller.authenticator.availability() }
    if (LockGate.mustAsk(controller.state, availability)) LockScreen(controller, availability) else content()
}

@Composable
private fun LockScreen(controller: LockController, availability: PhoneLockAvailability) {
    var asking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun ask() {
        if (asking) return
        asking = true
        controller.authenticator.authenticate("Unlock OwnTally") { ok ->
            asking = false
            if (ok) controller.unlocked() else failed = true
        }
    }
    val canAsk = LockGate.canAsk(availability)
    LaunchedEffect(canAsk) { if (canAsk) ask() }
    Column(
        Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Wordmark()
        Spacer(Modifier.height(28.dp))
        Text("OwnTally is locked", style = MaterialTheme.typography.headlineMedium, color = OwnTallyColors.Ink)
        Spacer(Modifier.height(10.dp))
        Text(LockGate.lockScreenText(availability), style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
        if (failed && canAsk) {
            Spacer(Modifier.height(14.dp))
            Notice("That did not unlock it. Try again.")
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(if (canAsk) "Unlock" else "Check again", { failed = false; if (canAsk) ask() else controller.refresh() }, Modifier.fillMaxWidth())
    }
}

@Composable
fun SettingsScreen(controller: LockController, onBack: () -> Unit, leftGroups: List<LeftGroupRow> = emptyList(), onDeleteLeft: (owntally.model.GroupId) -> Unit = {}) {
    val model = SettingsModel(controller.state.mode, controller.authenticator.availability())
    var message by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun choose(target: LockMode) {
        message = null
        when (val change = model.choose(target)) {
            ModeChange.Nothing -> Unit
            ModeChange.Refused -> message = model.phoneNote
            is ModeChange.SetNow -> scope.launch { controller.setMode(change.mode) }
            is ModeChange.AskThenSet -> controller.authenticator.authenticate("Confirm it's you") { ok ->
                if (ok) scope.launch { controller.setMode(change.mode) } else message = "The lock was not changed."
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp, 8.dp, 20.dp, 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = "Back"; role = Role.Button }, contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.BACK, OwnTallyColors.Ink)
            }
            Spacer(Modifier.width(8.dp))
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = OwnTallyColors.Ink)
        }
        Spacer(Modifier.height(20.dp))
        Text("APP LOCK", style = MaterialTheme.typography.labelMedium, color = OwnTallyColors.TextSecondary)
        Spacer(Modifier.height(10.dp))
        ModeRow("Off", "Anyone who opens your phone can open OwnTally.", model.mode == LockMode.OFF, true) { choose(LockMode.OFF) }
        Spacer(Modifier.height(10.dp))
        ModeRow(
            "Phone lock", "Ask for your fingerprint or screen lock when OwnTally opens, when your phone locks, and after 30 seconds away.",
            model.mode == LockMode.PHONE, model.canChoosePhoneLock || model.mode == LockMode.PHONE,
        ) { choose(LockMode.PHONE) }
        val note = message ?: model.phoneNote
        if (note != null) {
            Spacer(Modifier.height(14.dp))
            Notice(note)
        }
        LeftGroupsSection(leftGroups, onDeleteLeft)
    }
}

@Composable
private fun ModeRow(title: String, body: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().background(OwnTallyColors.Card, shape)
            .border(BorderStroke(if (on) 2.dp else 1.dp, if (on) OwnTallyColors.Green else OwnTallyColors.CardBorder), shape)
            .clickable(onClick = onClick).padding(16.dp)
            .semantics(mergeDescendants = true) { role = Role.RadioButton; selected = on; contentDescription = title },
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 2.dp).size(22.dp).border(BorderStroke(2.dp, if (on) OwnTallyColors.Green else OwnTallyColors.TextHint), CircleShape), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(10.dp).background(OwnTallyColors.Green, CircleShape))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, color = if (enabled) OwnTallyColors.Ink else OwnTallyColors.TextHint)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.TextSecondary)
        }
    }
}
