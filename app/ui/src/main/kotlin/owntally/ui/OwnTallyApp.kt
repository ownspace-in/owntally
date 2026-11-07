package owntally.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.currentStateAsState
import owntally.ui.lock.CoverRule
import owntally.ui.lock.LockController
import owntally.ui.lock.LockHost
import owntally.ui.lock.LockSettingStore
import owntally.ui.lock.PhoneAuthenticator
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import owntally.data.session.Sessions
import owntally.ui.components.Notice
import owntally.ui.groups.DebugTools
import owntally.ui.groups.HomeFlow
import owntally.ui.components.Wordmark
import owntally.ui.onboarding.OnboardingFlow
import owntally.ui.onboarding.OnboardingViewModel
import owntally.ui.theme.OwnTallyColors
import owntally.ui.theme.OwnTallyTheme

/** What the app found out about this phone when it started. */
sealed class AppStart {
    data object Loading : AppStart()
    data object NeedsFirstRun : AppStart()
    data object Ready : AppStart()

    /** A seed is stored but cannot be opened (the Keystore key is gone or changed). */
    data object SeedUnreadable : AppStart()

    /** Secure storage could not be set up on this phone. */
    data object StorageUnavailable : AppStart()
}

/** What the lock needs from the app: where its one setting is kept, and the phone's own lock screen. */
class LockDeps(val settings: owntally.data.store.SettingsStore, val authenticator: PhoneAuthenticator)

/** Loads the lock mode (off the main thread), then shows [content] behind the lock. With no [lock] there is none. */
@Composable
private fun WithLock(lock: LockDeps?, content: @Composable (LockController?) -> Unit) {
    if (lock == null) {
        content(null)
        return
    }
    val controller by produceState<LockController?>(null) {
        value = withContext(Dispatchers.IO) {
            val store = LockSettingStore(lock.settings)
            LockController(store.load(), store, lock.authenticator) { android.os.SystemClock.elapsedRealtime() }
        }
    }
    val c = controller
    if (c == null) Box(Modifier.fillMaxSize().background(OwnTallyColors.Background)) else LockHost(c) { content(c) }
}

/** Draws a plain cover over everything whenever the app is not in front, so the recent-apps preview shows nothing of the person's data. */
@Composable
private fun WithCover(content: @Composable () -> Unit) {
    val lifecycleState by androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    Box(Modifier.fillMaxSize()) {
        content()
        if (CoverRule.covered(lifecycleState)) {
            Box(Modifier.fillMaxSize().background(OwnTallyColors.Background), contentAlignment = Alignment.Center) { Wordmark() }
        }
    }
}

/** The root of the app's screens. */
@Composable
fun OwnTallyApp(start: AppStart, onboarding: () -> OnboardingViewModel, sessions: () -> Sessions, debugTools: DebugTools? = null, lock: LockDeps? = null, joinRequests: owntally.data.session.JoinRequests? = null, sync: owntally.data.session.SyncCoordinator? = null) {
    OwnTallyTheme {
        WithCover {
        when (start) {
            AppStart.Loading -> Box(Modifier.fillMaxSize().background(OwnTallyColors.Background))
            AppStart.NeedsFirstRun -> {
                var finished by remember { mutableStateOf(false) }
                if (finished) WithLock(lock) { HomeFlow(sessions(), debugTools, it, joinRequests, sync) } else OnboardingFlow(onboarding(), onFinished = { finished = true })
            }
            AppStart.Ready -> WithLock(lock) { HomeFlow(sessions(), debugTools, it, joinRequests, sync) }
            AppStart.SeedUnreadable -> MessageScreen(
                "Your saved identity can't be opened on this phone",
                "This can happen when the app is restored from a backup or moved to another phone. Restoring from your recovery phrase arrives in a later update.",
            )
            AppStart.StorageUnavailable -> MessageScreen(
                "Secure storage isn't available",
                "OwnTally keeps your keys in this phone's secure storage and can't start without it.",
            )
        }
        }
    }
}

@Composable
private fun MessageScreen(title: String, body: String) {
    Column(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Wordmark()
        Spacer(Modifier.height(32.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = OwnTallyColors.Ink)
        Spacer(Modifier.height(14.dp))
        Notice(body)
    }
}
