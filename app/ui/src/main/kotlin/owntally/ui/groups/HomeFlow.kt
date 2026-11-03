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
                HomeTabs(sessions, onOpen = { nav.navigate("group/${it.toHex()}") }, onSettle = { nav.navigate("group/${it.toHex()}/settle") }, onHow = { nav.navigate("how") }, onCreate = { nav.navigate("create") })
            }
            composable("create") {
                CreateGroupRoute(sessions, onDone = { nav.popBackStack() })
            }
            composable("how") { HowItWorksScreen(onBack = { nav.popBackStack() }) }
            composable("group/{id}/settle") { entry ->
                val id = entry.arguments?.getString("id")?.let { GroupId.fromHexOrNull(it) }
                if (id == null) LaunchedEffect(Unit) { nav.popBackStack() } else SettleUpRoute(sessions, id, onBack = { nav.popBackStack() })
            }
            composable("group/{id}/add") { entry ->
                val id = entry.arguments?.getString("id")?.let { GroupId.fromHexOrNull(it) }
                if (id == null) LaunchedEffect(Unit) { nav.popBackStack() } else AddExpenseRoute(sessions, id, onDone = { nav.popBackStack() })
            }
            composable("group/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.let { GroupId.fromHexOrNull(it) }
                if (id == null) LaunchedEffect(Unit) { nav.popBackStack() } else GroupRoute(sessions, id, debugTools, onBack = { nav.popBackStack() }, onAdd = { nav.navigate("group/${id.toHex()}/add") }, onSettle = { nav.navigate("group/${id.toHex()}/settle") })
            }
        }
    }
}

@Composable
private fun HomeTabs(sessions: Sessions, onOpen: (GroupId) -> Unit, onSettle: (GroupId) -> Unit, onHow: () -> Unit, onCreate: () -> Unit) {
    val ui: HomeViewModel = viewModel()
    val tab = ui.tab
    val summaries by sessions.summaries.collectAsStateWithLifecycle()
    val model = remember(summaries) { GroupsModels.list(summaries) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (Tab.entries[tab]) {
                Tab.GROUPS -> GroupsScreen(model, onOpen = { onOpen(it.id) }, onCreate = onCreate)
                Tab.SETTLE -> SettleTab(model, onSettle)
                Tab.SYNC -> MessageTab("Sync", SyncPlaceholder.TEXT)
                Tab.ABOUT -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        MessageTab(
                            "About OwnTally",
                            "OwnTally splits expenses with friends. There is no account and no server of ours: your groups are stored on this phone, and each group's data is encrypted with its own key.",
                        )
                    }
                    Box(Modifier.padding(20.dp)) { owntally.ui.components.PrimaryButton("How OwnTally works", onHow, Modifier.fillMaxWidth()) }
                }
            }
        }
        BottomBar(Tab.entries[tab]) { ui.tab = it.ordinal }
    }
}

@Composable
private fun SettleTab(model: GroupsListModel, onOpen: (GroupId) -> Unit) {
    val open = GroupsModels.settleTabCards(model.cards)
    if (open.isEmpty()) {
        MessageTab("Settle", "You have no groups yet. Make a group on the Groups tab first.")
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
private fun GroupRoute(sessions: Sessions, id: GroupId, debugTools: DebugTools?, onBack: () -> Unit, onAdd: () -> Unit, onSettle: () -> Unit) {
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
    var opened by remember { mutableStateOf<owntally.model.EventId?>(null) }
    GroupScreen(model, onBack, onAdd, onSettle, onOpenExpense = { opened = it.id }) {
        if (debugTools != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            owntally.ui.components.TextAction("Add test friends and expenses (debug build)", { scope.launch { withContext(Dispatchers.IO) { debugTools.addTestData(s) } } })
        }
    }
    val target = opened?.let { view.state.expenses[it] }
    if (target != null) {
        ExpenseDialog(view, target, onClose = { opened = null }, onDelete = {
            opened = null
            scope.launch { withContext(Dispatchers.IO) { s.deleteExpense(target.id) } }
        })
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

@Composable
private fun ExpenseDialog(view: owntally.data.session.GroupView, e: owntally.model.Expense, onClose: () -> Unit, onDelete: () -> Unit) {
    val row = GroupScreenModels.build(view).expenses.first { it.id == e.id }
    val canDelete = DeleteRules.canDelete(view, e)
    var confirming by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (confirming) "Delete this expense?" else e.description) },
        text = {
            Column {
                if (!confirming) {
                    Text(row.subtitle)
                    if (row.caption != null) Text("${row.caption} ${row.amount}")
                    Text("Entries can't be edited. To fix a mistake, delete it and add it again.", Modifier.padding(top = 8.dp))
                } else {
                    Text("It stays in the list, struck through, and is no longer counted in the balances.")
                    if (DeleteRules.changesSettledAmounts(view, e)) {
                        Text(
                            "This changes balances. A payment in this group already involves someone on this expense, so what people owe each other will change.",
                            Modifier.padding(top = 8.dp), color = OwnTallyColors.Red,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!confirming && canDelete) owntally.ui.components.TextAction("Delete", { confirming = true })
            else if (confirming) owntally.ui.components.TextAction("Delete expense", onDelete)
            else owntally.ui.components.TextAction("Close", onClose)
        },
        dismissButton = { if (canDelete || confirming) owntally.ui.components.TextAction(if (confirming) "Keep it" else "Close", onClose) },
    )
}

/** The form lives here so it survives turning the phone. Memory only, never saved state. */
class AddExpenseViewModel : androidx.lifecycle.ViewModel() {
    var model by mutableStateOf<AddExpenseModel?>(null)
}

@Composable
private fun AddExpenseRoute(sessions: Sessions, id: GroupId, onDone: () -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session ?: return Box(Modifier.fillMaxSize())
    val vm: AddExpenseViewModel = viewModel()
    val view by s.view.collectAsStateWithLifecycle()
    LaunchedEffect(s) {
        // nextCounter() reads the database, so this is done off the main thread.
        if (vm.model == null) vm.model = withContext(Dispatchers.IO) { AddExpenseModel.start(s.view.value, s.nextCounter(), java.time.LocalDate.now().toEpochDay()) }
    }
    val model = vm.model ?: return
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AddExpenseScreen(
        model, view.state.name ?: "", onChange = { vm.model = it; error = null },
        onSave = {
            val split = model.toSplit()
            val total = (owntally.model.format.MoneyInput.parse(model.amountText) as? owntally.model.format.ParsedAmount.Valid)?.money
            if (saving || split == null || total == null || !model.canSave) return@AddExpenseScreen
            saving = true
            scope.launch {
                try {
                    val r = withContext(Dispatchers.IO) { s.addExpense(model.trimmedDescription, total, model.paidBy, model.day, split) }
                    when (r) {
                        is owntally.data.session.AddResult.Added -> { vm.model = null; withContext(Dispatchers.Main.immediate) { onDone() } }
                        is owntally.data.session.AddResult.Refused -> error = "This expense can't be added (${r.reason.name.lowercase().replace('_', ' ')}). Someone may have left the group."
                        owntally.data.session.AddResult.NoKey -> error = "This phone doesn't have the group's key yet."
                    }
                } catch (e: Exception) {
                    error = "The expense could not be saved on this phone."
                } finally {
                    saving = false
                }
            }
        },
        onClose = { vm.model = null; onDone() }, error = error, saving = saving,
    )
}

@Composable
private fun SettleUpRoute(sessions: Sessions, id: GroupId, onBack: () -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session ?: return Box(Modifier.fillMaxSize())
    val view by s.view.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    SettleUpScreen(remember(view) { SettleUpModel.build(view) }, onBack, onShare = { text ->
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
        try { context.startActivity(android.content.Intent.createChooser(send, null)) } catch (e: android.content.ActivityNotFoundException) { }
    })
}
