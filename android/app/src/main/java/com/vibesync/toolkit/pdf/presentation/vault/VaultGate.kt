package com.vibesync.toolkit.pdf.presentation.vault

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.vibesync.toolkit.pdf.R

/** Thin biometric/device-credential boundary. Any authentication error or lockout denies access. */
class VaultGate {
    enum class Decision { GRANTED, DENIED }

    fun authenticate(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val supportsCombinedAuthenticators = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        if (
            supportsCombinedAuthenticators &&
            BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            onResult(false)
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(decisionFor(success = true) == Decision.GRANTED)
                }

                override fun onAuthenticationFailed() {
                    onResult(decisionFor(success = false) == Decision.GRANTED)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(decisionFor(success = false, errorCode = errorCode) == Decision.GRANTED)
                }
            }
        )
        val promptBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.vault_biometric_title))
            .setSubtitle(activity.getString(R.string.vault_biometric_subtitle))
        if (supportsCombinedAuthenticators) {
            promptBuilder.setAllowedAuthenticators(authenticators)
        } else {
            // Android 10 and earlier use the legacy device-credential flag for PIN fallback.
            promptBuilder.setDeviceCredentialAllowed(true)
        }
        val promptInfo = promptBuilder.build()
        prompt.authenticate(promptInfo)
    }

    companion object {
        fun decisionFor(success: Boolean, errorCode: Int? = null): Decision =
            if (success && errorCode == null) Decision.GRANTED else Decision.DENIED
    }
}
