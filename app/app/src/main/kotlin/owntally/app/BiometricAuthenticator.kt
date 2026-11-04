package owntally.app

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import owntally.ui.lock.PhoneAuthenticator
import owntally.ui.lock.PhoneLockAvailability

/**
 * The phone's own lock screen, through Android's BiometricPrompt. We store nothing and count nothing: Android shows the prompt,
 * applies its own limits, and tells us yes or no. Android 11 and newer accept a strong fingerprint or face, or the screen lock PIN,
 * pattern or password. Older versions cannot combine the two, so Android 10 accepts the screen lock and Android 9 and older a fingerprint.
 */
class BiometricAuthenticator(private val activity: FragmentActivity) : PhoneAuthenticator {
    private val authenticators: Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> DEVICE_CREDENTIAL
        else -> BIOMETRIC_WEAK
    }

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
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(authenticators).apply {
            // A cancel button is only allowed when the screen lock is not one of the choices.
            if (authenticators and DEVICE_CREDENTIAL == 0) setNegativeButtonText("Cancel")
        }.build()
        try {
            prompt.authenticate(info)
        } catch (e: IllegalArgumentException) {
            onResult(false)
        }
    }
}
