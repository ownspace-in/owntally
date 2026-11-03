package owntally.data.store

/** Small named text settings (the default name and phone, the next key slot, later the app lock choice). Nothing secret. */
interface SettingsStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
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
    fun all(): Map<String, String> = values.toMap()
}
