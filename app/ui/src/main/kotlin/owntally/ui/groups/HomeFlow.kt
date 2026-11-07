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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import owntally.data.session.GroupSession
import owntally.data.session.Sessions
import owntally.model.GroupId
import owntally.ui.NavStack
import owntally.ui.Screen
import owntally.model.format.BalanceKind
import owntally.ui.components.Glyph
import owntally.ui.components.GlyphIcon
import owntally.ui.theme.OwnTallyColors

/** Things only debug builds have (test friends and sample expenses). Release builds pass null, so no such control is drawn. */
/** One debug button: what it says and what it does (run off the main thread). */
class DebugAction(val label: String, val run: () -> Unit)

interface DebugTools {
    fun addTestData(session: GroupSession)

    /** More buttons that depend on the group's state (pretend friends, a pretend join request, acting as a pretend member). */
    fun actions(session: GroupSession): List<DebugAction> = emptyList()
}

/** The screens after first run: the four tabs, and the Group and New group screens above them. */
@Composable
fun HomeFlow(sessions: Sessions, debugTools: DebugTools? = null, lock: owntally.ui.lock.LockController? = null, joinRequests: owntally.data.session.JoinRequests? = null) {
    val ui: HomeViewModel = viewModel()
    val nav = ui.nav
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { sessions.refresh() } }
    androidx.activity.compose.BackHandler(enabled = nav.canGoBack) { ui.nav = nav.pop() }
    val back = { ui.nav = ui.nav.pop() }
    Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) {
        when (val screen = nav.current) {
            Screen.Home -> HomeTabs(
                sessions,
                onOpen = { ui.nav = ui.nav.push(Screen.Group(it)) },
                onSettle = { ui.nav = ui.nav.push(Screen.SettleUp(it)) },
                onHow = { ui.nav = ui.nav.push(Screen.HowItWorks) },
                onSettings = if (lock != null) ({ ui.nav = ui.nav.push(Screen.Settings) }) else null,
                onCreate = { ui.nav = ui.nav.push(Screen.CreateGroup) },
            )
            Screen.CreateGroup -> CreateGroupRoute(sessions, onDone = back)
            Screen.HowItWorks -> HowItWorksScreen(onBack = back)
            Screen.Settings -> if (lock != null) SettingsRoute(sessions, lock, onBack = back) else MessageTab("Settings", "There is nothing to set yet.")
            is Screen.SettleUp -> SettleUpRoute(sessions, screen.id, onBack = back)
            is Screen.Members -> MembersRoute(sessions, joinRequests, screen.id, onBack = back, onMenu = { ui.nav = ui.nav.push(Screen.MemberActions(screen.id, it)) }, onJoinRequests = { ui.nav = ui.nav.push(Screen.JoinRequests(screen.id)) }, onMyProfile = { ui.nav = ui.nav.push(Screen.Profile(screen.id)) }, onLeave = { ui.nav = ui.nav.push(Screen.Leave(screen.id)) }, onRemovedTap = { ui.nav = ui.nav.push(Screen.Cancelled(screen.id, it)) })
            is Screen.Cancelled -> CancelledRoute(
                sessions, screen.id, screen.member, onBack = back,
                onAddAgain = { model -> ui.addExpense = screen.id to model; ui.nav = ui.nav.push(Screen.AddExpense(screen.id)) },
            )
            is Screen.Profile -> ProfileRoute(sessions, screen.id, onBack = back, onLeave = { ui.nav = ui.nav.push(Screen.Leave(screen.id)) })
            is Screen.Leave -> LeaveRoute(sessions, screen.id, onCancel = back, onLeft = { ui.nav = ui.nav.home() })
            is Screen.JoinRequests -> JoinRequestsRoute(sessions, joinRequests, screen.id, onBack = back)
            is Screen.MemberActions -> MemberActionsRoute(sessions, screen.id, screen.member, onClose = back, onRemove = { ui.nav = ui.nav.push(Screen.RemoveMember(screen.id, screen.member)) }, onMakeOwner = { ui.nav = ui.nav.push(Screen.OwnerOffer(screen.id, screen.member)) })
            is Screen.OwnerOffer -> OwnerOfferRoute(sessions, screen.id, screen.member, onClose = back, onChoose = { ui.nav = ui.nav.pop() })
            is Screen.RemoveMember -> RemoveMemberRoute(sessions, screen.id, screen.member, onCancel = back, onRemoved = { ui.nav = ui.nav.pop().pop() })
            is Screen.AddExpense -> AddExpenseRoute(sessions, screen.id, onDone = back)
            is Screen.Group -> GroupRoute(
                sessions, screen.id, debugTools, onBack = back,
                onAdd = { ui.nav = ui.nav.push(Screen.AddExpense(screen.id)) },
                onSettle = { ui.nav = ui.nav.push(Screen.SettleUp(screen.id)) },
                onMembers = { ui.nav = ui.nav.push(Screen.Members(screen.id)) },
                onProfile = { ui.nav = ui.nav.push(Screen.Profile(screen.id)) },
            )
        }
    }
}

@Composable
private fun HomeTabs(sessions: Sessions, onOpen: (GroupId) -> Unit, onSettle: (GroupId) -> Unit, onHow: () -> Unit, onSettings: (() -> Unit)?, onCreate: () -> Unit) {
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
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        owntally.ui.components.PrimaryButton("How OwnTally works", onHow, Modifier.fillMaxWidth())
                        if (onSettings != null) owntally.ui.components.PrimaryButton("Settings", onSettings, Modifier.fillMaxWidth())
                    }
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
private fun GroupRoute(sessions: Sessions, id: GroupId, debugTools: DebugTools?, onBack: () -> Unit, onAdd: () -> Unit, onSettle: () -> Unit, onMembers: () -> Unit, onProfile: () -> Unit) {
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
    var confirmAccept by remember { mutableStateOf(false) }
    val banner = remember(view) { OwnershipBanners.build(view) }
    GroupScreen(
        model, onBack, onAdd, onSettle, onOpenExpense = { opened = it.id }, onMembers = onMembers, onProfile = onProfile, banner = banner,
        onAcceptOffer = { confirmAccept = true },
        onDeclineOffer = { scope.launch { withContext(Dispatchers.IO) { s.declineOwnership() } } },
    ) {
        if (debugTools != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            owntally.ui.components.TextAction("Add test friends and expenses (debug build)", { scope.launch { withContext(Dispatchers.IO) { debugTools.addTestData(s) } } })
            for (action in remember(view) { debugTools.actions(s) }) {
                owntally.ui.components.TextAction(action.label + " (debug)", { scope.launch { withContext(Dispatchers.IO) { action.run() } } })
            }
        }
    }
    if (confirmAccept && banner != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmAccept = false },
            title = { androidx.compose.material3.Text(banner.confirmTitle) },
            text = { androidx.compose.material3.Text(banner.confirmBody) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmAccept = false
                    scope.launch { withContext(Dispatchers.IO) { s.acceptOwnership() } }
                }) { androidx.compose.material3.Text("Become owner") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmAccept = false }) { androidx.compose.material3.Text("Not now") } },
        )
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
    var nav by mutableStateOf(NavStack())

    /** The Add expense form being filled in, and the group it belongs to (a form for another group is never reused). */
    var addExpense by mutableStateOf<Pair<GroupId, AddExpenseModel>?>(null)
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

@Composable
private fun AddExpenseRoute(sessions: Sessions, id: GroupId, onDone: () -> Unit) {
    // Everything that must outlive an early return is declared first: a composable that returns early drops the state and the
    // coroutine scope declared after the return, which once cancelled the save before it could close the screen.
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session ?: return Box(Modifier.fillMaxSize())
    val ui: HomeViewModel = viewModel()
    val view by s.view.collectAsStateWithLifecycle()
    LaunchedEffect(s) {
        // nextCounter() reads the database, so this is done off the main thread.
        if (ui.addExpense?.first != id) ui.addExpense = id to withContext(Dispatchers.IO) { AddExpenseModel.start(s.view.value, s.nextCounter(), java.time.LocalDate.now().toEpochDay()) }
    }
    val model = ui.addExpense?.takeIf { it.first == id }?.second ?: return
    AddExpenseScreen(
        model, view.state.name ?: "", onChange = { ui.addExpense = id to it; error = null },
        onSave = {
            val split = model.toSplit()
            val total = (owntally.model.format.MoneyInput.parse(model.amountText) as? owntally.model.format.ParsedAmount.Valid)?.money
            if (saving || split == null || total == null || !model.canSave) return@AddExpenseScreen
            saving = true
            scope.launch {
                try {
                    val r = withContext(Dispatchers.IO) { s.addExpense(model.trimmedDescription, total, model.paidBy, model.day, split) }
                    when (r) {
                        is owntally.data.session.AddResult.Added -> withContext(Dispatchers.Main.immediate) {
                            // Close the screen first, then clear the form, both in one step that cannot be interrupted.
                            onDone()
                            ui.addExpense = null
                        }
                        is owntally.data.session.AddResult.Refused -> error = "This expense can't be added (${r.reason.name.lowercase().replace('_', ' ')}). Someone may have left the group."
                        owntally.data.session.AddResult.NoKey -> error = "This phone doesn't have the group's key yet."
                        is owntally.data.session.AddResult.Invalid -> error = r.problem
                    }
                } catch (e: Exception) {
                    error = "The expense could not be saved on this phone."
                } finally {
                    saving = false
                }
            }
        },
        onClose = { ui.addExpense = null; onDone() }, error = error, saving = saving,
    )
}

@Composable
private fun SettleUpRoute(sessions: Sessions, id: GroupId, onBack: () -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session ?: return Box(Modifier.fillMaxSize())
    val view by s.view.collectAsStateWithLifecycle()
    SettleUpScreen(remember(view) { SettleUpModel.build(view) }, onBack)
}

@Composable
private fun MembersRoute(sessions: Sessions, joinRequests: owntally.data.session.JoinRequests?, id: GroupId, onBack: () -> Unit, onMenu: (owntally.model.PublicKey) -> Unit, onJoinRequests: () -> Unit, onMyProfile: () -> Unit, onLeave: () -> Unit, onRemovedTap: (owntally.model.PublicKey) -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session
    if (s == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val view by s.view.collectAsStateWithLifecycle()
    val waiting by produceState(0, id, view) { value = withContext(Dispatchers.IO) { joinRequests?.waiting(id)?.size ?: 0 } }
    MembersScreen(remember(view, waiting) { MembersModels.build(view, waiting) }, onBack, onMenu = { onMenu(it.key) }, onJoinRequests = if (joinRequests != null) onJoinRequests else null, onMyProfile = onMyProfile, onLeave = onLeave, onRemovedTap = { onRemovedTap(it.key) })
}

@Composable
private fun MemberActionsRoute(sessions: Sessions, id: GroupId, member: owntally.model.PublicKey, onClose: () -> Unit, onRemove: () -> Unit, onMakeOwner: () -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session
    if (s == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val view by s.view.collectAsStateWithLifecycle()
    val model = remember(view) { MemberActionsModels.build(view, member) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    if (model == null) {
        // The person is gone, or this phone may not act on them any more.
        LaunchedEffect(Unit) { onClose() }
        Box(Modifier.fillMaxSize())
        return
    }
    MemberActionsScreen(model, onClose, error = error, onAction = { kind ->
        if (kind == ActionKind.REMOVE) return@MemberActionsScreen onRemove()
        if (kind == ActionKind.MAKE_OWNER) return@MemberActionsScreen onMakeOwner()
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                when (kind) {
                    ActionKind.MAKE_ADMIN -> s.makeAdmin(member)
                    ActionKind.REMOVE_ADMIN -> s.removeAdmin(member)
                    // Make owner and Remove from group are handled above, before this block.
                    ActionKind.MAKE_OWNER, ActionKind.REMOVE -> null
                }
            }
            when (r) {
                is owntally.data.session.AddResult.Added -> onClose()
                is owntally.data.session.AddResult.Refused -> error = "That can't be done now (${r.reason.name.lowercase().replace('_', ' ')})."
                is owntally.data.session.AddResult.Invalid -> error = r.problem
                owntally.data.session.AddResult.NoKey -> error = "This phone doesn't have the group's key yet."
                null -> Unit
            }
        }
    })
}

@Composable
private fun RemoveMemberRoute(sessions: Sessions, id: GroupId, member: owntally.model.PublicKey, onCancel: () -> Unit, onRemoved: () -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session
    if (s == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val view by s.view.collectAsStateWithLifecycle()
    val model = remember(view) { RemoveMemberModels.build(view, member) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    if (model == null) {
        // Gone by other means (not while this phone is removing them: then onRemoved closes this screen and the one before it).
        if (!busy) LaunchedEffect(Unit) { onCancel() }
        Box(Modifier.fillMaxSize())
        return
    }
    RemoveMemberScreen(model, onCancel, busy = busy, error = error, onConfirm = {
        if (busy) return@RemoveMemberScreen
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { s.removeMember(member) }
            when (r) {
                is owntally.data.session.AddResult.Added -> onRemoved()
                is owntally.data.session.AddResult.Refused -> error = "That can't be done now (${r.reason.name.lowercase().replace('_', ' ')})."
                is owntally.data.session.AddResult.Invalid -> error = r.problem
                owntally.data.session.AddResult.NoKey -> error = "This phone doesn't have the group's key yet."
            }
            if (r !is owntally.data.session.AddResult.Added) busy = false
        }
    })
}

@Composable
private fun OwnerOfferRoute(sessions: Sessions, id: GroupId, member: owntally.model.PublicKey, onClose: () -> Unit, onChoose: () -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session
    if (s == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val view by s.view.collectAsStateWithLifecycle()
    val model = remember(view) { OwnerOfferModels.build(view, member) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    if (model == null) {
        // This phone is no longer the owner (they accepted), or the person is gone.
        LaunchedEffect(Unit) { onClose() }
        Box(Modifier.fillMaxSize())
        return
    }
    OwnerOfferScreen(
        model, onClose, busy = busy, error = error, onSecondary = onChoose,
        onPrimary = {
            if (busy) return@OwnerOfferScreen
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    if (model.stage == OfferStage.WAITING) s.cancelOffer() else s.offerOwnership(member)
                }
                busy = false
                error = when (r) {
                    is owntally.data.session.AddResult.Added -> null
                    is owntally.data.session.AddResult.Refused -> "That can't be done now (${r.reason.name.lowercase().replace('_', ' ')})."
                    is owntally.data.session.AddResult.Invalid -> r.problem
                    owntally.data.session.AddResult.NoKey -> "This phone doesn't have the group's key yet."
                }
            }
        },
    )
}

@Composable
private fun JoinRequestsRoute(sessions: Sessions, joinRequests: owntally.data.session.JoinRequests?, id: GroupId, onBack: () -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session
    if (s == null || joinRequests == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val view by s.view.collectAsStateWithLifecycle()
    var version by remember { mutableStateOf(0) }
    val waiting by produceState(emptyList<owntally.data.store.JoinRequest>(), id, view, version) { value = withContext(Dispatchers.IO) { joinRequests.waiting(id) } }
    val model = remember(view, waiting) { JoinRequestsModels.build(view, waiting, System.currentTimeMillis() / 1000) }
    val scope = rememberCoroutineScope()
    var approving by remember { mutableStateOf<JoinRequestRow?>(null) }
    if (model == null) {
        // This person can no longer approve joins (for example, an admin who lost the role).
        LaunchedEffect(Unit) { onBack() }
        Box(Modifier.fillMaxSize())
        return
    }
    JoinRequestsScreen(
        model, onBack, onApprove = { approving = it },
        onDecline = { row -> scope.launch { withContext(Dispatchers.IO) { joinRequests.decline(id, row.member) }; version++ } },
    )
    val target = approving
    if (target != null) {
        ApproveDialog(
            view, target, onCancel = { approving = null },
            onApprove = { name ->
                scope.launch {
                    // The dialog already checked the name and the member limit, so the usual answer is Approved; any other answer leaves the request waiting.
                    withContext(Dispatchers.IO) { joinRequests.approve(id, target.member, name) }
                    approving = null
                    version++
                }
            },
        )
    }
}

@Composable
private fun ProfileRoute(sessions: Sessions, id: GroupId, onBack: () -> Unit, onLeave: () -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session
    if (s == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val view by s.view.collectAsStateWithLifecycle()
    val mine = view.state.members[view.me]
    var name by remember(id) { mutableStateOf(mine?.name.orEmpty()) }
    var phone by remember(id) { mutableStateOf(mine?.phone.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var cannotLeave by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val check = remember(view, name, phone) { ProfileCheck.of(view, name, phone) }
    ProfileScreen(
        ProfileModels.title(view), name, phone, check, error, onName = { name = it.take(60) }, onPhone = { phone = it.take(30) }, onBack = onBack,
        onSave = {
            scope.launch {
                when (val r = withContext(Dispatchers.IO) { s.updateProfile(check.name, check.phone) }) {
                    is owntally.data.session.AddResult.Added -> onBack()
                    is owntally.data.session.AddResult.Refused -> error = "That can't be saved now (${r.reason.name.lowercase().replace('_', ' ')})."
                    is owntally.data.session.AddResult.Invalid -> error = r.problem
                    owntally.data.session.AddResult.NoKey -> error = "This phone doesn't have the group's key yet."
                }
            }
        },
        onLeave = {
            when (val o = LeaveModels.build(view)) {
                is LeaveOutcome.Blocked -> cannotLeave = o.message
                is LeaveOutcome.Allowed -> onLeave()
                null -> Unit
            }
        },
    )
    cannotLeave?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { cannotLeave = null },
            title = { androidx.compose.material3.Text("You can't leave yet") },
            text = { androidx.compose.material3.Text(message) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { cannotLeave = null }) { androidx.compose.material3.Text("OK") } },
        )
    }
}

@Composable
private fun LeaveRoute(sessions: Sessions, id: GroupId, onCancel: () -> Unit, onLeft: () -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session
    if (s == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val view by s.view.collectAsStateWithLifecycle()
    val outcome = remember(view) { LeaveModels.build(view) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val model = (outcome as? LeaveOutcome.Allowed)?.model
    if (model == null) {
        if (!busy) LaunchedEffect(Unit) { onCancel() }
        Box(Modifier.fillMaxSize())
        return
    }
    RemoveMemberScreen(
        RemoveMemberModel(model.title, model.intro + listOfNotNull(model.warning), model.rows, model.alsoLeftOut, model.button),
        onCancel, busy = busy, error = error,
        onConfirm = {
            if (busy) return@RemoveMemberScreen
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { sessions.leave(id) }
                when (r) {
                    is owntally.data.session.AddResult.Added -> onLeft()
                    is owntally.data.session.AddResult.Refused -> error = "You can't leave now (${r.reason.name.lowercase().replace('_', ' ')})."
                    is owntally.data.session.AddResult.Invalid -> error = r.problem
                    owntally.data.session.AddResult.NoKey -> error = "This phone doesn't have the group's key yet."
                }
                if (r !is owntally.data.session.AddResult.Added) busy = false
            }
        },
    )
}

@Composable
private fun CancelledRoute(sessions: Sessions, id: GroupId, member: owntally.model.PublicKey, onBack: () -> Unit, onAddAgain: (AddExpenseModel) -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session
    if (s == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val view by s.view.collectAsStateWithLifecycle()
    val model = remember(view) { CancelledListModels.build(view, member) }
    val scope = rememberCoroutineScope()
    if (model == null) {
        LaunchedEffect(Unit) { onBack() }
        Box(Modifier.fillMaxSize())
        return
    }
    CancelledScreen(model, onBack, onAddAgain = { row ->
        scope.launch {
            // nextCounter() reads the database, so this is done off the main thread.
            val form = withContext(Dispatchers.IO) { AddExpenseModel.fromCancelled(s.view.value, s.nextCounter(), java.time.LocalDate.now().toEpochDay(), row.entry) }
            onAddAgain(form)
        }
    })
}

@Composable
private fun SettingsRoute(sessions: Sessions, lock: owntally.ui.lock.LockController, onBack: () -> Unit) {
    val left by sessions.left.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    owntally.ui.lock.SettingsScreen(
        lock, onBack,
        leftGroups = remember(left) { owntally.ui.lock.LeftGroupsModels.rows(left) },
        onDeleteLeft = { id -> scope.launch { withContext(Dispatchers.IO) { sessions.deleteLeft(id) } } },
    )
}
