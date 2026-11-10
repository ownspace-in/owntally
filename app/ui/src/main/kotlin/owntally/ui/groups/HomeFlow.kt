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
fun HomeFlow(
    sessions: Sessions,
    debugTools: DebugTools? = null,
    lock: owntally.ui.lock.LockController? = null,
    joinRequests: owntally.data.session.JoinRequests? = null,
    sync: owntally.data.session.SyncCoordinator? = null,
    invites: owntally.data.session.Invites? = null,
    joinGroup: owntally.data.session.JoinGroup? = null,
    nearby: ((GroupId) -> owntally.ui.nearby.NearbyController)? = null,
    pendingInvites: owntally.data.session.PendingInvites? = null,
) {
    val ui: HomeViewModel = viewModel()
    val nav = ui.nav
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { sessions.refresh() } }
    androidx.activity.compose.BackHandler(enabled = nav.canGoBack) { ui.nav = nav.pop() }
    InviteLinkEffect(pendingInvites, joinGroup, onOpenInvitation = { ui.nav = NavStack().push(Screen.Join(it)) }, onOpenGroup = { ui.nav = NavStack().push(Screen.Group(it)) })
    val back = { ui.nav = ui.nav.pop() }
    Box(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding()) {
        when (val screen = nav.current) {
            Screen.Home -> HomeTabs(
                sessions,
                onOpen = { ui.nav = ui.nav.push(Screen.Group(it)) },
                onSettle = { ui.nav = ui.nav.push(Screen.SettleUp(it)) },
                onHow = { ui.nav = ui.nav.push(Screen.HowItWorks) },
                onPrivacy = { ui.nav = ui.nav.push(Screen.Privacy) },
                onSettings = if (lock != null) ({ ui.nav = ui.nav.push(Screen.Settings) }) else null,
                joinGroup = joinGroup,
                onJoin = { ui.nav = ui.nav.push(Screen.JoinPaste) },
                onOpenInvitation = { ui.nav = ui.nav.push(Screen.Join(it)) },
                sync = sync, onSync = { ui.nav = ui.nav.push(Screen.Sync(it)) },
                onCreate = { ui.nav = ui.nav.push(Screen.CreateGroup) },
            )
            Screen.CreateGroup -> CreateGroupRoute(sessions, onDone = back)
            Screen.HowItWorks -> HowItWorksScreen(onBack = back)
            Screen.Privacy -> owntally.ui.privacy.PrivacyScreen(onBack = back)
            is Screen.Sync -> SyncRoute(sessions, sync, screen.id, onBack = back, onNearby = if (nearby != null) ({ ui.nav = ui.nav.push(Screen.Nearby(screen.id)) }) else null, onExplain = { ui.nav = ui.nav.push(Screen.CantReach(screen.id, it)) })
            is Screen.CantReach -> CantReachRoute(sessions, sync, screen.id, screen.member, onBack = back, onNearby = if (nearby != null) ({ ui.nav = ui.nav.push(Screen.Nearby(screen.id)) }) else null)
            is Screen.Nearby -> NearbyRoute(sessions, nearby, screen.id, onBack = back)
            Screen.Settings -> if (lock != null) SettingsRoute(sessions, lock, onBack = back) else MessageTab("Settings", "There is nothing to set yet.")
            is Screen.SettleUp -> SettleUpRoute(sessions, screen.id, onBack = back, onNotReceived = { ui.nav = ui.nav.push(Screen.NotReceived(screen.id, it)) })
            is Screen.NotReceived -> NotReceivedRoute(sessions, screen.id, screen.settlement, onBack = back)
            is Screen.Invite -> InviteRoute(sessions, invites, screen.id, onBack = back)
            Screen.JoinPaste -> JoinPasteRoute(joinGroup, onBack = back, onOpened = { ui.nav = ui.nav.pop().push(Screen.Join(it)) })
            is Screen.Join -> JoinRoute(joinGroup, screen.id, onBack = back, onOpenGroup = { ui.nav = ui.nav.home().push(Screen.Group(screen.id)) })
            is Screen.Members -> MembersRoute(sessions, joinRequests, invites, screen.id, onBack = back, onInvite = { ui.nav = ui.nav.push(Screen.Invite(screen.id)) }, onMenu = { ui.nav = ui.nav.push(Screen.MemberActions(screen.id, it)) }, onJoinRequests = { ui.nav = ui.nav.push(Screen.JoinRequests(screen.id)) }, onMyProfile = { ui.nav = ui.nav.push(Screen.Profile(screen.id)) }, onLeave = { ui.nav = ui.nav.push(Screen.Leave(screen.id)) }, onRemovedTap = { ui.nav = ui.nav.push(Screen.Cancelled(screen.id, it)) })
            is Screen.Cancelled -> CancelledRoute(
                sessions, screen.id, screen.member, onBack = back,
                onAddAgain = { model -> ui.addExpense = screen.id to model; ui.nav = ui.nav.push(Screen.AddExpense(screen.id)) },
            )
            is Screen.Profile -> ProfileRoute(sessions, screen.id, onBack = back, onLeave = { ui.nav = ui.nav.push(Screen.Leave(screen.id)) })
            is Screen.Leave -> LeaveRoute(sessions, screen.id, onCancel = back, onLeft = { ui.nav = ui.nav.home() })
            is Screen.JoinRequests -> JoinRequestsRoute(sessions, joinRequests, invites, screen.id, onBack = back, onNewInvite = { ui.nav = ui.nav.push(Screen.Invite(screen.id)) })
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
private fun HomeTabs(
    sessions: Sessions, onOpen: (GroupId) -> Unit, onSettle: (GroupId) -> Unit, onHow: () -> Unit, onPrivacy: () -> Unit, onSettings: (() -> Unit)?, onCreate: () -> Unit,
    sync: owntally.data.session.SyncCoordinator?, onSync: (GroupId) -> Unit,
    joinGroup: owntally.data.session.JoinGroup? = null, onJoin: () -> Unit = {}, onOpenInvitation: (GroupId) -> Unit = {},
) {
    val ui: HomeViewModel = viewModel()
    val tab = ui.tab
    val summaries by sessions.summaries.collectAsStateWithLifecycle()
    val model = remember(summaries) { GroupsModels.list(summaries) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (Tab.entries[tab]) {
                Tab.GROUPS -> {
                    val invitations by produceState(emptyList<PendingCardModel>(), joinGroup, summaries) {
                        while (true) {
                            value = if (joinGroup == null) emptyList() else withContext(Dispatchers.IO) { joinGroup.pending().map { JoinModels.card(it, System.currentTimeMillis() / 1000) } }
                            kotlinx.coroutines.delay(3_000)
                        }
                    }
                    GroupsScreen(model, onOpen = { onOpen(it.id) }, onCreate = onCreate, invitations = invitations, onOpenInvitation = { onOpenInvitation(it.group) }, onJoin = if (joinGroup != null) onJoin else null)
                }
                Tab.SETTLE -> SettleTab(model, onSettle)
                Tab.SYNC -> if (sync == null) MessageTab("Sync", "Sync is not available on this phone.") else SyncTab(
                    GroupsModels.settleTabCards(model.cards),
                    statusLine = { card ->
                        val st by sync.status(card.id).collectAsStateWithLifecycle()
                        SyncModels.shortHeadline(st)
                    },
                    onOpen = { onSync(it.id) },
                )
                Tab.ABOUT -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        MessageTab(
                            "About OwnTally",
                            "OwnTally splits expenses with friends. There is no account, and no server keeps your data: your groups are stored on this phone, and each group's data is encrypted with its own key.",
                        )
                    }
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        owntally.ui.components.PrimaryButton("How OwnTally works", onHow, Modifier.fillMaxWidth())
                        owntally.ui.components.PrimaryButton("Privacy", onPrivacy, Modifier.fillMaxWidth())
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
                val colour = if (on) OwnTallyColors.Accent else OwnTallyColors.TextSecondary
                Column(
                    Modifier.weight(1f).clickable(onClick = { onSelect(t) }).padding(vertical = 6.dp)
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
private fun SettleUpRoute(sessions: Sessions, id: GroupId, onBack: () -> Unit, onNotReceived: (owntally.model.EventId) -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session ?: return Box(Modifier.fillMaxSize())
    val view by s.view.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var amountFor by remember { mutableStateOf<SettleAction?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun write(call: () -> owntally.data.session.AddResult) {
        scope.launch { message = SettleMessages.of(withContext(Dispatchers.IO) { call() }) }
    }
    val model = remember(view) { SettleUpModel.build(view) }
    Column(Modifier.fillMaxSize()) {
        if (message != null) {
            Box(Modifier.padding(20.dp, 8.dp, 20.dp, 0.dp)) {
                owntally.ui.components.Notice(message!!, modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = { message = null }))
            }
        }
        Box(Modifier.weight(1f)) {
            SettleUpScreen(
                model, onBack,
                onAction = { action ->
                    message = null
                    when (action) {
                        is SettleAction.Copy -> action.row.phone?.let { owntally.ui.copySensitive(context, "Phone number", it) }
                        is SettleAction.Call -> action.row.phone?.let {
                            try { context.startActivity(SettleIntents.dial(it)) }
                            catch (e: android.content.ActivityNotFoundException) { message = "This phone has no app to make calls." }
                        }
                        is SettleAction.Reminder ->
                            try { context.startActivity(SettleIntents.chooser(SettleIntents.reminder(action.row.otherName, action.row.amount))) }
                            catch (e: android.content.ActivityNotFoundException) { message = "This phone has no app to send a message with." }
                        is SettleAction.IPaid, is SettleAction.MarkReceived -> amountFor = action
                        is SettleAction.Confirm -> write { s.confirm(action.id) }
                        is SettleAction.CancelDuplicate -> write { s.cancelDuplicate(action.id) }
                        is SettleAction.NotReceived -> onNotReceived(action.row.id)
                    }
                },
            )
        }
    }
    when (val a = amountFor) {
        is SettleAction.IPaid -> AmountDialog("I paid ${a.row.otherName}", a.row.money, onCancel = { amountFor = null }, onConfirm = { amount ->
            amountFor = null; write { s.markPaid(a.row.to, amount) }
        })
        is SettleAction.MarkReceived -> AmountDialog("Received from ${a.row.otherName}", a.row.money, onCancel = { amountFor = null }, onConfirm = { amount ->
            amountFor = null; write { s.markReceived(a.row.from, amount) }
        })
        else -> Unit
    }
}

@Composable
private fun NotReceivedRoute(sessions: Sessions, id: GroupId, settlement: owntally.model.EventId, onBack: () -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session ?: return Box(Modifier.fillMaxSize())
    val view by s.view.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val model = remember(view) { NotReceivedModel.build(view, settlement, System.currentTimeMillis() / 1000) }
    NotReceivedScreen(model, onBack, message = message, onTell = { reason ->
        scope.launch { message = SettleMessages.of(withContext(Dispatchers.IO) { s.dispute(settlement, reason) }) }
    })
}

@Composable
private fun MembersRoute(sessions: Sessions, joinRequests: owntally.data.session.JoinRequests?, invites: owntally.data.session.Invites?, id: GroupId, onBack: () -> Unit, onInvite: () -> Unit, onMenu: (owntally.model.PublicKey) -> Unit, onJoinRequests: () -> Unit, onMyProfile: () -> Unit, onLeave: () -> Unit, onRemovedTap: (owntally.model.PublicKey) -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session
    if (s == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val view by s.view.collectAsStateWithLifecycle()
    val requestChanges by (joinRequests?.changes ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsStateWithLifecycle()
    val waiting by produceState(0, id, view, requestChanges) { value = withContext(Dispatchers.IO) { joinRequests?.waiting(id)?.size ?: 0 } }
    MembersScreen(remember(view, waiting) { MembersModels.build(view, waiting) }, onBack, onInvite = if (invites != null) onInvite else null, onMenu = { onMenu(it.key) }, onJoinRequests = if (joinRequests != null) onJoinRequests else null, onMyProfile = onMyProfile, onLeave = onLeave, onRemovedTap = { onRemovedTap(it.key) })
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
private fun JoinRequestsRoute(sessions: Sessions, joinRequests: owntally.data.session.JoinRequests?, invites: owntally.data.session.Invites?, id: GroupId, onBack: () -> Unit, onNewInvite: () -> Unit) {
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
    val changes by joinRequests.changes.collectAsStateWithLifecycle()
    val waiting by produceState(emptyList<owntally.data.store.JoinRequest>(), id, view, version, changes) { value = withContext(Dispatchers.IO) { joinRequests.waiting(id) } }
    val open by produceState(emptyList<OpenInviteInfo>(), id, view, version, changes) {
        value = withContext(Dispatchers.IO) { invites?.open(id).orEmpty().map { OpenInviteInfo(it.ticketId.joinToString("") { b -> "%02x".format(b) }, it.expiresAtSeconds) } }
    }
    val model = remember(view, waiting, open) { JoinRequestsModels.build(view, waiting, System.currentTimeMillis() / 1000, open) }
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
        onRevoke = { row ->
            scope.launch {
                withContext(Dispatchers.IO) { invites?.open(id)?.firstOrNull { it.ticketId.joinToString("") { b -> "%02x".format(b) } == row.id }?.let { invites.revoke(id, it) } }
                version++
            }
        },
        onNewInvite = if (invites != null) onNewInvite else null,
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

@Composable
private fun SyncRoute(sessions: Sessions, sync: owntally.data.session.SyncCoordinator?, id: GroupId, onBack: () -> Unit, onNearby: (() -> Unit)?, onExplain: (owntally.model.PublicKey) -> Unit) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session
    if (s == null || sync == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val view by s.view.collectAsStateWithLifecycle()
    val status by sync.status(id).collectAsStateWithLifecycle()
    val problems by produceState(emptyList<owntally.data.store.SyncProblem>(), id, status) { value = withContext(Dispatchers.IO) { sync.problems(id) } }
    val scope = rememberCoroutineScope()
    val model = remember(view, status, problems) { SyncModels.build(view, status, problems, System.currentTimeMillis() / 1000) }
    SyncScreen(model, onBack, onSyncNow = { scope.launch { withContext(Dispatchers.IO) { sync.syncNow(id) } } }, onDismissNotice = { sync.dismissNotice(id) }, onNearby = onNearby, onExplain = { onExplain(it.key) })
}

@Composable
private fun CantReachRoute(sessions: Sessions, sync: owntally.data.session.SyncCoordinator?, id: GroupId, member: owntally.model.PublicKey, onBack: () -> Unit, onNearby: (() -> Unit)?) {
    val session by produceState<GroupSession?>(null, id) {
        value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null }
    }
    val s = session
    if (s == null || sync == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val view by s.view.collectAsStateWithLifecycle()
    val status by sync.status(id).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val now by produceState(System.currentTimeMillis() / 1000, id) {
        while (true) { kotlinx.coroutines.delay(15_000); value = System.currentTimeMillis() / 1000 }
    }
    val model = remember(view, status, now) { CantReachModels.build(view, member, status.peers[member] ?: owntally.data.session.PeerStatus(), now) }
    CantReachScreen(model, onBack, onTryNow = { scope.launch { withContext(Dispatchers.IO) { sync.syncNow(id, member) } } }, onNearby = onNearby)
}


/**
 * Invite friends (design/10). Opening the screen shows the newest invite that still works, or makes one when there is none. Share
 * and Copy hand the link to the system (the clipboard entry is marked sensitive: the link holds the group key).
 */
@Composable
private fun InviteRoute(sessions: Sessions, invites: owntally.data.session.Invites?, id: GroupId, onBack: () -> Unit) {
    if (invites == null) { Box(Modifier.fillMaxSize()); return }
    val context = androidx.compose.ui.platform.LocalContext.current
    var invite by remember(id) { mutableStateOf<owntally.data.session.Invite?>(null) }
    var revoked by remember(id) { mutableStateOf(false) }
    var loaded by remember(id) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(id) {
        invite = withContext(Dispatchers.IO) { invites.current(id) ?: invites.create(id) }
        loaded = true
    }
    val now by produceState(System.currentTimeMillis() / 1000, id) {
        while (true) { kotlinx.coroutines.delay(15_000); value = System.currentTimeMillis() / 1000 }
    }
    val name = remember(id) { sessions.summaries.value.firstOrNull { it.groupId == id }?.name ?: "Group" }
    if (!loaded) { Box(Modifier.fillMaxSize()); return }
    val shown = invite
    val model = InviteModels.build(name, shown?.link, shown?.expiresAtSeconds ?: 0, revoked, now)
    InviteScreen(
        model, onBack,
        onShare = {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, model.shareText)
            context.startActivity(android.content.Intent.createChooser(send, "Share invite link"))
        },
        onCopy = { model.link?.let { owntally.ui.copySensitive(context, "OwnTally invite", it) } },
        onRevoke = { val i = invite; if (i != null) scope.launch { withContext(Dispatchers.IO) { invites.revoke(id, i) }; revoked = true } },
        onNew = { scope.launch { invite = withContext(Dispatchers.IO) { invites.create(id) }; revoked = false } },
    )
}


/** Join a group: paste the invite, open it (the host is asked what the group is), then the form. */
@Composable
private fun JoinPasteRoute(joinGroup: owntally.data.session.JoinGroup?, onBack: () -> Unit, onOpened: (GroupId) -> Unit) {
    if (joinGroup == null) { Box(Modifier.fillMaxSize()); return }
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PasteInviteScreen(
        text, { text = it; problem = null }, problem, busy,
        onPaste = {
            val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip
            val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!pasted.isNullOrBlank()) { text = pasted; problem = null }
        },
        onContinue = {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { joinGroup.open(text) }
                busy = false
                when (result) {
                    is owntally.data.session.OpenResult.Opened -> {
                        val bad = JoinModels.previewProblem(result.preview)
                        if (bad != null) { withContext(Dispatchers.IO) { joinGroup.discard(result.join.group) }; problem = bad } else onOpened(result.join.group)
                    }
                    else -> problem = JoinModels.pasteProblem(result)
                }
            }
        },
        onBack = onBack,
    )
}

/** The form, then waiting for approval, then "You're in" until the history arrives and the group opens. */
@Composable
private fun JoinRoute(joinGroup: owntally.data.session.JoinGroup?, id: GroupId, onBack: () -> Unit, onOpenGroup: () -> Unit) {
    if (joinGroup == null) { Box(Modifier.fillMaxSize()); return }
    var version by remember(id) { mutableStateOf(0) }
    val join by produceState<owntally.data.store.PendingJoin?>(null, id, version) { value = withContext(Dispatchers.IO) { joinGroup.pending(id) } }
    var name by remember(id) { mutableStateOf("") }
    var phone by remember(id) { mutableStateOf("") }
    var showErrors by remember(id) { mutableStateOf(false) }
    var problem by remember(id) { mutableStateOf<String?>(null) }
    var busy by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) {
        val profile = withContext(Dispatchers.IO) { joinGroup.defaultProfile() }
        if (name.isEmpty() && profile != null) name = profile.name
        if (phone.isEmpty() && profile != null) phone = profile.phone
    }
    val state = join?.state
    // While waiting, ask the host now and then; once approved, wait for the history and open the group.
    LaunchedEffect(id, state) {
        when (state) {
            owntally.data.store.PendingState.WAITING -> while (true) {
                kotlinx.coroutines.delay(30_000)
                if (withContext(Dispatchers.IO) { joinGroup.check(id) } == owntally.data.session.CheckResult.Approved) { version++; break }
            }
            owntally.data.store.PendingState.APPROVED -> while (true) {
                if (withContext(Dispatchers.IO) { joinGroup.completeIfMember(id) }) { onOpenGroup(); break }
                kotlinx.coroutines.delay(2_000)
            }
            else -> Unit
        }
    }
    val now = System.currentTimeMillis() / 1000
    val j = join
    if (j == null) { Box(Modifier.fillMaxSize()); return }
    when (j.state) {
        owntally.data.store.PendingState.OPENED -> {
            val model = JoinModels.form(j, name, phone, now, showErrors)
            JoinFormScreen(
                model, name, phone, { name = it; problem = null }, { phone = it; problem = null }, problem, busy,
                onRequest = {
                    showErrors = true
                    if (model.canRequest) {
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { joinGroup.request(id, name, phone) }
                            busy = false
                            problem = JoinModels.problem(j, r)
                            version++
                        }
                    }
                },
                onBack = onBack,
                onTryAgain = if (j.groupName.isBlank()) ({ scope.launch { withContext(Dispatchers.IO) { joinGroup.refreshPreview(id) }; version++ } }) else null,
                onDiscard = { scope.launch { withContext(Dispatchers.IO) { joinGroup.discard(id) }; onBack() } },
            )
        }
        else -> {
            var note by remember(id) { mutableStateOf<String?>(null) }
            JoinWaitingScreen(
                JoinModels.waiting(j, now), note, busy,
                onRetry = {
                    if (j.state == owntally.data.store.PendingState.DECLINED) {
                        scope.launch { withContext(Dispatchers.IO) { joinGroup.discard(id) }; onBack() }
                    } else {
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { joinGroup.check(id) }
                            busy = false
                            note = JoinModels.checkNote(j, r)
                            version++
                        }
                    }
                },
                onBack = onBack,
            )
        }
    }
}


/**
 * Nearby (T6.11): Start asks for what Nearby needs only after saying why, runs while this screen is open and stops when it is left.
 * Nothing about Nearby runs before the person taps Start.
 */
@Composable
private fun NearbyRoute(sessions: Sessions, nearby: ((GroupId) -> owntally.ui.nearby.NearbyController)?, id: GroupId, onBack: () -> Unit) {
    if (nearby == null) { Box(Modifier.fillMaxSize()); return }
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember(id) { nearby(id) }
    val session by produceState<GroupSession?>(null, id) { value = try { withContext(Dispatchers.IO) { sessions.session(id) } } catch (e: IllegalStateException) { null } }
    val s = session ?: run { Box(Modifier.fillMaxSize()); return }
    val view by s.view.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val sdk = android.os.Build.VERSION.SDK_INT
    fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var gate by remember(id) { mutableStateOf(if (owntally.ui.nearby.NearbyPermissions.missing(sdk, ::granted).isEmpty()) owntally.ui.nearby.NearbyGate.READY else owntally.ui.nearby.NearbyGate.EXPLAIN) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        gate = if (owntally.ui.nearby.NearbyPermissions.missing(sdk, ::granted).isEmpty()) owntally.ui.nearby.NearbyGate.READY else owntally.ui.nearby.NearbyGate.DENIED
    }
    androidx.compose.runtime.DisposableEffect(id) { onDispose { controller.stop() } }
    val names = remember(view) { view.state.currentMembers.filterKeys { it != view.me }.mapValues { it.value.name } }
    val model = remember(view, status) { owntally.ui.nearby.NearbyModels.build(view.state.name ?: "Group", names, status, controller.availability() == owntally.ui.nearby.NearbyAvailability.NO_PLAY_SERVICES) }
    owntally.ui.nearby.NearbyScreen(
        model, gate, owntally.ui.nearby.NearbyPermissions.explanation(sdk),
        onBack = onBack,
        onContinue = { launcher.launch(owntally.ui.nearby.NearbyPermissions.request(sdk).toTypedArray()) },
        onOpenSettings = {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)))
        },
        onStart = { controller.start() }, onStop = { controller.stop() }, onLookAgain = { controller.lookAgain() },
    )
}

/**
 * An invite link the person tapped outside the app (M9, decision A1): once this screen is showing (so past first-run setup and the lock), the
 * waiting link is opened like a pasted one: the join form, or the group if the person is already in it, or a message saying what is wrong. The
 * link is removed from storage as soon as it has been opened (an opened invitation is kept by the join flow itself).
 */
@Composable
private fun InviteLinkEffect(pending: owntally.data.session.PendingInvites?, joinGroup: owntally.data.session.JoinGroup?, onOpenInvitation: (GroupId) -> Unit, onOpenGroup: (GroupId) -> Unit) {
    if (pending == null || joinGroup == null) return
    val arrived by pending.changes.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(arrived) {
        pending.takeProblem()?.let { message = JoinModels.linkProblem(it) }
        val link = withContext(Dispatchers.IO) { pending.waiting() } ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { joinGroup.open(link) }
        withContext(Dispatchers.IO) { pending.clear() }
        when (result) {
            is owntally.data.session.OpenResult.Opened -> {
                val bad = JoinModels.previewProblem(result.preview)
                if (bad != null) { withContext(Dispatchers.IO) { joinGroup.discard(result.join.group) }; message = bad } else onOpenInvitation(result.join.group)
            }
            is owntally.data.session.OpenResult.AlreadyInGroup -> { message = JoinModels.pasteProblem(result); onOpenGroup(result.group) }
            else -> message = JoinModels.pasteProblem(result)
        }
    }
    message?.let { text ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Invite link") },
            text = { Text(text) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}
