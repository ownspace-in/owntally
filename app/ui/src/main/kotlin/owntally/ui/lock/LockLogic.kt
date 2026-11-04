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
                if (mode == LockMode.PHONE) "OwnTally's lock is not asking right now because your phone has no screen lock. Set one to use it again."
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
    /** Show the lock screen only if the app is locked and the phone can really check. Otherwise the lock cannot protect anything and must not trap the person. */
    fun mustAsk(state: LockState, availability: PhoneLockAvailability) = state.locked && availability == PhoneLockAvailability.AVAILABLE

}
