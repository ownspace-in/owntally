package owntally.ui.lock

/** How the app is locked. Off, or the phone's own fingerprint or screen lock. (An own PIN was left out by the owner's decision.) */
enum class LockMode(val stored: String) {
    OFF("off"), PHONE("phone");

    companion object {
        /** Anything missing or unknown reads as Off: a damaged setting must not hide the person's data or claim a lock that is not there. */
        fun fromStored(text: String?): LockMode = entries.firstOrNull { it.stored == text } ?: OFF
    }
}

/**
 * Whether the app is locked right now. A value with no Android code: the lock screen and the lifecycle only feed it events and
 * read [locked]. The app asks on every fresh start, and again when it comes back after at least [GRACE_MILLIS] in the background.
 */
data class LockState(val mode: LockMode, val locked: Boolean, val backgroundedAt: Long? = null) {
    fun unlocked() = copy(locked = false, backgroundedAt = null)

    /** The app left the screen. Leaving twice without coming back keeps the first time. */
    fun backgrounded(nowMillis: Long) = if (backgroundedAt == null) copy(backgroundedAt = nowMillis) else this

    /** The app came back. */
    fun foregrounded(nowMillis: Long): LockState {
        val left = backgroundedAt ?: return this
        val away = nowMillis - left
        // A clock that went backwards makes [away] negative: that is treated as too long, never as a free pass.
        val tooLong = away < 0 || away >= GRACE_MILLIS
        return copy(locked = locked || (mode != LockMode.OFF && tooLong), backgroundedAt = null)
    }

    /** The phone itself locked (its screen went off). The app locks at once, with no grace period (owner decision, 4 Nov 2026). */
    fun phoneLocked() = if (mode == LockMode.OFF) this else copy(locked = true, backgroundedAt = null)

    /** The person chose a mode on the Settings screen (after proving it works, when turning it on). Turning it off opens the app. */
    fun withMode(newMode: LockMode) = copy(mode = newMode, locked = locked && newMode != LockMode.OFF, backgroundedAt = null)

    companion object {
        const val GRACE_MILLIS = 30_000L
        fun start(mode: LockMode) = LockState(mode, locked = mode != LockMode.OFF)
    }
}
