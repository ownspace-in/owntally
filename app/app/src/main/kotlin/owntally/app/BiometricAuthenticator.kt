package owntally.app

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import owntally.ui.lock.PhoneAuthenticator
import owntally.ui.lock.PhoneLockAvailability

/**
 * The phone's own lock screen, through Android's BiometricPrompt. We store nothing and count nothing: Android shows the prompt,
 * applies its own limits, and tells us yes or no. It accepts a strong fingerprint or face, or the screen lock PIN, pattern or
 * password. (The app needs Android 11 or newer, where Android allows both in one prompt.)
 */
class BiometricAuthenticator(private val activity: FragmentActivity) : PhoneAuthenticator {
    private val authenticators: Int = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    override fun availability(): PhoneLockAvailability = when (BiometricManager.from(activity).canAuthenticate(authenticators)) {
        BiometricManager.BIOMETRIC_SUCCESS -> PhoneLockAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> PhoneLockAvailability.NOT_SET_UP
        else -> PhoneLockAvailability.UNSUPPORTED
    }

    override fun authenticate(title: String, onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
            },
        )
        // No cancel button: with the screen lock allowed, Android supplies its own way back.
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(authenticators).build()
        try {
            prompt.authenticate(info)
        } catch (e: IllegalArgumentException) {
            onResult(false)
        }
    }
}
