package owntally.ui.groups

import owntally.data.session.CheckResult
import owntally.data.session.OpenResult
import owntally.data.session.PreviewState
import owntally.data.session.RequestResult
import owntally.data.store.PendingJoin
import owntally.data.store.PendingState
import owntally.ui.onboarding.OnboardingRules
import owntally.ui.onboarding.OnboardingText

/** "You were invited" (design/09): the group as the host described it, the name and number to join with, and what is wrong with them. */
class JoinFormModel(
    val heading: String,
    val title: String,
    val initials: String,
    val subtitle: String,
    val expiry: String,
    val unreachableNote: String?,
    val privacyNote: String,
    val footnote: String,
    val button: String,
    val nameError: String?,
    val phoneError: String?,
    val shownNameError: String?,
    val shownPhoneError: String?,
    val canRequest: Boolean,
)

/** "Waiting for approval" (design/09b), and its approved and declined versions. [retry] is null when there is nothing to tap. */
class JoinWaitingModel(
    val groupTitle: String,
    val initials: String,
    val subtitle: String,
    val expiry: String,
    val title: String,
    val body: String,
    val helpTitle: String?,
    val helpLines: List<String>,
    val retry: String?,
)

class PendingCardModel(val group: owntally.model.GroupId, val title: String, val initials: String, val status: String)

object JoinModels {
    private fun title(j: PendingJoin) = j.groupName.ifBlank { "A group" }
    private fun hostName(j: PendingJoin) = j.ownerName.ifBlank { null }

    private fun subtitle(j: PendingJoin) = when {
        j.groupName.isBlank() -> ""
        else -> (if (j.ownerName.isBlank()) "" else "Owner: ${j.ownerName} · ") + (if (j.memberCount == 1) "1 member" else "${j.memberCount} members")
    }

    private fun expiry(j: PendingJoin, now: Long) =
        if (now >= j.expiresAtSeconds) "This invite has expired. Ask an admin for a new one."
        else "This invite works once and expires in ${InviteModels.timeLeft(j.expiresAtSeconds - now)}."

    fun form(j: PendingJoin, typedName: String, typedPhone: String, now: Long, showErrors: Boolean = false): JoinFormModel {
        val nameProblem = OnboardingRules.nameProblem(typedName)?.let { OnboardingText.nameError(it) }
        val phoneProblem = OnboardingRules.phoneProblem(typedPhone)?.let { OnboardingText.phoneError(it) }
        val expired = now >= j.expiresAtSeconds
        val group = j.groupName.ifBlank { "the group" }
        return JoinFormModel(
            heading = "You were invited",
            title = title(j),
            initials = GroupsModels.initials(title(j)),
            subtitle = subtitle(j),
            expiry = expiry(j, now),
            unreachableNote = if (j.groupName.isBlank()) "The person who invited you isn't reachable right now, so we can't show the group yet. You can still ask to join." else null,
            privacyNote = "Members of $group will see this name and number. Nothing else is shared.",
            footnote = "An admin must approve you before you see any expenses.",
            button = "Request to join",
            nameError = nameProblem,
            phoneError = phoneProblem,
            shownNameError = if (showErrors || typedName.isNotEmpty()) nameProblem else null,
            shownPhoneError = if (showErrors || typedPhone.isNotEmpty()) phoneProblem else null,
            canRequest = nameProblem == null && phoneProblem == null && !expired,
        )
    }

    fun waiting(j: PendingJoin, now: Long): JoinWaitingModel {
        val who = hostName(j)
        return when (j.state) {
            PendingState.APPROVED -> JoinWaitingModel(
                title(j), GroupsModels.initials(title(j)), subtitle(j), expiry(j, now), "You're in",
                "${who ?: "An admin"} approved your request. Getting the group's history…", null, emptyList(), null,
            )
            PendingState.DECLINED -> JoinWaitingModel(
                title(j), GroupsModels.initials(title(j)), subtitle(j), expiry(j, now), "Your request was declined",
                "Nobody is told why. You can ask an admin for a new invite.", null, emptyList(), "Close",
            )
            else -> JoinWaitingModel(
                title(j), GroupsModels.initials(title(j)), subtitle(j), expiry(j, now), "Waiting for approval",
                "${if (who != null) "$who or another admin" else "An admin"} needs to open OwnTally and approve your request. You can close this screen and come back later.",
                "If it doesn't connect", listOf("Ask an admin to open the app, then tap Retry.", "If the invite expired, an admin can send you a new one."), "Retry",
            )
        }
    }

    private fun unreachable(j: PendingJoin) = hostName(j)?.let { "$it's phone isn't reachable. Ask $it to open OwnTally, then tap Retry." }
        ?: "The phone of the person who invited you isn't reachable. Ask them to open OwnTally, then tap Retry."

    /** What to tell the person when asking to join did not end in "waiting". */
    fun problem(j: PendingJoin, r: RequestResult): String? = when (r) {
        RequestResult.Waiting -> null
        RequestResult.HostUnreachable -> unreachable(j)
        RequestResult.NoLongerValid -> "This invite was already used, has expired or was cancelled. Ask an admin for a new one."
        RequestResult.NotVerified -> "This invite could not be checked, so nothing was sent. Ask an admin for a new one."
        RequestResult.InvalidDetails -> "Check the name and number."
        RequestResult.NoSuchInvitation -> "This invite is no longer here. Open it again from the link."
    }

    fun checkNote(j: PendingJoin, r: CheckResult): String? = when (r) {
        CheckResult.StillWaiting, CheckResult.Approved, CheckResult.Declined -> null
        CheckResult.HostUnreachable -> unreachable(j)
        CheckResult.Unknown -> "${hostName(j)?.let { "$it's phone" } ?: "The host's phone"} has no record of your request. Ask an admin for a new invite."
        CheckResult.NoSuchInvitation -> "This invite is no longer here. Open it again from the link."
    }

    fun previewProblem(s: PreviewState): String? = when (s) {
        PreviewState.FOUND, PreviewState.HOST_UNREACHABLE -> null
        PreviewState.NO_LONGER_VALID -> "This invite was already used, has expired or was cancelled. Ask an admin for a new one."
        PreviewState.NOT_VERIFIED -> "The phone that answered is not the one this invite names. Don't use this invite; ask an admin for a new one."
    }

    /** What to tell the person when a link they tapped could not be used (M9). */
    fun linkProblem(r: owntally.data.session.PendingInvites.Received): String = when (r) {
        owntally.data.session.PendingInvites.Received.Expired -> "This invite has expired. Ask an admin for a new one."
        else -> "That link isn't an OwnTally invite. Ask your friend to send it again."
    }

    fun pasteProblem(r: OpenResult): String? = when (r) {
        OpenResult.NotAnInvite -> "That doesn't look like an OwnTally invite. Paste the whole link or code."
        OpenResult.Expired -> "This invite has expired. Ask an admin for a new one."
        is OpenResult.AlreadyInGroup -> "You are already in this group."
        is OpenResult.Opened -> null
    }

    fun card(j: PendingJoin, now: Long) = PendingCardModel(
        j.group, if (j.groupName.isBlank()) "Invitation" else j.groupName, GroupsModels.initials(if (j.groupName.isBlank()) "Invitation" else j.groupName),
        when (j.state) {
            PendingState.OPENED -> "Not sent yet"
            PendingState.WAITING -> "Waiting for approval"
            PendingState.APPROVED -> "Getting the history…"
            PendingState.DECLINED -> "Declined"
        },
    )
}
