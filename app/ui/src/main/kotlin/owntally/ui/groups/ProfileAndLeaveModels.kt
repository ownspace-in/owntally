package owntally.ui.groups

import owntally.data.session.GroupView
import owntally.model.Money
import owntally.model.Permissions
import owntally.model.Role
import owntally.model.format.MoneyFormat
import owntally.model.isNameTaken
import owntally.ui.onboarding.OnboardingRules
import owntally.ui.onboarding.PhoneProblem

object ProfileModels {
    fun title(view: GroupView) = "Your name and phone in ${view.state.name ?: "this group"}"
}

/**
 * The profile form (D3), checked as it is typed. The name rule is the core's [isNameTaken] with the person's own row left out (so changing only the
 * capitals of their own name is fine); the phone rule is the one first run uses, which has the core's limits.
 */
data class ProfileCheck(val name: String, val phone: String, val nameProblem: String?, val phoneProblem: String?, val changed: Boolean) {
    val canSave: Boolean get() = nameProblem == null && phoneProblem == null && changed

    companion object {
        fun of(view: GroupView, typedName: String, typedPhone: String): ProfileCheck {
            val name = typedName.trim()
            val phone = typedPhone.trim()
            val current = view.state.members[view.me]
            val nameProblem = when {
                name.isEmpty() -> "Type your name."
                name.length > 40 -> "Use at most 40 letters."
                isNameTaken(view.state, name, except = view.me) -> "That name is already taken in this group"
                else -> owntally.model.NameText.problem(name)
            }
            val phoneProblem = when (OnboardingRules.phoneProblem(phone)) {
                null -> null
                PhoneProblem.EMPTY -> "Enter your phone number."
                PhoneProblem.BAD_CHARACTERS -> "Use only digits, spaces and + - ( ) ."
                PhoneProblem.BAD_LENGTH -> "Use 5 to 20 characters."
                PhoneProblem.TOO_FEW_DIGITS -> "Use at least 5 digits."
            }
            return ProfileCheck(name, phone, nameProblem, phoneProblem, changed = name != current?.name || phone != current.phone)
        }
    }
}

/** The "Leave Goa Trip?" screen (D4): the same table as removing someone, in the second person. */
data class LeaveModel(val title: String, val intro: List<String>, val rows: List<RemoveRow>, val alsoLeftOut: LeftOutBox?, val warning: String?, val button: String)

sealed class LeaveOutcome {
    data class Allowed(val model: LeaveModel) : LeaveOutcome()

    /** An admin or the owner cannot leave; [message] says what to do instead. */
    data class Blocked(val message: String) : LeaveOutcome()
}

object LeaveModels {
    /** Null when this person is not in the group. */
    fun build(view: GroupView): LeaveOutcome? {
        val state = view.state
        val role = Permissions.roleOf(state, view.me) ?: return null
        if (!Permissions.leave(role)) {
            return LeaveOutcome.Blocked(if (role == Role.OWNER) "Hand over ownership first." else "Ask the owner to make you a member first, or to remove you.")
        }
        val (rows, box) = RemoveMemberModels.table(view, view.me, selfSpeaking = true)
        val mine = view.balances[view.me] ?: Money.ZERO
        val warning = when {
            mine.isPositive() -> "You are not settled up: you are owed ${MoneyFormat.format(mine)}. Leaving takes you out of the sums, so ask to be paid first."
            mine.isNegative() -> "You are not settled up: you owe ${MoneyFormat.format(-mine)}. Leaving takes you out of the sums, so settle up first if you want to pay it."
            else -> null
        }
        val group = state.name ?: "the group"
        return LeaveOutcome.Allowed(
            LeaveModel(
                "Leave $group?",
                listOf(
                    "You will no longer see or add expenses in $group. Your entries stay in the history but don't count.",
                    "The group is recalculated as if you had never joined.",
                ),
                rows, box, warning, "Leave group",
            ),
        )
    }
}
