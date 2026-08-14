package com.example.phonelinkagent

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat

private const val PREFS = "phone_link_agent_prefs"

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_RESUME_CAPTURE = "resume_capture"
    }

    // Set by the paused notification's tap/RESUME action (via intent extra) and consumed once
    // the Activity is actually ready to launch the consent dialog - a plain boolean survives
    // recomposition fine since onCreate/onNewIntent both run well before first composition.
    private var pendingResumeCapture = false

    // Hoisted out of setContent so onResume() can actually update it - Accessibility Service
    // can only be toggled outside this app (system Settings), and the only reliable moment to
    // notice that changed is when this Activity comes back to the foreground.
    private val accessibilityOnState = mutableStateOf(false)

    // Same reasoning as accessibilityOnState - the battery-optimization exemption dialog is a
    // real system dialog outside this app, only checkable again on resume.
    private val batteryUnrestrictedState = mutableStateOf(false)

    private fun isBatteryUnrestricted(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val svcIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            ContextCompat.startForegroundService(this, svcIntent)
            PhoneLinkAccessibilityService.instance?.onCaptureStarted()
        } else {
            PhoneLinkAccessibilityService.instance?.onCaptureDenied()
        }
    }

    private fun beginScreenCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        runCatching { screenCaptureLauncher.launch(mpm.createScreenCaptureIntent()) }
            .onFailure { PhoneLinkAccessibilityService.instance?.onCaptureDenied() }
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun loadDisclosureAcknowledged(): Boolean = prefs().getBoolean("disclosure_ack", false)

    private fun saveDisclosureAcknowledged() {
        prefs().edit().putBoolean("disclosure_ack", true).apply()
    }

    private fun loadOrCreateToken(): String {
        val existing = prefs().getString("token", null)
        if (existing != null && existing.length >= 16) return existing
        val fresh = generateToken()
        prefs().edit().putString("token", fresh).apply()
        return fresh
    }

    private fun regenerateToken(): String {
        val fresh = generateToken()
        prefs().edit().putString("token", fresh).apply()
        return fresh
    }

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingResumeCapture = intent?.getBooleanExtra(EXTRA_RESUME_CAPTURE, false) == true

        setContent {
            val isDark = isSystemInDarkTheme()
            var disclosureAck by remember { mutableStateOf(loadDisclosureAcknowledged()) }
            val accessibilityOn by accessibilityOnState
            val batteryUnrestricted by batteryUnrestrictedState
            var token by remember { mutableStateOf(loadOrCreateToken()) }
            var status by remember {
                mutableStateOf(PhoneLinkAccessibilityService.instance?.currentStatus() ?: PhoneLinkAgentStatus.IDLE)
            }

            DisposableEffect(Unit) {
                PhoneLinkAccessibilityService.instance?.onStatusChanged = { status = it }
                status = PhoneLinkAccessibilityService.instance?.currentStatus() ?: PhoneLinkAgentStatus.IDLE
                // A tap on the "paused" notification landed here specifically to resume -
                // jump straight to the consent dialog instead of making the user find and tap
                // RESUME NOW themselves once more on top of the notification tap they just did.
                if (pendingResumeCapture && status == PhoneLinkAgentStatus.PAUSED) {
                    pendingResumeCapture = false
                    beginScreenCapture()
                }
                onDispose {
                    PhoneLinkAccessibilityService.instance?.onStatusChanged = null
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier, color = MaterialTheme.colorScheme.background) {
                    PhoneLinkAgentScreen(
                        isDark = isDark,
                        disclosureAcknowledged = disclosureAck,
                        onAcknowledgeDisclosure = {
                            saveDisclosureAcknowledged()
                            disclosureAck = true
                        },
                        accessibilityEnabled = accessibilityOn,
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        batteryUnrestricted = batteryUnrestricted,
                        onRequestBatteryUnrestricted = {
                            runCatching {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:$packageName")
                                    )
                                )
                            }.onFailure {
                                // Some OEMs (or a Play-Protect-restricted build) reject this
                                // specific intent - fall back to the general battery settings
                                // screen so the user can still find and grant it manually.
                                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        },
                        token = token,
                        status = status,
                        onStart = {
                            PhoneLinkAccessibilityService.instance?.startSession(token)
                            beginScreenCapture()
                        },
                        onResume = { beginScreenCapture() },
                        onLogOut = {
                            PhoneLinkAccessibilityService.instance?.stopSession()
                            token = regenerateToken()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessibilityOnState.value = PhoneLinkAccessibilityService.instance != null
        batteryUnrestrictedState.value = isBatteryUnrestricted()
    }

    /** Covers the "app was already running" case - onCreate's pendingResumeCapture handles a
     * cold start from the notification tap, but with android:launchMode="singleTop" a tap while
     * the Activity already exists routes here instead, where Compose's DisposableEffect(Unit)
     * (which only runs once per composition) wouldn't otherwise notice a second resume request.
     * Acts directly rather than going through Compose state for that reason. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RESUME_CAPTURE, false) &&
            PhoneLinkAccessibilityService.instance?.currentStatus() == PhoneLinkAgentStatus.PAUSED
        ) {
            beginScreenCapture()
        }
    }
}
