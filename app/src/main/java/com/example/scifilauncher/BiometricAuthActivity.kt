package com.example.scifilauncher

import android.os.Bundle
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Minimal, dedicated FragmentActivity that only exists to host BiometricPrompt (which
 * requires a FragmentActivity/Fragment host - there's no ComponentActivity overload).
 * Kept separate from MainActivity because FragmentActivity's stricter request-code
 * validation conflicts with the newer Activity Result API launchers used everywhere
 * else in the app (a known AndroidX rough edge). Finishes immediately: RESULT_OK on
 * success, RESULT_CANCELED otherwise.
 */
class BiometricAuthActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    setResult(RESULT_OK)
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(intent.getStringExtra(EXTRA_TITLE) ?: "Confirm it's you")
            .setSubtitle(intent.getStringExtra(EXTRA_SUBTITLE) ?: "Fingerprint required")
            .setNegativeButtonText("Deny")
            .build()

        runCatching { prompt.authenticate(promptInfo) }.onFailure {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    companion object {
        const val EXTRA_TITLE = "com.example.scifilauncher.extra.BIOMETRIC_TITLE"
        const val EXTRA_SUBTITLE = "com.example.scifilauncher.extra.BIOMETRIC_SUBTITLE"
    }
}
