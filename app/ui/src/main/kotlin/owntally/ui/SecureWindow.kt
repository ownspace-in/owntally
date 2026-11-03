package owntally.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * While [enabled] and on screen, the window is marked secure: Android blocks screenshots and screen recording of it, and hides
 * it in the recent-apps view. Used for the screens that show or ask for the recovery phrase.
 */
@Composable
fun SecureWindow(enabled: Boolean) {
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(window, enabled) {
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (enabled) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
