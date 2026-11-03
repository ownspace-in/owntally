package owntally.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import owntally.data.session.GroupSession
import owntally.data.session.Sessions
import owntally.model.GroupId
import owntally.model.format.BalanceKind
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.theme.OwnTallyColors

/** Things only debug builds have (test friends and sample expenses). Release builds pass null, so no such control is drawn. */
interface DebugTools {
    fun addTestData(session: GroupSession)
}

/** The screens after first run: the four tabs, and the Group and New group screens above them. */
@Composable
fun HomeFlow(sessions: Sessions, debugTools: DebugTools? = null) {
    val nav = rememberNavController()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { sessions.refresh() } }
    Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) {
        NavHost(nav, startDestination = "home") {
            composable("home") {
                HomeTabs(sessions, onOpen = { nav.navigate("group/${it.toHex()}") }, onCreate = { nav.navigate("create") })
            }
            composable("create") {
                CreateGroupRoute(sessions, onDone = { nav.popBackStack() })
            }
            composable("group/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.let { GroupId.fromHexOrNull(it) }
                if (id == null) LaunchedEffect(Unit) { nav.popBackStack() } else GroupRoute(sessions, id, debugTools, onBack = { nav.popBackStack() })
            }
        }
    }
}

@Composable
private fun HomeTabs(sessions: Sessions, onOpen: (GroupId) -> Unit, onCreate: () -> Unit) {
    val ui: HomeViewModel = viewModel()
    val tab = ui.tab
    val summaries by sessions.summaries.collectAsStateWithLifecycle()
    val model = remember(summaries) { GroupsModels.list(summaries) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (Tab.entries[tab]) {
                Tab.GROUPS -> GroupsScreen(model, onOpen = { onOpen(it.id) }, onCreate = onCreate)
                Tab.SETTLE -> SettleTab(model, onOpen)
                Tab.SYNC -> MessageTab("Sync", SyncPlaceholder.TEXT)
                Tab.ABOUT -> MessageTab(
                    "About OwnTally",
                    "OwnTally splits expenses with friends. There is no account and no server of ours: your groups are stored on this phone, and each group's data is encrypted with its own key.",
                )
            }
        }
        BottomBar(Tab.entries[tab]) { ui.tab = it.ordinal }
    }
}

@Composable
private fun SettleTab(model: GroupsListModel, onOpen: (GroupId) -> Unit) {
    val open = model.cards.filter { it.readable && it.balance.kind != BalanceKind.SETTLED }
    if (open.isEmpty()) {
        MessageTab("Nothing to settle", "All your groups are settled up.")
        return
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settle", style = MaterialTheme.typography.headlineMedium, color = OwnTallyColors.Ink)
        for (card in open) GroupCard(card) { onOpen(card.id) }
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(OwnTallyColors.Card)) {
        HorizontalDivider(color = OwnTallyColors.CardBorder)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            for (t in Tab.entries) {
                val on = t == selected
                val colour = if (on) OwnTallyColors.Green else OwnTallyColors.TextSecondary
                Column(
                    Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 6.dp)
                        .semantics(mergeDescendants = true) { role = Role.Tab; this.selected = on; contentDescription = t.label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GlyphIcon(
                        when (t) { Tab.GROUPS -> Glyph.GROUPS; Tab.SETTLE -> Glyph.SETTLE; Tab.SYNC -> Glyph.SYNC; Tab.ABOUT -> Glyph.ABOUT },
                        colour,
                    )
                    Text(t.label, style = MaterialTheme.typography.labelMedium, color = colour)
                }
            }
        }
    }
}

@Composable
private fun CreateGroupRoute(sessions: Sessions, onDone: () -> Unit) {
    val ui: HomeViewModel = viewModel()
    val model = ui.createForm
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    CreateGroupScreen(
        model, onChange = { ui.createForm = it },
        onCreate = {
            if (busy || !model.canCreate) return@CreateGroupScreen
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { sessions.createGroup(model.trimmedName, CreateGroupModel.CURRENCY, model.colourIndex) }
                    ui.createForm = CreateGroupModel()
                    withContext(Dispatchers.Main.immediate) { onDone() } // navigation must run on the main thread
                } catch (e: IllegalArgumentException) {
                    error = "That name can't be used. Use 1 to 40 letters."
                } catch (e: Exception) {
                    error = "The group could not be saved on this phone."
                } finally {
                    busy = false
                }
            }
        },
        onBack = onDone, error = error,
    )
}

@Composable
private fun GroupRoute(sessions: Sessions, id: GroupId, debugTools: DebugTools?, onBack: () -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session
    if (s == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val view by s.view.collectAsStateWithLifecycle()
    val model = remember(view) { GroupScreenModels.build(view) }
    val scope = rememberCoroutineScope()
    GroupScreen(model, onBack) {
        if (debugTools != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            owntally.ui.components.TextAction("Add test friends and expenses (debug build)", { scope.launch(Dispatchers.IO) { debugTools.addTestData(s) } })
        }
    }
}

/**
 * Small bits of screen state that survive turning the phone. Kept in memory only, never in saved state: a group name typed here
 * is gone if Android ends the app's process, which is fine for a form.
 */
class HomeViewModel : androidx.lifecycle.ViewModel() {
    var tab by mutableStateOf(Tab.GROUPS.ordinal)
    var createForm by mutableStateOf(CreateGroupModel())
}
