package com.example.scifilauncher

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.File

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
                    when (intent.getStringExtra(EXTRA_REASON)) {
                        REASON_MOTION_CONFIRM -> MotionTheftDetector.onConfirmed(applicationContext)
                        REASON_KIOSK_UNLOCK -> ScifiAccessibilityService.instance?.dismissKioskLockCover()
                    }
                    setResult(RESULT_OK)
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    setResult(RESULT_CANCELED)
                    finish()
                }

                // The real "someone tried and failed" signal - fires once per non-matching
                // fingerprint scan, distinct from onAuthenticationError above (which covers the
                // legitimate owner tapping "Deny", cancelling, or a lockout - not an intrusion
                // signal at all). Can fire multiple times in one prompt session if someone keeps
                // trying different fingers. Silent - the person attempting entry has no idea
                // this ran, which is the whole point.
                override fun onAuthenticationFailed() {
                    captureIntruderAttempt()
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

    private fun captureIntruderAttempt() {
        val reason = intent.getStringExtra(EXTRA_TITLE) ?: "Confirmation"
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // No photo without the permission, but still worth logging that a failed attempt
            // happened at all, with whatever location is available.
            recordCapture(null, reason)
            return
        }
        val outputDir = File(getExternalFilesDir(null), "IntruderCaptures")
        SilentCameraCapture.captureFrontFacing(applicationContext, outputDir) { file ->
            recordCapture(file?.absolutePath, reason)
        }
    }

    private fun recordCapture(photoPath: String?, reason: String) {
        val lockPrefs = getSharedPreferences("lock_prefs", MODE_PRIVATE)
        runCatching { captureLastLocation(applicationContext, lockPrefs) }
        val loc = loadLastKnownLocation(lockPrefs)
        IntruderCaptureLog.record(applicationContext, photoPath, loc?.first, loc?.second, reason)

        // Real auto-arm trigger: N failed fingerprint scans in a short window is a direct
        // "someone who isn't the owner is trying to use this phone" signal - doesn't need a
        // confirm-or-arm grace period the way the weaker motion trigger does, since a genuine
        // owner doesn't fail their own fingerprint repeatedly in a few minutes.
        if (!isSequenceModeActive(lockPrefs) && shouldAutoArmFromFailedAttempts(applicationContext)) {
            SystemEventLog.record(applicationContext, "SequenceMode", "Auto-armed: repeated failed fingerprint attempts")
            enterSequenceMode(applicationContext, lockPrefs)
        }
    }

    companion object {
        const val EXTRA_TITLE = "com.example.scifilauncher.extra.BIOMETRIC_TITLE"
        const val EXTRA_SUBTITLE = "com.example.scifilauncher.extra.BIOMETRIC_SUBTITLE"
        const val EXTRA_REASON = "com.example.scifilauncher.extra.BIOMETRIC_REASON"
        const val REASON_MOTION_CONFIRM = "motion_confirm"
        const val REASON_KIOSK_UNLOCK = "kiosk_unlock"
    }
}
