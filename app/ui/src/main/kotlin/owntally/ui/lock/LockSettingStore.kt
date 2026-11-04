package owntally.ui.lock

import owntally.data.store.SettingsStore

/** Keeps the chosen lock mode. Only the mode is stored: no secret, no count of tries. Reads the database, so call off the main thread. */
class LockSettingStore(private val settings: SettingsStore) {
    fun load(): LockMode = LockMode.fromStored(settings.get(KEY))
    fun save(mode: LockMode) = settings.put(KEY, mode.stored)

    companion object {
        const val KEY = "app_lock"
    }
}
