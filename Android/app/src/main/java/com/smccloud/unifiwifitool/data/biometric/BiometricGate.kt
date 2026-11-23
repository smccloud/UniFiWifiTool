package com.smccloud.unifiwifitool.data.biometric

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Thin coroutine wrapper around [BiometricPrompt]. The prompt gates access to the
 * Keystore-backed device token; it is not itself the token's key guard so that
 * "Unpair" can always wipe credentials even when biometrics are unavailable.
 */
class BiometricGate(private val activity: FragmentActivity) {

    fun canAuthenticate(): Boolean {
        val result = BiometricManager.from(activity)
            .canAuthenticate(ALLOWED_AUTHENTICATORS)
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    suspend fun authenticate(
        title: String = "Unlock UniFi WiFi Access Manager",
        subtitle: String = "Authenticate to use the paired device token",
        cancelButtonText: String = "Cancel"
    ): Boolean {
        if (!canAuthenticate()) return false

        return suspendCancellableCoroutine { continuation ->
            val executor = Executor { runnable -> activity.runOnUiThread(runnable) }
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            )

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText(cancelButtonText)
                .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
                .build()

            prompt.authenticate(info)
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    private companion object {
        /**
         * BIOMETRIC_WEAK also accepts Class 3 (strong) sensors and is the only
         * biometric-strength flag that stays valid when combined with a negative
         * button. Device credential is intentionally not offered so the app lock
         * always requires a biometric.
         */
        const val ALLOWED_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK
    }
}
