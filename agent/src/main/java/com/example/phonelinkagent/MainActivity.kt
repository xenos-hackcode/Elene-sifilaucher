package com.example.phonelinkagent

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
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

    // Hoisted out of setContent so onResume() can actually update it - Accessibility Service
    // can only be toggled outside this app (system Settings), and the only reliable moment to
    // notice that changed is when this Activity comes back to the foreground.
    private val accessibilityOnState = mutableStateOf(false)

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

        setContent {
            val isDark = isSystemInDarkTheme()
            var disclosureAck by remember { mutableStateOf(loadDisclosureAcknowledged()) }
            val accessibilityOn by accessibilityOnState
            var token by remember { mutableStateOf(loadOrCreateToken()) }
            var status by remember { mutableStateOf(PhoneLinkAgentStatus.IDLE) }

            DisposableEffect(Unit) {
                PhoneLinkAccessibilityService.instance?.onStatusChanged = { status = it }
                status = if (PhoneLinkAccessibilityService.instance?.isActive() == true) {
                    PhoneLinkAgentStatus.WAITING_FOR_CONTROLLER
                } else {
                    PhoneLinkAgentStatus.IDLE
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
                        token = token,
                        status = status,
                        onStart = {
                            PhoneLinkAccessibilityService.instance?.startSession(token)
                            beginScreenCapture()
                        },
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
    }
}
