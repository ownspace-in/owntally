package owntally.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import owntally.ui.AppStart
import owntally.ui.OwnTallyApp
import owntally.ui.onboarding.OnboardingViewModel

class MainActivity : ComponentActivity() {
    private val container get() = (application as OwnTallyApplication).container

    private val onboarding: OnboardingViewModel by viewModels {
        viewModelFactory { initializer { OnboardingViewModel(container.firstRun) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val start by produceState<AppStart>(AppStart.Loading) { value = withContext(Dispatchers.IO) { container.start() } }
            OwnTallyApp(start, onboarding = { onboarding }, sessions = { container.sessions }, debugTools = container.debugTools)
        }
    }
}
