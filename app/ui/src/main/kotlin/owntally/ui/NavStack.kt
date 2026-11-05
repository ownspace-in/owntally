package owntally.ui

import owntally.model.GroupId

/** The screens above the four tabs. Each is a plain value, so a stack of them can be tested without Android. */
sealed interface Screen {
    data object Home : Screen
    data object CreateGroup : Screen
    data class Group(val id: GroupId) : Screen
    data class AddExpense(val id: GroupId) : Screen
    data class SettleUp(val id: GroupId) : Screen
    data class Members(val id: GroupId) : Screen
    data class OwnerOffer(val id: GroupId, val member: owntally.model.PublicKey) : Screen
    data class RemoveMember(val id: GroupId, val member: owntally.model.PublicKey) : Screen
    data class MemberActions(val id: GroupId, val member: owntally.model.PublicKey) : Screen
    data object HowItWorks : Screen
    data object Settings : Screen
}

/** Where the person is, and where Back goes. Never empty: Home is always at the bottom. */
data class NavStack(val screens: List<Screen> = listOf(Screen.Home)) {
    val current: Screen get() = screens.last()
    val canGoBack: Boolean get() = screens.size > 1

    /** Pressing the same thing twice quickly does not stack the same screen twice. */
    fun push(screen: Screen): NavStack = if (screen == current) this else NavStack(screens + screen)

    fun pop(): NavStack = if (canGoBack) NavStack(screens.dropLast(1)) else this

    fun home(): NavStack = NavStack()

    /** Drops every screen about [id] (for example when that group no longer exists). */
    fun without(id: GroupId): NavStack {
        val kept = screens.filterNot {
            when (it) {
                is Screen.Group -> it.id == id
                is Screen.AddExpense -> it.id == id
                is Screen.SettleUp -> it.id == id
                is Screen.Members -> it.id == id
                is Screen.MemberActions -> it.id == id
                is Screen.RemoveMember -> it.id == id
                is Screen.OwnerOffer -> it.id == id
                else -> false
            }
        }
        return if (kept.size == screens.size) this else NavStack(kept.ifEmpty { listOf(Screen.Home) })
    }
}
