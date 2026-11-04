package owntally.ui.lock

/** Whether this phone can show its own lock to an app (a fingerprint, or a screen lock PIN, pattern or password). */
enum class PhoneLockAvailability { AVAILABLE, NOT_SET_UP, UNSUPPORTED }

/** What the phone's own lock screen can do for the app. Implemented with Android's BiometricPrompt in the app module. */
interface PhoneAuthenticator {
    fun availability(): PhoneLockAvailability

    /** Shows the phone's lock. [onResult] gets true only if the person proved who they are; any error or cancel is false. Nothing is stored. */
    fun authenticate(title: String, onResult: (Boolean) -> Unit)
}

sealed class ModeChange {
    data object Nothing : ModeChange()

    /** Not allowed (the phone cannot check a lock). */
    data object Refused : ModeChange()

    /** Show the phone's lock first; set the mode only if it passes. */
    data class AskThenSet(val mode: LockMode) : ModeChange()

    /** Nothing to ask (the phone has no lock to ask with): set it. */
    data class SetNow(val mode: LockMode) : ModeChange()
}

data class SettingsModel(val mode: LockMode, val availability: PhoneLockAvailability) {
    val canChoosePhoneLock: Boolean get() = availability == PhoneLockAvailability.AVAILABLE

    val phoneNote: String?
        get() = when (availability) {
            PhoneLockAvailability.AVAILABLE -> null
            PhoneLockAvailability.NOT_SET_UP ->
                if (mode == LockMode.PHONE) "Your phone has no screen lock, so OwnTally stays locked until you set one."
                else "Set a screen lock or fingerprint in your phone's settings first."
            PhoneLockAvailability.UNSUPPORTED -> "This phone cannot check a lock for apps."
        }

    fun choose(target: LockMode): ModeChange = when {
        target == mode -> ModeChange.Nothing
        target == LockMode.OFF -> if (availability == PhoneLockAvailability.AVAILABLE) ModeChange.AskThenSet(target) else ModeChange.SetNow(target)
        canChoosePhoneLock -> ModeChange.AskThenSet(target)
        else -> ModeChange.Refused
    }
}

object LockGate {
    /**
     * Show the lock screen whenever the app is locked. If the phone has no screen lock to ask with, the app stays locked until one is
     * set (owner decision, 4 Nov 2026); the person's data is not lost, because our keys are not tied to the screen lock.
     */
    fun mustAsk(state: LockState, @Suppress("UNUSED_PARAMETER") availability: PhoneLockAvailability) = state.locked

    /** Whether the system's prompt can be shown at all. */
    fun canAsk(availability: PhoneLockAvailability) = availability == PhoneLockAvailability.AVAILABLE

    fun lockScreenText(availability: PhoneLockAvailability) = when (availability) {
        PhoneLockAvailability.AVAILABLE -> "Use your fingerprint or your phone's screen lock to open it."
        PhoneLockAvailability.NOT_SET_UP -> "Your phone has no screen lock. Set a screen lock or fingerprint in your phone's settings, then come back."
        PhoneLockAvailability.UNSUPPORTED -> "This phone cannot check a lock for apps, so OwnTally cannot be opened while its lock is on."
    }
}

/** The app covers its own screen whenever it is not in front, so the recent-apps preview shows nothing (owner decision, 4 Nov 2026). */
object CoverRule {
    fun covered(state: androidx.lifecycle.Lifecycle.State) = state != androidx.lifecycle.Lifecycle.State.RESUMED
}
