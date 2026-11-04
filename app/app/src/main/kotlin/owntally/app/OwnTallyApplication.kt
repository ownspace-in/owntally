package owntally.app

import android.app.Application

class OwnTallyApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
