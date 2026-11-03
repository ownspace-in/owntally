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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import owntally.ui.components.Notice
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

/** The root of the app's screens. */
@Composable
fun OwnTallyApp(start: AppStart, onboarding: () -> OnboardingViewModel) {
    OwnTallyTheme {
        when (start) {
            AppStart.Loading -> Box(Modifier.fillMaxSize().background(OwnTallyColors.Background))
            AppStart.NeedsFirstRun -> {
                var finished by remember { mutableStateOf(false) }
                if (finished) GroupsPlaceholder() else OnboardingFlow(onboarding(), onFinished = { finished = true })
            }
            AppStart.Ready -> GroupsPlaceholder()
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

/** Stands in for the Groups screen until it is built (task T3.8). */
@Composable
fun GroupsPlaceholder() {
    Column(Modifier.fillMaxSize().background(OwnTallyColors.Background).safeDrawingPadding().padding(24.dp)) {
        Spacer(Modifier.height(16.dp))
        Wordmark()
        Spacer(Modifier.height(16.dp))
        Box(Modifier.background(OwnTallyColors.InfoBackground, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("Stored only on this phone", style = MaterialTheme.typography.bodyMedium, color = OwnTallyColors.Green)
        }
        Spacer(Modifier.height(48.dp))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Text("No groups yet", style = MaterialTheme.typography.bodyLarge, color = OwnTallyColors.TextSecondary)
        }
    }
}
