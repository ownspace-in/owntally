package owntally.data.store

/** Small named text settings (the default name and phone, the next key slot, later the app lock choice). Nothing secret. */
interface SettingsStore {
    fun get(key: String): String?
    fun put(key: String, value: String)

    /** Removing a setting that is not there is fine. */
    fun remove(key: String)
}

/** For tests and previews. */
class InMemorySettingsStore : SettingsStore {
    private val values = java.util.TreeMap<String, String>()

    @Synchronized
    override fun get(key: String): String? = values[key]

    @Synchronized
    override fun put(key: String, value: String) {
        values[key] = value
    }

    @Synchronized
    override fun remove(key: String) {
        values.remove(key)
    }

    @Synchronized
    fun all(): Map<String, String> = values.toMap()
}
