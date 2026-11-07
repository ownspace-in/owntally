package owntally.app

import android.app.Application

class OwnTallyApplication : Application() {
    @Volatile
    private var current: AppContainer? = null

    val container: AppContainer
        get() = current ?: synchronized(this) { current ?: AppContainer(this).also { current = it } }

    /**
     * For tests only: after the stored data and the Keystore key were wiped, the next use makes a new container. A container made before
     * the wipe would still hold the deleted key and the old database.
     */
    fun resetContainerForTests() {
        current = null
    }
}
