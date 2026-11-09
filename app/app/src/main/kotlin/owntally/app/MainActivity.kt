package owntally.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import owntally.ui.AppStart
import owntally.ui.LockDeps
import owntally.ui.OwnTallyApp
import owntally.ui.onboarding.OnboardingViewModel

class MainActivity : FragmentActivity() {
    private val container get() = (application as OwnTallyApplication).container

    private val onboarding: OnboardingViewModel by viewModels {
        viewModelFactory { initializer { OnboardingViewModel(container.firstRun) } }
    }

    override fun onStart() {
        super.onStart()
        container.beginSyncing()
    }

    override fun onStop() {
        container.endSyncing()
        super.onStop()
    }

    /**
     * An invite link that opened or reached the app (M9): kept for the screen to use once the person is past setup and the lock. Then the intent is
     * forgotten, so the link does not stay in this activity or in a saved state, and is not read a second time.
     */
    private fun takeInviteLink(intent: android.content.Intent?) {
        if (!InviteIntents.isViewLink(intent)) return
        container.inviteLinkArrived(InviteIntents.linkFrom(intent))
        setIntent(android.content.Intent(android.content.Intent.ACTION_MAIN))
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        takeInviteLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only when the app is really started by the link: a recreated activity (a turn of the screen, or the system bringing the app back) gets the
        // old intent again, and the link must not be taken twice.
        if (savedInstanceState == null) takeInviteLink(intent)
        enableEdgeToEdge()
        // Android 13 and newer can leave the app's picture out of the recent-apps list; older versions rely on the cover drawn by the app.
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        setContent {
            val start by produceState<AppStart>(AppStart.Loading) { value = withContext(Dispatchers.IO) { container.start() } }
            OwnTallyApp(start, onboarding = { onboarding }, sessions = { container.sessions }, debugTools = container.debugTools, lock = LockDeps(container.settings, BiometricAuthenticator(this)), joinRequests = container.joinRequests, sync = container.sync, invites = container.invites, joinGroup = container.joinGroup, nearby = container.nearby, pendingInvites = container.pendingInvites)
        }
    }
}
