@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.scifilauncher

import android.content.*
import android.util.Log
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import android.provider.Settings
import java.util.Locale
import android.content.ActivityNotFoundException
import android.widget.Toast
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import android.content.Context
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import java.io.File
import android.view.View
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.example.scifilauncher.ui.theme.SciFiLauncherTheme

private const val KEY_USER_NAME = "user_name"
const val UNINSTALL_STATUS_ACTION = "com.example.scifilauncher.UNINSTALL_STATUS"

private fun formatMinutesForSpeech(totalMinutes: Int): String {
    val hrs = totalMinutes / 60
    val mins = totalMinutes % 60
    return when {
        hrs > 0 && mins > 0 -> "$hrs hour${if (hrs != 1) "s" else ""} and $mins minute${if (mins != 1) "s" else ""}"
        hrs > 0 -> "$hrs hour${if (hrs != 1) "s" else ""}"
        else -> "$mins minute${if (mins != 1) "s" else ""}"
    }
}

// Elene bubble visual/interaction state.
// DORMANT: hidden, waiting to be called. LISTENING: mic active (white glow).
// REPLYING: awaiting/speaking the answer (red/green/blue glitch glow).
// UNRESPONSIVE: recognition or the network call failed (black, briefly).
enum class EleneBubbleState { DORMANT, LISTENING, REPLYING, UNRESPONSIVE }

class MainActivity : androidx.activity.ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var eleneLastSpokenText: String? = null
    private var eleneSpeechFailed: Boolean = false
    private var pendingSpeechDoneCallback: (() -> Unit)? = null

    // The overlay bubble (ScifiAccessibilityService) is the only Elene command handler now -
    // for the handful of commands that genuinely need a visible Activity UI (Settings/Security
    // navigation, the Apps screen's search box, the screen-record consent dialog, the
    // confirmation panel, PIN entry), it brings this Activity to front carrying the raw command
    // string, onNewIntent stashes it as Compose state, and a LaunchedEffect inside setContent
    // (same scope handleEleneCommand and all the real showApps/hiddenApps/etc. state lives in)
    // runs it through the exact same handleEleneCommand dispatcher a home-screen-originated
    // command would use - one command handler, two possible entry points into it.
    private var eleneBridgeCommand by androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_ELENE_COMMAND)?.let { eleneBridgeCommand = it }
    }

    private val sequenceModePermissionsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Sequence Mode gracefully skips whatever wasn't granted */ }

    private val deviceAdminLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Success is silent - the toggle itself highlighting is enough feedback. Denial still
        // gets a spoken explanation since that's an actual problem, not just a state change.
        if (result.resultCode != RESULT_OK) {
            reportPermissionDenied("OS-level lockdown", retry = { requestDeviceAdmin() })
        }
    }

    private val bluetoothEnableLauncher: androidx.activity.result.ActivityResultLauncher<Intent> = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            reportPermissionDenied("Turning on Bluetooth", retry = { launchBluetoothEnableRequest() })
        }
    }

    private fun launchBluetoothEnableRequest() {
        runCatching {
            bluetoothEnableLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    private var screenRecordActiveState: ((Boolean) -> Unit)? = null
    private var pendingRecordAudioMode = RecordAudioMode.NONE
    private var pendingRecordCropRect: android.graphics.Rect? = null

    private val screenRecordLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val svcIntent = Intent(this, ScreenRecordService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
                putExtra("audioMode", pendingRecordAudioMode.name)
                pendingRecordCropRect?.let { putExtra("cropRect", it) }
            }
            ContextCompat.startForegroundService(this, svcIntent)
            screenRecordActiveState?.invoke(true)
        } else {
            reportPermissionDenied("Screen recording")
            screenRecordActiveState?.invoke(false)
        }
    }

    private var pendingCaptureMode: String? = null

    /** Consent flow for Elene's screen-perception capture (describe_screen / play_game) -
     * mirrors screenRecordLauncher above exactly, since MediaProjectionManager's consent intent
     * can only be resolved via an Activity. Orchestration (deciding when to actually request a
     * frame, calling the backend, dispatching gestures) all lives in ScifiAccessibilityService,
     * not here - this is purely "show the system prompt, start the isolated capture service,
     * hand control back" since MainActivity and the accessibility service share this process
     * and can call each other directly, unlike ScreenPerceptionService in :recorder. */
    private val screenCaptureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val mode = pendingCaptureMode
        if (result.resultCode == RESULT_OK && result.data != null && mode != null) {
            val svcIntent = Intent(this, ScreenPerceptionService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
                putExtra("mode", mode)
            }
            ContextCompat.startForegroundService(this, svcIntent)
            ScifiAccessibilityService.instance?.onPerceptionCaptureStarted(mode == "single")
            moveTaskToBack(true)
        } else {
            ScifiAccessibilityService.instance?.onPerceptionCaptureDenied()
        }
    }

    private fun beginScreenCapture(mode: String) {
        pendingCaptureMode = mode
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        runCatching { screenCaptureLauncher.launch(mpm.createScreenCaptureIntent()) }
            .onFailure { ScifiAccessibilityService.instance?.onPerceptionCaptureDenied() }
    }

    /** Entry point for starting a recording with the options chosen on ScreenRecordSetupScreen -
     * [cropRect] is null for full screen, or the region picked via CropSelectorOverlay (in raw
     * screen-pixel coordinates) to record only that part of the screen. */
    private fun beginScreenRecording(
        audioMode: RecordAudioMode,
        cropRect: android.graphics.Rect?,
        onResult: (Boolean) -> Unit
    ) {
        pendingRecordAudioMode = audioMode
        pendingRecordCropRect = cropRect
        screenRecordActiveState = onResult
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        runCatching { screenRecordLauncher.launch(mpm.createScreenCaptureIntent()) }
            .onFailure { onResult(false) }
    }

    /** A real snapshot of whatever's currently on screen, shown behind the partial-screen crop
     * selector so you're aligning the region against actual content instead of a blank screen -
     * it's just a positioning reference (the real recording later captures whatever app is in
     * focus), not a preview of what the recording itself will contain. */
    private fun captureScreenBitmap(): android.graphics.Bitmap? = runCatching {
        val view = window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        bmp
    }.getOrNull()

    // Reused by both the generic "Scan QR" quick control and the laptop-pairing flow -
    // when a caller supplies a callback, the scanned text goes there instead of the
    // default open-URL/toast behavior.
    private var qrScanResultCallback: ((String) -> Unit)? = null

    private val qrScanLauncher = registerForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        val text = result.contents
        val callback = qrScanResultCallback
        qrScanResultCallback = null
        if (!text.isNullOrBlank()) {
            when {
                callback != null -> callback(text)
                text.startsWith("http://") || text.startsWith("https://") ->
                    runCatching { openUrlInPreferredBrowser(this@MainActivity, text) }
                else -> Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchQrScan(onResult: ((String) -> Unit)? = null) {
        qrScanResultCallback = onResult
        runCatching {
            qrScanLauncher.launch(
                com.journeyapps.barcodescanner.ScanOptions()
                    .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }.onFailure {
            qrScanResultCallback = null
        }
    }

    private fun loadLaptopToken(): String? =
        getSharedPreferences("laptop_control_prefs", MODE_PRIVATE).getString("token", null)

    private fun saveLaptopToken(token: String) {
        getSharedPreferences("laptop_control_prefs", MODE_PRIVATE).edit().putString("token", token).apply()
    }

    private fun forgetLaptopToken() {
        getSharedPreferences("laptop_control_prefs", MODE_PRIVATE).edit().remove("token").apply()
    }

    private val nearbyDevicesPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { /* NearbyDevicesScreen re-reads hasNearbyDevicesPermissions() on recompose */ }

    private val callScreeningRoleLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { /* SecurityScreen re-reads hasCallScreeningRole() on the next tap/recompose - the role
         grant prompt is a real system UI, not something this app controls the outcome of. */ }

    private fun requestCallScreeningRole() {
        val intent = requestCallScreeningRoleIntent(this)
        if (intent != null) {
            runCatching { callScreeningRoleLauncher.launch(intent) }
        } else {
            Toast.makeText(this, "Call screening isn't available on this Android version.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNearbyDevicesPermissions() {
        val perms = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            perms += android.Manifest.permission.BLUETOOTH_SCAN
            perms += android.Manifest.permission.BLUETOOTH_CONNECT
        }
        runCatching { nearbyDevicesPermissionLauncher.launch(perms.toTypedArray()) }
    }

    private fun hasNearbyDevicesPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val scan = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        return fine && scan
    }

    private val vpnConsentLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("TrackerBlockVpn", "Consent dialog result: resultCode=${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            startService(Intent(this, TrackerBlockVpnService::class.java))
        } else {
            reportPermissionDenied("Tracker & ad blocking", retry = { toggleTrackerBlocking(true) })
        }
    }

    private val speechRecognitionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val spoken = results?.firstOrNull()?.trim()
        if (result.resultCode == RESULT_OK && !spoken.isNullOrBlank()) {
            eleneLastSpokenText = spoken
        } else {
            // No speech recognized, or the recognizer was cancelled/failed.
            eleneSpeechFailed = true
        }
    }

    // 2-Step Verify's voice option: a spoken passphrase, matched via speech-to-text - not
    // real voiceprint verification. Kept entirely separate from Elene's own speech launcher
    // so a passphrase attempt never gets sent to Elene's backend as a chat message.
    private var pendingVoicePassphraseCallback: ((spokenText: String?) -> Unit)? = null

    private val voicePassphraseLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingVoicePassphraseCallback
        pendingVoicePassphraseCallback = null
        val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val spoken = results?.firstOrNull()?.trim()
        callback?.invoke(if (result.resultCode == RESULT_OK) spoken else null)
    }

    private fun captureVoicePassphrase(prompt: String, onResult: (spokenText: String?) -> Unit) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
        pendingVoicePassphraseCallback = onResult
        runCatching { voicePassphraseLauncher.launch(intent) }.onFailure {
            pendingVoicePassphraseCallback = null
            onResult(null)
        }
    }

    // Every device-owner-level action goes through this before it happens. Never
    // auto-approves on silence - the caller is responsible for snoozing on a timeout.
    private var pendingConfirmationApprove: (() -> Unit)? = null
    // Optional - most callers don't need to react to a denial, only ActionLog does by default.
    // Added for update proposals, which need their own log kept in sync on both outcomes.
    private var pendingConfirmationDeny: (() -> Unit)? = null

    private var pendingBiometricCallback: Pair<() -> Unit, () -> Unit>? = null

    private var pendingBiometricLabel: String = "Device action"

    private val biometricAuthLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingBiometricCallback
        pendingBiometricCallback = null
        val outcome = if (result.resultCode == RESULT_OK) "APPROVED" else "DENIED/CANCELLED"
        SystemEventLog.record(this, "Biometric", "$pendingBiometricLabel: $outcome")
        if (result.resultCode == RESULT_OK) {
            callback?.first?.invoke()
        } else {
            callback?.second?.invoke()
        }
    }

    /** Being Device Owner can trigger Samsung/Knox to defensively lock down USB debugging
     * (greyed-out toggle, "blocked" behavior) as a reaction to a management app being present -
     * that's Knox's default posture, not something this app's code asked for. As Device Owner,
     * this app has the standing to explicitly clear that restriction back off again. Runs on
     * every launch; a no-op if this app isn't Device Owner or the restriction isn't set. */
    /** Device Owner can grant runtime ("dangerous") permissions with zero user interaction -
     * no reason to make the user tap through a popup for permissions this app is entitled to
     * grant itself. This is what closed a real gap: BLUETOOTH_SCAN was declared in the
     * manifest for Nearby Devices but never actually in the upfront request batch, so it sat
     * ungranted until someone happened to open that specific screen. Returns whichever
     * permissions this couldn't silently grant (not Device Owner, or the call failed), so the
     * caller can still fall back to the normal interactive dialog for those. */
    private fun grantAllDangerousPermissionsSilently(): List<String> {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val admin = SequenceDeviceAdminReceiver.componentName(this)
        val isDeviceOwner = dpm?.isDeviceOwnerApp(packageName) == true

        val allDangerous = buildList {
            add(android.Manifest.permission.RECORD_AUDIO)
            add(android.Manifest.permission.CAMERA)
            add(android.Manifest.permission.READ_CONTACTS)
            add(android.Manifest.permission.SEND_SMS)
            add(android.Manifest.permission.READ_CALENDAR)
            add(android.Manifest.permission.READ_PHONE_STATE)
            add(android.Manifest.permission.READ_CALL_LOG)
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                add(android.Manifest.permission.ANSWER_PHONE_CALLS)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
                add(android.Manifest.permission.BLUETOOTH_SCAN)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        val stillNeeded = mutableListOf<String>()
        for (perm in allDangerous) {
            val alreadyGranted = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            if (alreadyGranted) continue
            val silentlyGranted = isDeviceOwner && runCatching {
                dpm!!.setPermissionGrantState(
                    admin, packageName, perm,
                    android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            }.getOrDefault(false)
            if (!silentlyGranted) stillNeeded.add(perm)
        }
        return stillNeeded
    }

    private fun clearDebuggingRestrictionIfDeviceOwner() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val admin = SequenceDeviceAdminReceiver.componentName(this)
        runCatching {
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_DEBUGGING_FEATURES)
        }.onFailure { Log.e("DeviceOwner", "Failed to clear DISALLOW_DEBUGGING_FEATURES", it) }
    }

    /** As Device Owner, this app can configure exactly what stays reachable during Kiosk
     * mode (Screen Pinning). LOCK_TASK_FEATURE_NONE means the real status bar / notification
     * shade / home / recents / power menu all stay blocked - our own notification bar panel
     * is meant to replace them, not sit alongside them. Also registers this app as an allowed
     * lock-task package so startLockTask() pins silently instead of showing the one-time
     * system confirmation. Runs on every launch; a no-op if not Device Owner. */
    private fun applyKioskLockTaskFeatures() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val admin = SequenceDeviceAdminReceiver.componentName(this)
        runCatching {
            // Kiosk mode still hides system chrome (LOCK_TASK_FEATURE_NONE below), but every
            // currently-installed launchable app is also allowed through Lock Task, so pinning
            // doesn't trap the user inside only the launcher - they can still open and switch
            // between their real apps. This needs re-running whenever the app list changes
            // (also called from onResume), since newly installed apps aren't retroactively
            // allowed until this runs again.
            val launchablePackages = packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0
            ).map { it.activityInfo.packageName }.toSet()
            val allowed = (launchablePackages + packageName).toTypedArray()
            dpm.setLockTaskPackages(admin, allowed)
            dpm.setLockTaskFeatures(admin, android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        }.onFailure { Log.e("DeviceOwner", "Failed to configure lock task features", it) }
    }

    // Confirmed by direct testing: Device Owner's setGlobalSetting(AIRPLANE_MODE_ON) only
    // flips the raw setting value - the radios never actually respond, because actually
    // enforcing airplane mode also requires broadcasting the protected
    // ACTION_AIRPLANE_MODE_CHANGED intent, which no non-system app (Device Owner or not) is
    // allowed to send. A toggle that shows "on" without the radios actually being off is
    // worse than no toggle at all, so this deep-links to the real settings instead of
    // pretending to flip it silently.
    private fun openAirplaneModeSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }
    }

    private fun isAirplaneModeOn(): Boolean =
        Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    /** Reads Android's already-cached last-known location (no active GPS request) so the
     * Security screen can show something more useful than "None yet" even before Sequence
     * Mode has ever actually triggered and recorded its own location snapshot. */
    private fun readCachedDeviceLocation(): Triple<Double, Double, Long>? {
        val hasPermission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return null
        return runCatching {
            val lm = getSystemService(LOCATION_SERVICE) as? android.location.LocationManager ?: return@runCatching null
            lm.getProviders(true)
                .mapNotNull { provider -> lm.getLastKnownLocation(provider) }
                .maxByOrNull { it.time }
                ?.let { Triple(it.latitude, it.longitude, it.time) }
        }.getOrNull()
    }

    private fun isBluetoothOn(): Boolean {
        val hasPermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return false
        return runCatching { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true }
            .getOrDefault(false)
    }

    // ---- Extra quick-settings controls ----
    // Real, working toggles where Android actually allows it (volume, location, DND,
    // brightness). Everything else below is an honest deep link to the real system screen -
    // same reasoning as openAirplaneModeSettings() above: Android simply doesn't expose a
    // public API for mobile data / hotspot / wifi calling / multi-window toggles to any app,
    // Device Owner included, so pretending to flip them would be a fake toggle.

    private fun currentVolumeMode(): VolumeModeOption {
        val am = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager ?: return VolumeModeOption.RING
        return when (am.ringerMode) {
            android.media.AudioManager.RINGER_MODE_SILENT -> VolumeModeOption.SILENT
            android.media.AudioManager.RINGER_MODE_VIBRATE -> VolumeModeOption.VIBRATE
            else -> VolumeModeOption.RING
        }
    }

    private fun cycleVolumeMode(): VolumeModeOption {
        val am = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager ?: return currentVolumeMode()
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val next = when (currentVolumeMode()) {
            VolumeModeOption.RING -> VolumeModeOption.VIBRATE
            VolumeModeOption.VIBRATE -> VolumeModeOption.SILENT
            VolumeModeOption.SILENT -> VolumeModeOption.RING
        }
        if (next == VolumeModeOption.SILENT && !nm.isNotificationPolicyAccessGranted) {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            return currentVolumeMode()
        }
        runCatching {
            am.ringerMode = when (next) {
                VolumeModeOption.RING -> android.media.AudioManager.RINGER_MODE_NORMAL
                VolumeModeOption.VIBRATE -> android.media.AudioManager.RINGER_MODE_VIBRATE
                VolumeModeOption.SILENT -> android.media.AudioManager.RINGER_MODE_SILENT
            }
        }
        return currentVolumeMode()
    }

    private fun openMobileDataSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } }
    }

    private fun openHotspotSettings() {
        runCatching { startActivity(Intent("android.settings.WIFI_TETHER_SETTINGS")) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } }
    }

    private fun openBatterySaverSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
    }

    private fun openWifiCallingSettings() {
        runCatching { startActivity(Intent("android.settings.WIFI_CALLING_SETTINGS")) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } }
    }

    private fun openMultiWindowSettings() {
        // No public Android API/settings screen for this exists on every device - best-effort
        // Samsung path, falling back to Display settings rather than doing nothing.
        runCatching { startActivity(Intent("com.samsung.android.settings.SplitScreenSettingsActivity")) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } }
    }

    private fun openSecureFolder() {
        val launchIntent = packageManager.getLaunchIntentForPackage("com.samsung.knox.securefolder")
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "Secure Folder isn't set up on this device.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAudioSharingSettings() {
        // Samsung "Audio sharing" lives nested inside Bluetooth settings - no direct action.
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    private fun isLocationOn(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            return (getSystemService(LOCATION_SERVICE) as? android.location.LocationManager)?.isLocationEnabled == true
        }
        return runCatching {
            Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
        }.getOrDefault(false)
    }

    private fun toggleLocation(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val admin = SequenceDeviceAdminReceiver.componentName(this)
        val target = !isLocationOn()
        if (dpm != null && dpm.isDeviceOwnerApp(packageName) && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val ok = runCatching { dpm.setLocationEnabled(admin, target) }.isSuccess
            if (!ok) runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        } else {
            runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }
        return isLocationOn()
    }

    private fun isDndOn(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        return nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun toggleDnd(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            return isDndOn()
        }
        runCatching {
            nm.setInterruptionFilter(
                if (isDndOn()) android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                else android.app.NotificationManager.INTERRUPTION_FILTER_NONE
            )
        }
        return isDndOn()
    }

    private fun toggleEyeComfort(currentlyOn: Boolean): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            return currentlyOn
        }
        return if (currentlyOn) {
            stopService(Intent(this, EyeComfortOverlayService::class.java))
            false
        } else {
            runCatching { startService(Intent(this, EyeComfortOverlayService::class.java)) }
            true
        }
    }

    /** Stop-only now - starting a recording goes through ScreenRecordSetupScreen and
     * beginScreenRecording() instead, since starting needs the area/audio/show-taps choices
     * made there first. Kept as a Boolean-taking function only because a couple of call sites
     * pass the current state in for symmetry with other quick-toggle actions. */
    private fun toggleScreenRecord(currentlyRecording: Boolean, onResult: (Boolean) -> Unit) {
        if (!currentlyRecording) return
        // Routed through an action Intent rather than stopService() directly - stopService()
        // jumps straight to onDestroy() with no chance to finish baking any blur marks into
        // the recording first, so this and the in-overlay STOP button share one exit path.
        runCatching {
            startService(Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP_AND_FINISH))
        }
        onResult(false)
    }

    // Cedal SMS relies on a background service to relay messages - Android's battery/doze
    // management will happily kill that unless the app is explicitly exempted. This only
    // opens the real system "ignore battery optimizations" confirmation dialog for it, never
    // a silent grant (there is no silent grant available to any app, Device Owner included).
    private fun isPackageInstalled(pkg: String): Boolean =
        runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun isIgnoringBatteryOptimizations(pkg: String): Boolean {
        val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(pkg) }.getOrDefault(true)
    }

    private fun requestIgnoreBatteryOptimizations(pkg: String) {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))
        }
    }

    private fun currentBrightnessPercent(): Int {
        val raw = runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        return (raw * 100 / 255).coerceIn(1, 100)
    }

    private fun setBrightnessPercent(percent: Int) {
        if (!Settings.System.canWrite(this)) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
            return
        }
        val raw = (percent.coerceIn(1, 100) * 255 / 100)
        runCatching { Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw) }
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                SequenceDeviceAdminReceiver.componentName(this@MainActivity)
            )
            putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_description)
            )
        }
        runCatching { deviceAdminLauncher.launch(intent) }
    }

    private fun toggleTrackerBlocking(enable: Boolean) {
        if (!enable) {
            Log.d("TrackerBlockVpn", "Stopping tracker-block service")
            stopService(Intent(this, TrackerBlockVpnService::class.java))
            return
        }
        val consentIntent = runCatching { android.net.VpnService.prepare(this) }
            .onFailure { Log.e("TrackerBlockVpn", "VpnService.prepare() threw", it) }
            .getOrNull()

        Log.d("TrackerBlockVpn", "VpnService.prepare() returned ${if (consentIntent != null) "an intent (consent needed)" else "null (already permitted)"}")

        if (consentIntent != null) {
            runCatching { vpnConsentLauncher.launch(consentIntent) }
                .onFailure {
                    Log.e("TrackerBlockVpn", "Failed to launch VPN consent dialog", it)
                    Toast.makeText(this, "Could not open the VPN permission dialog: ${it.message}", Toast.LENGTH_LONG).show()
                }
        } else {
            Log.d("TrackerBlockVpn", "Starting tracker-block service directly (already permitted)")
            runCatching { startService(Intent(this, TrackerBlockVpnService::class.java)) }
                .onFailure { Log.e("TrackerBlockVpn", "startService() threw", it) }
        }
    }

    // launcher-wide apps state so we can refresh onResume
    private var allAppsState by mutableStateOf(listOf<AppItem>())

    // assistant state
    private var waitingForReadConfirmation: Boolean = false
    private var readingAllMissed: Boolean = false
    private var currentReadIndex: Int = 0

    private var askingWhichToReply: Boolean = false
    private var askingWhichPerson: Boolean = false

    // reply state
    private var waitingForReplyText: Boolean = false
    private var replyTargetKey: String? = null
    private var replyTargetTitle: String? = null
    private var replyTargetAppName: String? = null
    private var pendingAppForReply: String? = null   // lowercase app key like "whatsapp"

    // Permission-denial conversation state: when a permission/consent dialog is declined,
    // Elene explains it instead of failing silently, then walks through "was that on purpose?"
    // -> "want me to ask again?" rather than just retrying blindly.
    private var pendingDenialRetry: (() -> Unit)? = null
    private var awaitingDenialFollowup: Boolean = false
    private var awaitingReaskConfirmation: Boolean = false

    private fun reportPermissionDenied(what: String, retry: (() -> Unit)? = null) {
        pendingDenialRetry = retry
        awaitingDenialFollowup = retry != null
        awaitingReaskConfirmation = false
        val followup = if (retry != null) " Did you mean to deny that by mistake, or should we change the plan?" else ""
        speak("Negative. $what was denied, so I can't do that.$followup")
    }

    private fun currentLanguage(): LanguageOption {
        val themePrefs = getSharedPreferences("theme_prefs", MODE_PRIVATE)
        return loadLanguage(themePrefs)
    }

    private fun continuousListeningEnabled(): Boolean =
        getSharedPreferences("theme_prefs", MODE_PRIVATE).getBoolean("elene_continuous_listening", true)

    /** "Ask me in 45 minutes" overrides the 30-minute default snooze - a simple, local parse
     * rather than a backend round-trip, since it only needs to recognize a number plus
     * hour/minute words. Returns null (caller falls back to the 30-minute default) if nothing
     * resembling a duration was said. */
    private fun parseDurationMinutes(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val lower = text.lowercase()
        val hours = Regex("(\\d+)\\s*h(our)?s?").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = Regex("(\\d+)\\s*m(in)?(ute)?s?").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + mins
        return if (total > 0) total else null
    }

    fun fontSizeOptionToSp(option: FontSizeOption): Float {
        return when (option) {
            FontSizeOption.SMALL -> 14f
            FontSizeOption.NORMAL -> 16f
            FontSizeOption.LARGE -> 18f
            FontSizeOption.HUGE -> 20f
        }
    }

    fun loadUserName(prefs: SharedPreferences): String? {
        return prefs.getString(KEY_USER_NAME, null)
    }

    fun savePhonePin(prefs: SharedPreferences, pin: String, recovery: String) {
        prefs.edit()
            .putString("phone_pin", pin)
            .putString("phone_pin_recovery", recovery)
            .apply()
    }

    companion object {
        private const val VOICE_ID = "elene_tts_id"
        const val EXTRA_ELENE_COMMAND = "com.example.scifilauncher.extra.ELENE_COMMAND"
    }

    private fun openPlayStoreForRating() {
        val pkg = packageName
        try {
            // Try Play Store app
            val uri = Uri.parse("market://details?id=$pkg")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Fallback: browser
            val uri = Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
        }
    }

    private fun openPlayStoreForFeedback() {
        openPlayStoreForRating()
    }

    private fun openPrivacyPolicyPage() {
        openUrlInPreferredBrowser(this, "https://xenos-hackcode.github.io/scifilauncher-privacy/")
    }


    private fun openHomeSelectorSettings() {
        try {
            // API 21+ – opens "Home app" picker directly
            val intent = Intent(Settings.ACTION_HOME_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: open app details so user can clear defaults if needed
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    data class IntruderLog(
        val packageName: String,
        val appName: String,
        val count: Int,
        val lastTime: Long,
        val allTimes: List<Long>
    )

    private val intruderLogs = mutableStateListOf<IntruderLog>()

    // Broadcast receiver for notification announcements
    // Device Owner apps can silently uninstall via PackageInstaller - no OS confirmation
    // dialog pops up - which is exactly the point: the confirmation the user sees is this
    // app's own DeviceActionConfirmationPanel, not Android's, so it goes through the same
    // logged/spoken/never-silently-approved flow as every other device-owner action.
    private val uninstallStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE)
                ?: android.content.pm.PackageInstaller.STATUS_FAILURE
            val pkg = intent?.getStringExtra(android.content.pm.PackageInstaller.EXTRA_PACKAGE_NAME)
            if (status != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                val message = intent?.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.e("Uninstall", "Failed to uninstall $pkg: $message")
                Toast.makeText(this@MainActivity, "Couldn't uninstall: ${message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Actions are stored as plain data and executed by ScheduledActionReceiver via
    // AlarmManager, not as an in-memory closure - this needs to still fire correctly even if
    // this app process is long gone by the time the delay elapses.
    private fun scheduleAction(type: String, target: String, label: String, delayMillis: Long) {
        val id = System.currentTimeMillis()
        val executeAt = System.currentTimeMillis() + delayMillis
        ScheduledActions.record(this, ScheduledAction(id, type, target, label, executeAt, System.currentTimeMillis()))

        val intent = Intent(this, ScheduledActionReceiver::class.java).apply {
            putExtra("id", id)
        }
        val pending = android.app.PendingIntent.getBroadcast(
            this, id.toInt(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        runCatching {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, executeAt, pending)
        }.onFailure {
            runCatching { am.set(android.app.AlarmManager.RTC_WAKEUP, executeAt, pending) }
        }
    }

    private fun performSilentUninstall(pkg: String) {
        val statusIntent = Intent(UNINSTALL_STATUS_ACTION).setPackage(packageName)
        val pending = android.app.PendingIntent.getBroadcast(
            this, pkg.hashCode(), statusIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )
        runCatching {
            packageManager.packageInstaller.uninstall(pkg, pending.intentSender)
        }.onFailure {
            Log.e("Uninstall", "uninstall() call failed for $pkg", it)
            Toast.makeText(this, "Couldn't start uninstall: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationVoiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val appName = intent?.getStringExtra("appName") ?: "an app"
            val lang = currentLanguage()
            val phonePrefs = getSharedPreferences("phone_prefs", MODE_PRIVATE)
            val userName = loadUserName(phonePrefs)

            speak(
                elenePhrase(
                    "notif_from_app",
                    lang,
                    userName,     // this fills $name
                    appName       // this fills %s
                )
            )
        }
    }

    // allAppsState previously only refreshed on onCreate/onResume/post-uninstall - an app
    // installed while this launcher never left the background (e.g. installed via a link from
    // inside another app, without ever coming home first) stayed invisible to both the App
    // grid and Elene's voice matching until the next resume. This keeps it live.
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            allAppsState = loadAllApps(packageManager)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Captures crashes into the in-app Log screen ("log art") - separate from Requests,
        // which tracks actions taken regarding other apps, not this app's own bugs.
        val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { ErrorLog.record(applicationContext, "CRASH", throwable.stackTraceToString()) }
            defaultExceptionHandler?.uncaughtException(thread, throwable)
        }

        clearDebuggingRestrictionIfDeviceOwner()
        applyKioskLockTaskFeatures()
        enableEdgeToEdge()

        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        tts = TextToSpeech(this, this)

        val pm: PackageManager = packageManager
        allAppsState = loadAllApps(pm)

        // No-op unless the user has enabled Cedal Shared System in Security.
        CedalSharedSystem.broadcastToSiblings(this)

        // Every dangerous runtime permission this app declares in the manifest, requested
        // upfront rather than piecemeal - already-granted ones are silently skipped by the
        // system, so this is safe to call unconditionally on every launch.
        val stillNeededPermissions = grantAllDangerousPermissionsSilently()
        if (stillNeededPermissions.isNotEmpty()) {
            sequenceModePermissionsLauncher.launch(stillNeededPermissions.toTypedArray())
        }

        ContextCompat.registerReceiver(
            this,
            notificationVoiceReceiver,
            IntentFilter("com.example.scifilauncher.NEW_NOTIFICATION_VOICE"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            uninstallStatusReceiver,
            IntentFilter(UNINSTALL_STATUS_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            packageChangeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            // SciFiLauncherTheme defaulted to isSystemInDarkTheme() + dynamic Material-You
            // colors, both completely disconnected from this app's own Dark/Light setting -
            // every stock Material3 component (AlertDialog, Button, OutlinedTextField...)
            // that relies on MaterialTheme.colorScheme was following the PHONE's system theme
            // and wallpaper colors instead, regardless of what was chosen in this app's own
            // Settings. That's what made dialogs in Security/Notifications/Quick Settings
            // ignore the toggle. Declared once, up here, so the same state drives both the M3
            // theme AND the isDark used throughout the rest of the app below (via closure,
            // not a second independent copy).
            val themePrefs = getSharedPreferences("theme_prefs", MODE_PRIVATE)
            var darkModeOption by remember { mutableStateOf(loadDarkMode(themePrefs)) }
            val isDark = darkModeOption == DarkModeOption.DARK

            SciFiLauncherTheme(darkTheme = isDark, dynamicColor = false) {
                val context = LocalContext.current

                val lockPrefs = getSharedPreferences("lock_prefs", MODE_PRIVATE)
                var trackerBlockingEnabled by remember { mutableStateOf(TrackerBlockVpnService.isRunning) }
                var cedalSharedSystemEnabled by remember {
                    mutableStateOf(CedalSharedSystem.isEnabled(CedalSharedSystem.prefs(this@MainActivity)))
                }
                var kioskModeEnabled by rememberSaveable {
                    mutableStateOf(getSharedPreferences("lock_prefs", MODE_PRIVATE).getBoolean("kiosk_mode_enabled", false))
                }
                var voiceActivationEnabled by rememberSaveable {
                    mutableStateOf(lockPrefs.getBoolean("voice_activation_enabled", false))
                }
                var commandWord by rememberSaveable {
                    mutableStateOf(lockPrefs.getString("voice_command_word", "elene") ?: "elene")
                }
                var listeningInfoSeen by rememberSaveable {
                    mutableStateOf(lockPrefs.getBoolean("listening_info_seen", false))
                }

                var pendingShowListeningInfo by rememberSaveable { mutableStateOf(false) }
                var showFreezer by rememberSaveable { mutableStateOf(false) }
                var showStorage by rememberSaveable { mutableStateOf(false) }
                var showFileBrowser by rememberSaveable { mutableStateOf(false) }
                var showRequests by rememberSaveable { mutableStateOf(false) }
                var showUpdates by rememberSaveable { mutableStateOf(false) }
                var showAppLog by rememberSaveable { mutableStateOf(false) }
                var showCommands by rememberSaveable { mutableStateOf(false) }
                var showNearbyDevices by rememberSaveable { mutableStateOf(false) }
                var showLaptopControl by rememberSaveable { mutableStateOf(false) }
                var laptopTokenState by rememberSaveable { mutableStateOf(loadLaptopToken()) }
                var showInstallFlags by rememberSaveable { mutableStateOf(false) }
                var showCapabilities by rememberSaveable { mutableStateOf(false) }
                var showLocationHistory by rememberSaveable { mutableStateOf(false) }
                var appsSearchQuery by rememberSaveable { mutableStateOf("") }
                var showScreenRecordSetup by rememberSaveable { mutableStateOf(false) }
                var pendingRecordArea by remember { mutableStateOf<android.graphics.Rect?>(null) }
                var showCropSelector by rememberSaveable { mutableStateOf(false) }
                var cropBackgroundBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                var locationHistoryEnabledState by rememberSaveable {
                    mutableStateOf(getSharedPreferences("lock_prefs", MODE_PRIVATE).getBoolean("location_history_enabled", false))
                }
                var installWatchEnabledState by rememberSaveable {
                    mutableStateOf(getSharedPreferences("lock_prefs", MODE_PRIVATE).getBoolean("install_watch_enabled", true))
                }
                val batteryPrefs = getSharedPreferences("battery_prefs", MODE_PRIVATE)
                val fontPrefs = getSharedPreferences("font_prefs", MODE_PRIVATE)
                val lastOpenedPrefs = getSharedPreferences("last_opened_prefs", MODE_PRIVATE)
                // themePrefs/darkModeOption/isDark are declared above SciFiLauncherTheme now -
                // this scope uses those same instances via closure.

                var batteryMode by remember { mutableStateOf(loadBatterySaverMode(batteryPrefs)) }
                var fontSizeOption by remember { mutableStateOf(loadFontSize(fontPrefs)) }
                var showMoreApps by remember { mutableStateOf(false) }

                val contextAndroid = LocalContext.current

                val allApps by remember { derivedStateOf { allAppsState } }

                // Explicit type so compiler is happy
                val visibleApps by remember(batteryMode, allApps) {
                    mutableStateOf<List<AppItem>>(
                        filterAppsForBatteryMode(allApps, batteryMode, batteryPrefs)
                    )
                }

                // THEME STATE
                var themeIndex by rememberSaveable {
                    mutableStateOf(themePrefs.getInt("theme_index", 0))
                }

                val activeTheme = CedalThemes[themeIndex % CedalThemes.size]
                val themeColor = activeTheme.primary

                var showOnboarding by rememberSaveable { mutableStateOf(!isOnboardingComplete(context)) }
                var showWelcome by rememberSaveable { mutableStateOf(true) }
                var showApps by rememberSaveable { mutableStateOf(false) }
                var showRecents by rememberSaveable { mutableStateOf(false) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var showSecurity by rememberSaveable { mutableStateOf(false) }
                var showHiddenApps by rememberSaveable { mutableStateOf(false) }
                var showFavoriteApps by rememberSaveable { mutableStateOf(false) }
                var showBatteryAllowedApps by rememberSaveable { mutableStateOf(false) }
                var isPageMode by rememberSaveable { mutableStateOf(false) }

                var recentApps by remember { mutableStateOf(listOf<AppItem>()) }
                val maxRecents = 10
                var showAbout by rememberSaveable { mutableStateOf(false) }


                var hiddenApps by rememberSaveable { mutableStateOf(setOf<String>()) }
                val phonePrefs = getSharedPreferences("phone_prefs", MODE_PRIVATE)

                var favoriteAppsPkgs by rememberSaveable { mutableStateOf(setOf<String>()) }

                // Every device-owner-level action (install, uninstall, force-stop, permission
                // grant, etc.) is asked for here first - visually and out loud - and logged
                // to Requests regardless of outcome. No response within the window means it
                // gets snoozed, never silently approved (e.g. if you were asleep).
                var pendingConfirmationId by remember { mutableStateOf<Long?>(null) }
                var pendingConfirmationLabel by remember { mutableStateOf("") }
                var pendingConfirmationReason by remember { mutableStateOf("") }
                // Non-null only for actions that have a real scheduled-execution path behind
                // them (see ScheduledActionReceiver) - lets the confirmation panel offer
                // "do this later instead" without every action type needing to support it.
                var pendingConfirmationScheduleType by remember { mutableStateOf<String?>(null) }
                var pendingConfirmationScheduleTarget by remember { mutableStateOf<String?>(null) }

                // Message drafts (reply to last message / compose to any contact) - not a
                // device-owner action, so no fingerprint, no ActionLog entry, just a plain
                // review-before-send step. "direct_reply" channel means pendingMessageTarget is
                // a replyableMap key (XenosNotificationListener.sendDirectReply); "whatsapp"/
                // "sms" mean it's a phone number (sendWhatsAppAlert/sendSmsAlert).
                var showMessageDraftConfirm by remember { mutableStateOf(false) }
                var pendingMessageDraftText by remember { mutableStateOf("") }
                var pendingMessageChannel by remember { mutableStateOf("") }
                var pendingMessageTarget by remember { mutableStateOf("") }
                var pendingMessageRecipientLabel by remember { mutableStateOf("") }

                // Confirmed real bug (found via real use, not assumed): starting VoiceMemoService
                // synchronously inside the verb branch below meant the mic was already recording
                // by the time "Recording started." was spoken - the memo's own first second was
                // Elene announcing herself. Deferred instead: the verb branch only sets this flag,
                // and the actual start happens from speakWithCompletion's onDone below, once the
                // confirmation has genuinely finished playing.
                var pendingVoiceMemoStart by remember { mutableStateOf(false) }

                fun requestDeviceActionConfirmation(
                    actionLabel: String,
                    reason: String,
                    target: String?,
                    scheduleType: String? = null,
                    onDeny: (() -> Unit)? = null,
                    onApprove: () -> Unit
                ) {
                    val id = System.currentTimeMillis()
                    ActionLog.record(
                        this@MainActivity,
                        ActionRequestEntry(id, actionLabel, reason, target, id, ActionRequestStatus.PENDING, null)
                    )
                    pendingConfirmationApprove = onApprove
                    pendingConfirmationDeny = onDeny
                    pendingConfirmationId = id
                    pendingConfirmationLabel = actionLabel
                    pendingConfirmationReason = reason
                    pendingConfirmationScheduleType = scheduleType
                    pendingConfirmationScheduleTarget = target
                    speak("$actionLabel. $reason. Yes, no, or ask again later?")
                }

                // Battery saver's "allowed apps" picker is gone - instead Elene decides, in
                // your favor, which unused apps are worth putting to sleep, and asks first
                // through the same confirmation flow as every other device-owner action.
                // Checked once per day at most, one candidate at a time, never silent.
                LaunchedEffect(allAppsState) {
                    if (allAppsState.isEmpty()) return@LaunchedEffect
                    val staleCheckPrefs = getSharedPreferences("lock_prefs", MODE_PRIVATE)
                    val lastChecked = staleCheckPrefs.getLong("last_stale_app_check", 0L)
                    val dayMillis = 24 * 60 * 60_000L
                    if (System.currentTimeMillis() - lastChecked < dayMillis) return@LaunchedEffect

                    val staleThreshold = System.currentTimeMillis() - (14 * dayMillis)
                    val candidate = allAppsState.firstOrNull { app ->
                        app.packageName != packageName &&
                            app.packageName !in hiddenApps &&
                            lastOpenedPrefs.getLong(app.packageName, System.currentTimeMillis()) < staleThreshold
                    }
                    staleCheckPrefs.edit().putLong("last_stale_app_check", System.currentTimeMillis()).apply()

                    if (candidate != null) {
                        requestDeviceActionConfirmation(
                            actionLabel = "Freeze ${candidate.label}?",
                            reason = "Hasn't been opened in over 14 days - freezing it saves battery. You can unfreeze it any time.",
                            target = candidate.packageName
                        ) {
                            hiddenApps = hiddenApps + candidate.packageName
                        }
                    }
                }

                // Cedal SMS needs to keep running in the background to relay messages - ask
                // once (not on every launch) whether to request the real system exemption
                // from battery optimization for it.
                LaunchedEffect(Unit) {
                    val cedalSmsPkg = "com.xhacker.cedalsmsrelay"
                    val cedalPrefs = getSharedPreferences("lock_prefs", MODE_PRIVATE)
                    val alreadyAsked = cedalPrefs.getBoolean("cedal_sms_battery_asked", false)
                    if (!alreadyAsked &&
                        isPackageInstalled(cedalSmsPkg) &&
                        !isIgnoringBatteryOptimizations(cedalSmsPkg)
                    ) {
                        cedalPrefs.edit().putBoolean("cedal_sms_battery_asked", true).apply()
                        requestDeviceActionConfirmation(
                            actionLabel = "Let Cedal SMS run in the background?",
                            reason = "It needs to keep running unrestricted to relay messages reliably, even when you're not actively using it. This opens Android's own battery exemption confirmation.",
                            target = cedalSmsPkg
                        ) {
                            requestIgnoreBatteryOptimizations(cedalSmsPkg)
                        }
                    }
                }

                // Elene bubble: hidden until called, voice-first
                var bubbleX by rememberSaveable { mutableStateOf(40f) }
                var bubbleY by rememberSaveable { mutableStateOf(200f) }
                var bubbleState by remember { mutableStateOf(EleneBubbleState.DORMANT) }
                var isEleneChatVisible by rememberSaveable { mutableStateOf(false) }
                var eleneText by remember { mutableStateOf(TextFieldValue("")) }
                var eleneReply by remember { mutableStateOf<String?>(null) }
                var eleneLoading by remember { mutableStateOf(false) }
                var notificationFeed by remember { mutableStateOf(listOf<LastMessageInfo>()) }
                var showNotificationPanel by remember { mutableStateOf(false) }
                var showQuickSettingsPanel by remember { mutableStateOf(false) }
                var flashlightOnState by remember { mutableStateOf(false) }
                var airplaneModeOnState by remember { mutableStateOf(isAirplaneModeOn()) }
                var bluetoothOnState by remember { mutableStateOf(isBluetoothOn()) }
                var volumeModeState by remember { mutableStateOf(currentVolumeMode()) }
                var locationOnState by remember { mutableStateOf(isLocationOn()) }
                var dndOnState by remember { mutableStateOf(isDndOn()) }
                var eyeComfortOnState by remember { mutableStateOf(false) }
                var screenRecordingState by remember { mutableStateOf(false) }
                var brightnessPercentState by remember { mutableStateOf(currentBrightnessPercent()) }
                // When a command handler discovers the LLM's reply was wrong (e.g. it said
                // "Opening now" but the app wasn't actually found), this replaces what gets
                // spoken instead of speaking both messages back to back.
                var commandReplyOverride by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()

                // The one choke point every mic-start goes through (manual tap or continuous-
                // listening auto-restart) - never starts listening during a call. This was a
                // real, urgent bug: continuous listening's retry-on-error loop didn't know
                // about calls, so it kept grabbing the mic throughout one, over and over.
                fun beginListening() {
                    if (shouldPauseListening(contextAndroid)) {
                        bubbleState = EleneBubbleState.DORMANT
                        return
                    }
                    bubbleState = EleneBubbleState.LISTENING
                    startVoiceInput()
                }

                // Dispatches a command returned by the Elene backend (e.g. "open_app:com.x",
                // "scroll_down"). Screen-control verbs fall through to ScifiAccessibilityService;
                // if it isn't enabled, Elene explains why instead of silently doing nothing.
                val handleEleneCommand: (String?) -> Unit = handler@{ command ->
                    if (command.isNullOrBlank()) return@handler
                    val parts = command.split(":", limit = 2)
                    val verb = parts[0]
                    val arg = parts.getOrNull(1)

                    fun goToScreen(apps: Boolean = false, recents: Boolean = false, settings: Boolean = false, security: Boolean = false) {
                        showWelcome = false
                        showApps = apps
                        showRecents = recents
                        showSettings = settings
                        showSecurity = security
                        showHiddenApps = false
                        showFavoriteApps = false
                        showBatteryAllowedApps = false
                    }

                    fun screenControl(action: (ScifiAccessibilityService) -> Unit) {
                        val service = ScifiAccessibilityService.instance
                        if (service != null) {
                            action(service)
                        } else {
                            speak("Negative. I need the Accessibility permission to control your screen. Opening Settings now.")
                            Toast.makeText(
                                this@MainActivity,
                                "Turn on \"SciFi Elene\" under Accessibility to let Elene control your screen.",
                                Toast.LENGTH_LONG
                            ).show()
                            runCatching {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        }
                    }

                    // Play Store search itself is the honest ceiling for any app, Device Owner
                    // included, WITHOUT Managed Google Play enterprise enrollment - no app can
                    // make it silently install something. What this CAN do beyond just opening
                    // the search page: read the results via the Accessibility service (already
                    // used for click/highlight/scroll), speak back exactly which app it found,
                    // and - only after that's confirmed - tap Install on the visible Play Store
                    // screen itself (a real tap on the real screen, not a hidden background
                    // call, so it's watchable live and it's logged in Requests either way).
                    fun downloadAppFlow(query: String) {
                        val encoded = Uri.encode(query)
                        val opened = runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$encoded&c=apps")))
                            true
                        }.getOrDefault(false)
                        if (!opened) {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$encoded&c=apps")))
                            }
                            commandReplyOverride = "Opening the Play Store for \"$query\" - Accessibility isn't on, so you'll need to tap Install yourself."
                            return
                        }
                        val service = ScifiAccessibilityService.instance
                        if (service == null) {
                            commandReplyOverride = "Opening the Play Store for \"$query\". Turn on Elene's Accessibility permission under Settings if you want me to find and install it for you."
                            return
                        }
                        commandReplyOverride = "Searching the Play Store for \"$query\"."
                        lifecycleScope.launch {
                            var found: Pair<String, String>? = null
                            var attempts = 0
                            while (found == null && attempts < 8) {
                                kotlinx.coroutines.delay(600L)
                                found = service.findTopPlayStoreResult()
                                attempts++
                            }
                            val result = found
                            if (result == null) {
                                speak("I searched for \"$query\" but couldn't read the results - go ahead and tap Install yourself.")
                                return@launch
                            }
                            val (appName, developer) = result
                            requestDeviceActionConfirmation(
                                actionLabel = "Install $appName?",
                                reason = "By $developer - found from your search for \"$query\".",
                                target = appName
                            ) {
                                if (service.clickByText("Install")) {
                                    speak("Installing $appName now.")
                                } else {
                                    speak("I found $appName but couldn't tap Install - the screen may have changed. Go ahead and tap it yourself.")
                                }
                            }
                        }
                    }

                    when (verb) {
                        "open_app" -> if (arg != null) {
                            val opened = openAppSmart(arg, allAppsState, favoriteAppsPkgs, contextAndroid)
                            if (!opened) {
                                commandReplyOverride = "Negative. I couldn't find an app matching \"$arg\" installed on this phone."
                            }
                        }
                        // Distinct from "open_app": this navigates to the app grid and types
                        // into its real search box (visible, filtering live) rather than
                        // launching anything - for "search for X" / "do I have X" style asks.
                        "search_app" -> if (arg != null) {
                            goToScreen(apps = true)
                            appsSearchQuery = arg
                            val exists = allAppsState.any { it.label.lowercase().contains(arg.lowercase()) }
                            commandReplyOverride = if (exists) {
                                "Found it - showing results for \"$arg\"."
                            } else {
                                "I don't see anything matching \"$arg\" installed on this phone."
                            }
                        }
                        // Draft-then-confirm messaging: never sends anything by itself - both
                        // branches only ever stage a MessageDraftConfirmationPanel; the actual
                        // send happens on that panel's SEND button, nowhere else.
                        "reply_last_message" -> if (arg != null) {
                            val lastMsg = XenosNotificationListener.lastMessageInfo
                            if (lastMsg == null) {
                                commandReplyOverride = "There's no recent message to reply to."
                            } else {
                                val replyable = XenosNotificationListener.replyableMap.values.firstOrNull {
                                    it.title == lastMsg.title && it.appName == lastMsg.appName
                                }
                                if (replyable != null) {
                                    pendingMessageChannel = "direct_reply"
                                    pendingMessageTarget = replyable.key
                                    pendingMessageRecipientLabel = lastMsg.title
                                    pendingMessageDraftText = arg
                                    showMessageDraftConfirm = true
                                } else {
                                    val matches = findContactsByName(this@MainActivity, lastMsg.title)
                                    if (matches.size == 1) {
                                        pendingMessageChannel = "whatsapp"
                                        pendingMessageTarget = matches[0].phoneNumber
                                        pendingMessageRecipientLabel = matches[0].displayName
                                        pendingMessageDraftText = arg
                                        showMessageDraftConfirm = true
                                    } else {
                                        commandReplyOverride = "That message isn't available to reply to directly anymore."
                                    }
                                }
                            }
                        }
                        "send_message" -> if (arg != null) {
                            val parts = arg.split(":", limit = 3)
                            val contactQuery = parts.getOrNull(0)
                            val channel = parts.getOrNull(1)?.lowercase()
                            val text = parts.getOrNull(2)
                            when {
                                contactQuery == null || channel == null || text == null ->
                                    commandReplyOverride = "I need a contact, a channel, and a message to send that."
                                else -> {
                                    val matches = findContactsByName(this@MainActivity, contactQuery)
                                    when {
                                        matches.isEmpty() ->
                                            commandReplyOverride = "I couldn't find a contact matching \"$contactQuery\"."
                                        matches.size > 1 ->
                                            commandReplyOverride = "I found more than one \"$contactQuery\": ${matches.joinToString(", ") { it.displayName }}. Which one did you mean?"
                                        else -> {
                                            pendingMessageChannel = if (channel.contains("sms")) "sms" else "whatsapp"
                                            pendingMessageTarget = matches[0].phoneNumber
                                            pendingMessageRecipientLabel = matches[0].displayName
                                            pendingMessageDraftText = text
                                            showMessageDraftConfirm = true
                                        }
                                    }
                                }
                            }
                        }
                        "answer_call" -> {
                            val answered = attemptAnswerCall(this@MainActivity)
                            commandReplyOverride = if (answered) "Answering." else "I couldn't answer that one - you'll need to tap it yourself."
                        }
                        "end_call" -> {
                            val ended = attemptEndCall(this@MainActivity)
                            commandReplyOverride = if (ended) "Ending the call." else "I couldn't end that one - you'll need to tap it yourself."
                        }
                        "start_recording" -> {
                            commandReplyOverride = if (VoiceMemoService.isRecording) {
                                "Already recording."
                            } else {
                                pendingVoiceMemoStart = true
                                "Recording started."
                            }
                        }
                        "stop_recording" -> {
                            commandReplyOverride = if (!VoiceMemoService.isRecording) {
                                "Not currently recording."
                            } else {
                                VoiceMemoService.stop(this@MainActivity)
                                "Recording saved."
                            }
                        }
                        "start_screen_recording" -> {
                            if (screenRecordingState) {
                                commandReplyOverride = "Already recording."
                            } else {
                                commandReplyOverride = "Starting screen recording - confirm the system permission prompt."
                                beginScreenRecording(RecordAudioMode.NONE, null) { recording ->
                                    screenRecordingState = recording
                                }
                            }
                        }
                        "stop_screen_recording" -> {
                            if (!screenRecordingState) {
                                commandReplyOverride = "Not currently recording."
                            } else {
                                toggleScreenRecord(true) { recording -> screenRecordingState = recording }
                                commandReplyOverride = "Stopped and saving the recording."
                            }
                        }
                        // Stage 1 of self-updating Elene - this only ever queues a PROPOSED
                        // entry for fingerprint approval, never applies anything (there is no
                        // build/deploy pipeline behind this yet). "user" origin means the user
                        // directly asked, so it goes through the live confirmation flow right
                        // away, same as any other device action; "elene" origin means she
                        // proposed it herself while just chatting, so it only appears in the
                        // Updates screen for later review - never interrupts unprompted.
                        "propose_update" -> arg?.let { raw ->
                            val parts = raw.split(":", limit = 3)
                            val origin = if (parts.getOrNull(0)?.lowercase() == "elene") ProposalOrigin.ELENE else ProposalOrigin.USER
                            val category = when (parts.getOrNull(1)?.lowercase()) {
                                "feature" -> UpdateCategory.FEATURE_ADDED
                                "fix" -> UpdateCategory.BUG_FIX
                                "remove" -> UpdateCategory.FEATURE_REMOVED
                                else -> UpdateCategory.OTHER
                            }
                            val description = parts.getOrNull(2)?.trim()
                            if (description.isNullOrBlank()) return@let
                            val title = description.take(60)
                            val id = System.currentTimeMillis()
                            UpdateProposalLog.record(
                                this@MainActivity,
                                UpdateProposalEntry(id, title, description, category, origin, id, UpdateProposalStatus.PROPOSED, null)
                            )
                            if (origin == ProposalOrigin.USER) {
                                val actionWord = when (category) {
                                    UpdateCategory.FEATURE_ADDED -> "Add feature"
                                    UpdateCategory.BUG_FIX -> "Fix"
                                    UpdateCategory.FEATURE_REMOVED -> "Remove"
                                    UpdateCategory.OTHER -> "Update"
                                }
                                requestDeviceActionConfirmation(
                                    actionLabel = "$actionWord: $title",
                                    reason = description,
                                    target = id.toString(),
                                    onApprove = { UpdateProposalLog.updateStatus(this@MainActivity, id, UpdateProposalStatus.APPROVED) },
                                    onDeny = { UpdateProposalLog.updateStatus(this@MainActivity, id, UpdateProposalStatus.DENIED) }
                                )
                            } else {
                                commandReplyOverride = "Noted - I've added a suggestion to the Updates screen for you to review."
                            }
                        }
                        // Bridged from ScifiAccessibilityService, which has already stashed the
                        // target package/hint in its own fields before triggering this - purely
                        // mechanical here, no speaking: the accessibility service drives the
                        // whole describe/play flow once capture starts.
                        "describe_screen_capture" -> beginScreenCapture("single")
                        "play_game_capture" -> beginScreenCapture("loop")
                        // Reachable both from the cross-app bubble (handled entirely in
                        // ScifiAccessibilityService.handleOverlayCommand) AND from typing into
                        // this screen's own chat panel, which routes here instead - delegate to
                        // the same orchestration either way rather than duplicating it.
                        "describe_screen" -> screenControl { it.startDescribeScreen(arg) }
                        "play_game" -> screenControl { it.startGameLoop(arg) }
                        "stop_game" -> screenControl {
                            if (it.isGameLoopActive()) it.stopGameLoop("Stopped playing.") else speak("Not currently playing.")
                        }
                        "schedule" -> if (arg != null) {
                            // "schedule:<minutes>:<innerCommand>" - the backend wraps any
                            // actionable request this way when the user attaches a delay
                            // ("in 2 hours", "after 30 minutes"). Only download_app actually
                            // has a scheduled-execution path right now (see
                            // ScheduledActionReceiver) - everything else replies honestly
                            // that it can't be deferred yet, rather than silently dropping it.
                            val scheduleParts = arg.split(":", limit = 2)
                            val minutes = scheduleParts.getOrNull(0)?.toIntOrNull()
                            val innerCommand = scheduleParts.getOrNull(1)
                            val innerParts = innerCommand?.split(":", limit = 2)
                            val innerVerb = innerParts?.getOrNull(0)
                            val innerArg = innerParts?.getOrNull(1)
                            when {
                                minutes == null || minutes <= 0 || innerCommand == null ->
                                    commandReplyOverride = "Negative. I couldn't tell when to schedule that."
                                innerVerb == "download_app" && innerArg != null -> {
                                    scheduleAction("download_app", innerArg, "Download $innerArg", minutes * 60_000L)
                                    commandReplyOverride = "Scheduled: download \"$innerArg\" in ${formatMinutesForSpeech(minutes)}."
                                }
                                else ->
                                    commandReplyOverride = "Negative. I can only schedule downloads for later right now."
                            }
                        }
                        "download_app" -> if (arg != null) {
                            downloadAppFlow(arg)
                        }
                        "freeze_app" -> if (arg != null) {
                            val match = allAppsState.firstOrNull { it.label.lowercase().contains(arg.lowercase()) }
                            if (match != null) {
                                hiddenApps = hiddenApps + match.packageName
                                commandReplyOverride = "Froze ${match.label}."
                            } else {
                                commandReplyOverride = "Negative. I couldn't find an app matching \"$arg\" to freeze."
                            }
                        }
                        "unfreeze_app" -> if (arg != null) {
                            val match = allAppsState.firstOrNull { it.label.lowercase().contains(arg.lowercase()) }
                            if (match != null) {
                                hiddenApps = hiddenApps - match.packageName
                                commandReplyOverride = "Unfroze ${match.label}."
                            } else {
                                commandReplyOverride = "Negative. I couldn't find an app matching \"$arg\" to unfreeze."
                            }
                        }
                        "open_page" -> when (arg) {
                            "home", "dashboard" -> goToScreen()
                            "apps", "games" -> goToScreen(apps = true)
                            "recents" -> goToScreen(recents = true)
                            "settings" -> goToScreen(settings = true)
                            "security" -> goToScreen(security = true)
                            else -> speak("Negative. No page called $arg.")
                        }
                        "open_android_settings" -> runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                        "toggle_dark_mode" -> {
                            darkModeOption = if (darkModeOption == DarkModeOption.DARK) DarkModeOption.LIGHT else DarkModeOption.DARK
                            saveDarkMode(themePrefs, darkModeOption)
                        }
                        "toggle_battery_saver" -> {
                            batteryMode = if (batteryMode == BatterySaverMode.OFF) BatterySaverMode.BALANCED else BatterySaverMode.OFF
                            saveBatterySaverMode(batteryPrefs, batteryMode)
                        }
                        "stop_listening" -> bubbleState = EleneBubbleState.DORMANT
                        "scroll_up" -> screenControl { it.scrollUp() }
                        "scroll_down" -> screenControl { it.scrollDown() }
                        "go_back" -> screenControl { it.goBack() }
                        "go_home" -> screenControl { it.goHome() }
                        "open_recents" -> screenControl { it.openRecents() }
                        "click" -> if (arg != null) {
                            screenControl { svc ->
                                if (!svc.clickByText(arg)) speak("Negative. I could not find \"$arg\" on screen.")
                            }
                        }
                        "highlight" -> if (arg != null) {
                            screenControl { svc ->
                                if (!svc.highlightByText(arg)) speak("Negative. I could not find \"$arg\" on screen.")
                            }
                        }
                        "highlight_off" -> ScifiAccessibilityService.instance?.clearHighlight()
                        "type_text" -> if (arg != null) {
                            screenControl { svc ->
                                if (!svc.typeText(arg)) speak("Negative. I couldn't find a text field to type into.")
                            }
                        }
                        "force_stop_app" -> if (arg != null) {
                            val pkg = AppResolver.resolvePackageName(contextAndroid, arg, favoriteAppsPkgs)
                            when {
                                pkg == null -> speak("Negative. I couldn't find an app matching \"$arg\".")
                                ShizukuManager.hasPermission() -> {
                                    scope.launch {
                                        val result = ShizukuManager.forceStopPackage(contextAndroid, pkg)
                                        if (result.isSuccess) speak("Force-stopped $arg.")
                                        else speak("Negative. The force-stop command failed.")
                                    }
                                }
                                else -> {
                                    val am = contextAndroid.getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
                                    runCatching { am?.killBackgroundProcesses(pkg) }
                                    speak(
                                        "Stopped $arg's background processes. A full force-stop " +
                                            "(the same as if it were currently on screen) needs " +
                                            "Shizuku set up in Settings - it isn't right now."
                                    )
                                }
                            }
                        }
                        "volume" -> if (arg != null) {
                            val am = contextAndroid.getSystemService(AUDIO_SERVICE) as? android.media.AudioManager
                            if (am == null) {
                                speak("Negative. Audio control isn't available.")
                            } else {
                                val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                                val current = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                                val step = (max * 0.15f).toInt().coerceAtLeast(1)
                                val target = when {
                                    arg == "up" -> (current + step).coerceIn(0, max)
                                    arg == "down" -> (current - step).coerceIn(0, max)
                                    arg.toIntOrNull() != null -> (arg.toInt().coerceIn(0, 100) * max) / 100
                                    else -> current
                                }
                                runCatching {
                                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, android.media.AudioManager.FLAG_SHOW_UI)
                                }
                            }
                        }
                        "brightness" -> if (arg != null) {
                            val current = currentBrightnessPercent()
                            val target = when {
                                arg == "up" -> (current + 15).coerceIn(1, 100)
                                arg == "down" -> (current - 15).coerceIn(1, 100)
                                arg.toIntOrNull() != null -> arg.toInt().coerceIn(1, 100)
                                else -> current
                            }
                            setBrightnessPercent(target)
                        }
                        "hide_page" -> screenControl { it.toggleHidePage() }
                        "flashlight" -> setFlashlight(contextAndroid, on = arg == "on")
                        "bluetooth" -> when (arg) {
                            "on" -> {
                                val hasPermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                                    ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    // Bluetooth's own launcher callback speaks the real outcome
                                    // once the user actually answers the system dialog, rather
                                    // than claiming success before they've responded to it.
                                    runCatching {
                                        bluetoothEnableLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                    }
                                } else {
                                    speak("Negative. I need the Bluetooth permission first. Opening Bluetooth settings.")
                                    runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                                }
                            }
                            else -> {
                                speak("Advisory. Android blocks silently turning Bluetooth off since Android 13. Opening Bluetooth settings.")
                                runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                            }
                        }
                        "world_clock" -> if (arg != null) {
                            runCatching {
                                val zone = java.time.ZoneId.of(arg)
                                val now = java.time.ZonedDateTime.now(zone)
                                val formatted = now.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
                                speak("It is $formatted in ${arg.substringAfterLast('/').replace('_', ' ')}.")
                            }.onFailure {
                                speak("Negative. I don't recognize that timezone.")
                            }
                        }
                        "power_off" -> speak(
                            "Negative. Powering off the device is not something any app can do on a " +
                                "normal, non-rooted phone - that's an OS-level restriction, not a missing feature."
                        )
                        "scan_wifi" -> {
                            speak("Affirmative. Scanning the network.")
                            scope.launch {
                                val devices = scanLocalNetwork(contextAndroid)
                                val summary = if (devices.isEmpty()) {
                                    "Negative. I found no other devices on this network."
                                } else {
                                    val parts = devices.map { d ->
                                        if (d.guessedType != null) "${d.ip}, likely a ${d.guessedType}" else d.ip
                                    }
                                    "Found ${devices.size} device${if (devices.size == 1) "" else "s"}: ${parts.joinToString("; ")}."
                                }
                                speak(summary)
                            }
                        }
                        "next_page", "previous_page" ->
                            speak("Negative. Paging through the app grid by voice is not wired up yet.")
                        "remember_avoid" -> arg?.let {
                            addAvoidTopic(getSharedPreferences("elene_memory_prefs", MODE_PRIVATE), it)
                        }
                        "forget_avoid" -> arg?.let {
                            removeAvoidTopic(getSharedPreferences("elene_memory_prefs", MODE_PRIVATE), it)
                        }
                        else -> {}
                    }
                }

                // Sends recognized/typed text to the Elene backend, shows the reply's command
                // through handleEleneCommand, and speaks the answer. Drives the bubble's color state.
                val sendToElene: (String) -> Unit = sender@{ rawText ->
                    val text = rawText.trim()
                    if (text.isBlank() || eleneLoading) return@sender

                    // Deterministic local shortcuts for simple, unambiguous UI toggles -
                    // more reliable than depending on the LLM to pick the right command
                    // every time, and skips a network round-trip.
                    val normalized = text.lowercase().trimEnd('.', '!', '?')
                    if (normalized in setOf("hide chat", "close chat", "dismiss chat", "dismiss that")) {
                        isEleneChatVisible = false
                        return@sender
                    }
                    if (normalized in setOf("show chat", "open chat")) {
                        isEleneChatVisible = true
                        return@sender
                    }
                    // "stop listening" is the absolute, always-local fallback to end a
                    // continuous listening session - every other exit from a conversation turn
                    // re-opens the mic instead of going dormant. More natural variants than the
                    // few checked here are also handled via the backend's "stop_listening"
                    // command, but that's best-effort - this one always works, even offline.
                    if (isStopListeningPhrase(text)) {
                        bubbleState = EleneBubbleState.DORMANT
                        return@sender
                    }

                    // Follow-up to a just-explained permission denial - handled deterministically
                    // rather than round-tripping to the LLM, since it's a fixed two-step exchange.
                    if (awaitingReaskConfirmation) {
                        awaitingReaskConfirmation = false
                        val retry = pendingDenialRetry
                        pendingDenialRetry = null
                        val affirmative = listOf("yes", "yeah", "yep", "sure", "do it", "please", "bring it up", "bring it up again")
                        if (retry != null && affirmative.any { normalized.contains(it) }) {
                            speak("Bringing it up again.")
                            retry()
                        } else {
                            speak("Understood. Leaving it as is.")
                        }
                        return@sender
                    }
                    if (awaitingDenialFollowup) {
                        awaitingDenialFollowup = false
                        val mistakeWords = listOf("mistake", "accident", "accidentally", "misclick", "wrong")
                        if (mistakeWords.any { normalized.contains(it) }) {
                            awaitingReaskConfirmation = true
                            speak("Do you want me to bring it up again?")
                        } else {
                            pendingDenialRetry = null
                            speak("Understood. We'll go with your plan.")
                        }
                        return@sender
                    }

                    eleneLoading = true
                    eleneReply = null
                    bubbleState = EleneBubbleState.REPLYING

                    scope.launch {
                        val screenText = ScifiAccessibilityService.instance?.describeScreen()
                        val avoidTopics = loadAvoidTopics(getSharedPreferences("elene_memory_prefs", MODE_PRIVATE))
                        val lastMsg = XenosNotificationListener.lastMessageInfo
                        val meeting = currentMeeting(this@MainActivity)
                        val ctxMap = buildMap {
                            put("battery_mode", batteryMode.name)
                            put("is_dark", isDark)
                            if (!screenText.isNullOrBlank()) put("screen_text", screenText)
                            if (avoidTopics.isNotEmpty()) put("avoid_topics", avoidTopics.joinToString(", "))
                            if (lastMsg != null) {
                                put("last_message_sender", lastMsg.title)
                                put("last_message_app", lastMsg.appName)
                                put("last_message_text", lastMsg.text)
                            }
                            if (meeting != null) {
                                put("in_meeting", "true")
                                put("current_meeting_title", meeting.title)
                            }
                        }
                        val response = EleneApiClient.sendText(
                            userId = "launcher-user",
                            text = text,
                            context = ctxMap
                        )
                        eleneLoading = false

                        if (response == null) {
                            bubbleState = EleneBubbleState.UNRESPONSIVE
                            speak("Negative. Could not reach Elene.")
                            kotlinx.coroutines.delay(1400L)
                            // Stays listening until "stop listening" is heard - a failed
                            // request isn't an exit condition - unless continuous listening
                            // is turned off in Settings, in which case every turn ends here.
                            if (continuousListeningEnabled()) beginListening() else bubbleState = EleneBubbleState.DORMANT
                        } else {
                            eleneReply = response.reply
                            commandReplyOverride = null
                            // Multi-step requests ("open app view and then launch whatsapp")
                            // arrive as an ordered list - a short gap between each lets
                            // Compose recomposition/navigation settle before the next one runs.
                            response.commands.forEachIndexed { index, cmd ->
                                handleEleneCommand(cmd)
                                if (index != response.commands.lastIndex) kotlinx.coroutines.delay(350L)
                            }
                            val replyText = commandReplyOverride
                                ?: response.reply?.ifBlank { null }
                                ?: "Negative. I had a problem thinking just now."
                            if (commandReplyOverride != null) eleneReply = commandReplyOverride
                            speakWithCompletion(replyText) {
                                // Only now, after "Recording started." has actually finished
                                // playing, does the mic itself start - see the comment on
                                // pendingVoiceMemoStart above for why this can't run synchronously
                                // inside the start_recording branch.
                                if (pendingVoiceMemoStart) {
                                    pendingVoiceMemoStart = false
                                    VoiceMemoService.start(this@MainActivity)
                                }
                                // Loop back into listening once the reply finishes speaking,
                                // rather than closing - "stop listening" is the only exit,
                                // unless continuous listening is off in Settings.
                                if (continuousListeningEnabled()) beginListening() else bubbleState = EleneBubbleState.DORMANT
                            }
                        }
                    }
                }

                // Carries out whatever the cross-app overlay bubble asked for once this Activity
                // is actually back in front (see onNewIntent) - reuses the exact same command
                // handling as the home-screen assistant, not a separate implementation.
                LaunchedEffect(eleneBridgeCommand) {
                    eleneBridgeCommand?.let { cmd ->
                        handleEleneCommand(cmd)
                        eleneBridgeCommand = null
                    }
                }

                // Bridges the imperative onActivityResult speech callback into Compose state.
                LaunchedEffect(Unit) {
                    while (true) {
                        val spoken = eleneLastSpokenText
                        if (spoken != null) {
                            eleneLastSpokenText = null
                            eleneText = TextFieldValue("")
                            sendToElene(spoken)
                        } else if (eleneSpeechFailed) {
                            eleneSpeechFailed = false
                            bubbleState = EleneBubbleState.UNRESPONSIVE
                            speak("Negative. I didn't catch that.")
                            kotlinx.coroutines.delay(1200L)
                            if (continuousListeningEnabled()) {
                                // Not catching what you said isn't "stop listening" - stay open.
                                beginListening()
                            } else {
                                bubbleState = EleneBubbleState.DORMANT
                            }
                        }
                        kotlinx.coroutines.delay(100L)
                    }
                }

                // Bridges XenosNotificationListener's plain list into Compose state for the
                // custom notification bar panel (stands in for the system shade in Kiosk mode).
                LaunchedEffect(Unit) {
                    while (true) {
                        val snapshot = XenosNotificationListener.missedNotifications.toList()
                        if (snapshot != notificationFeed) {
                            notificationFeed = snapshot
                        }
                        kotlinx.coroutines.delay(1000L)
                    }
                }

                val favoriteApps = visibleApps
                    .filter { it.packageName in favoriteAppsPkgs }
                    .take(6)

                if (isDeviceWiped(lockPrefs)) {
                    WipedScreen()
                    return@SciFiLauncherTheme
                }

                if (showOnboarding) {
                    OnboardingScreen(
                        themeColor = themeColor,
                        onFinished = { showOnboarding = false }
                    )
                    return@SciFiLauncherTheme
                }

                // Font size actually controlling every piece of text in the app, not just the
                // handful of screens that were manually wired to fontSizeOption.scale before -
                // Compose already scales every .sp value by the current Density's fontScale,
                // so overriding it here at the root makes it apply everywhere for free instead
                // of threading a scale multiplier through 20+ screen files by hand.
                val baseDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = baseDensity.density,
                        fontScale = fontSizeOption.scale
                    )
                ) {
                Box(modifier = Modifier.fillMaxSize()) {

                    // MAIN SCREENS
                    when {
                        showWelcome -> {
                            WelcomeScreen(
                                modifier = Modifier.fillMaxSize(),
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                onContinue = { showWelcome = false },
                                onColorChange = { _ -> /* no longer using isRed */ }
                            )
                        }

                        showApps -> {
                            AppsScreen(
                                themeColor = themeColor,
                                apps = visibleApps,
                                isPageMode = isPageMode,
                                hiddenApps = hiddenApps,
                                fontSizeOption = fontSizeOption,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                onToggleLayout = { isPageMode = !isPageMode },
                                onBackToDashboard = {
                                    showApps = false
                                    showWelcome = false
                                    showRecents = false
                                    showSettings = false
                                    showSecurity = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                onOpenRecents = {
                                    showApps = false
                                    showRecents = true
                                    showWelcome = false
                                    showSettings = false
                                    showSecurity = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                onAppClick = { pkg ->
                                    fun doLaunch() {
                                        launchApp(
                                            pkg,
                                            visibleApps,
                                            pm,
                                            maxRecents,
                                            lastOpenedPrefs
                                        ) { updated ->
                                            recentApps = updated
                                        }
                                    }

                                    doLaunch()
                                },
                                onUninstall = { pkg ->
                                    val label = allAppsState.firstOrNull { it.packageName == pkg }?.label ?: pkg
                                    requestDeviceActionConfirmation(
                                        actionLabel = "Uninstall $label?",
                                        reason = "This removes the app and its data from this device.",
                                        target = pkg,
                                        scheduleType = "uninstall"
                                    ) {
                                        performSilentUninstall(pkg)
                                    }
                                },
                                onAppInfo = { pkg ->
                                    val intent =
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:$pkg")
                                        }
                                    startActivity(intent)
                                },
                                onShare = { pkg -> shareApp(pkg) },
                                onRename = { pkg, newName ->
                                    val labelPrefs = getSharedPreferences("app_label_prefs", MODE_PRIVATE)
                                    if (newName.isBlank()) {
                                        clearAppLabelOverride(labelPrefs, pkg)
                                    } else {
                                        saveAppLabelOverride(labelPrefs, pkg, newName)
                                    }
                                    allAppsState = loadAllApps(packageManager)
                                },
                                getLastOpenedText = { pkg ->
                                    getLastOpenedText(lastOpenedPrefs, pkg)
                                },
                                searchQuery = appsSearchQuery,
                                onSearchQueryChange = { appsSearchQuery = it }
                            )
                        }

                        showRecents -> {
                            val filteredRecents = recentApps.filter { app ->
                                visibleApps.any { it.packageName == app.packageName }
                            }
                            RecentsScreen(
                                modifier = Modifier.fillMaxSize(),
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                recentApps = filteredRecents,
                                onBackToDashboard = {
                                    showRecents = false
                                    showWelcome = false
                                    showSettings = false
                                    showSecurity = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                onAppClick = { pkg ->
                                    if (visibleApps.any { it.packageName == pkg }) {
                                        val launchIntent = pm.getLaunchIntentForPackage(pkg)
                                        if (launchIntent != null) {
                                            startActivity(launchIntent)
                                            recordLastOpened(lastOpenedPrefs, pkg)
                                        }
                                    }
                                }
                            )
                        }


                        showSettings -> {
                            SettingsScreen(
                                modifier = Modifier.fillMaxSize(),
                                themeColor = themeColor,
                                batteryMode = batteryMode,
                                currentThemeIndex = themeIndex,
                                onThemeChange = { idx -> themeIndex = idx },
                                onBackToDashboard = {
                                    batteryMode = loadBatterySaverMode(batteryPrefs)
                                    fontSizeOption = loadFontSize(fontPrefs)
                                    darkModeOption = loadDarkMode(themePrefs)
                                    showSettings = false
                                    showWelcome = false
                                    showApps = false
                                    showRecents = false
                                    showSecurity = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                onFeedback = { openPlayStoreForFeedback() },
                                onPrivacyPolicy = { openPrivacyPolicyPage() },
                                onMakeDefaultLauncher = { openHomeSelectorSettings() },
                                onOpenAbout = {
                                    showSettings = false
                                    showAbout = true
                                },
                                onOpenMoreApps = {
                                    showSettings = false
                                    showMoreApps = true
                                },
                                onOpenCapabilities = {
                                    showSettings = false
                                    showCapabilities = true
                                },
                                onDarkModeChange = { mode ->
                                    // SettingsScreen keeps its own local copy for its own
                                    // recomposition, but the M3 theme (AlertDialog colors,
                                    // etc.) is driven from THIS outer darkModeOption - without
                                    // this, toggling dark/light only took effect once you
                                    // backed all the way out to the dashboard and back in,
                                    // since that was the only place the outer copy re-read
                                    // SharedPreferences.
                                    darkModeOption = mode
                                }
                            )
                        }

                        showCapabilities -> {
                            CapabilitiesScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                onBack = {
                                    showCapabilities = false
                                    showSettings = true
                                }
                            )
                        }

                        showMoreApps -> {
                            MoreAppsScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                fontSize = fontSizeOptionToSp(fontSizeOption),
                                onBack = {
                                    showMoreApps = false
                                    showSettings = true
                                }
                            )
                        }

                        showHiddenApps -> {
                            HiddenAppsScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                apps = visibleApps,
                                hiddenApps = hiddenApps,
                                onHiddenAppsChange = { hiddenApps = it },
                                onBack = {
                                    showHiddenApps = false
                                    showSettings = false
                                    showSecurity = false
                                }
                            )
                        }

                        showFavoriteApps -> {
                            FavoriteAppsScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,   // add this line
                                apps = visibleApps,
                                favoriteApps = favoriteAppsPkgs,
                                onFavoriteAppsChange = { favoriteAppsPkgs = it.take(6).toSet() },
                                onBack = {
                                    showFavoriteApps = false
                                    showSettings = false
                                }
                            )
                        }

                        showBatteryAllowedApps -> {
                            BatteryAllowedAppsRoot(
                                themeColor = themeColor,
                                isDark = isDark,
                                allApps = allApps,
                                batteryMode = batteryMode,
                                onBack = {
                                    showBatteryAllowedApps = false
                                    showSettings = true
                                }
                            )
                        }

                        showAbout -> {
                            AboutScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                fontSize = fontSizeOptionToSp(fontSizeOption), // <-- convert here
                                onBack = {
                                    showAbout = false
                                    showSettings = true
                                }
                            )
                        }


                        showSecurity -> {
                            SecurityScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                lockPrefs = lockPrefs,
                                onBackToDashboard = { showSecurity = false },
                                onOpenStorage = {
                                    showSecurity = false
                                    showStorage = true
                                },
                                onOpenFileManager = {
                                    showSecurity = false
                                    showFileBrowser = true
                                },
                                onOpenRequests = {
                                    showSecurity = false
                                    showRequests = true
                                },
                                onOpenUpdates = {
                                    showSecurity = false
                                    showUpdates = true
                                },
                                onOpenAppLog = {
                                    showSecurity = false
                                    showAppLog = true
                                },
                                onOpenCommands = {
                                    showSecurity = false
                                    showCommands = true
                                },
                                onRequestBiometricForVoiceId = { onSuccess ->
                                    showBiometricPrompt(
                                        title = "Voice ID",
                                        subtitle = "Verify it's really you before changing this",
                                        onSuccess = onSuccess,
                                        onFailure = {}
                                    )
                                },
                                onOpenInstallFlags = {
                                    showSecurity = false
                                    showInstallFlags = true
                                },
                                installWatchEnabled = installWatchEnabledState,
                                onToggleInstallWatch = { enabled ->
                                    installWatchEnabledState = enabled
                                    getSharedPreferences("lock_prefs", MODE_PRIVATE).edit()
                                        .putBoolean("install_watch_enabled", enabled).apply()
                                },
                                currentDeviceLocation = readCachedDeviceLocation(),
                                locationHistoryEnabled = locationHistoryEnabledState,
                                locationHistoryCount = LocationHistory.loadAll(this@MainActivity).size,
                                onToggleLocationHistory = { enabled ->
                                    locationHistoryEnabledState = enabled
                                    getSharedPreferences("lock_prefs", MODE_PRIVATE).edit()
                                        .putBoolean("location_history_enabled", enabled).apply()
                                    if (enabled) {
                                        LocationHistoryWorker.start(this@MainActivity)
                                    } else {
                                        LocationHistoryWorker.stop(this@MainActivity)
                                    }
                                },
                                onOpenLocationHistory = {
                                    showSecurity = false
                                    showLocationHistory = true
                                },
                                onOpenHiddenApps = {
                                    showSecurity = false
                                    showHiddenApps = true
                                },
                                onOpenFreezer = {
                                    showSecurity = false
                                    showFreezer = true
                                },
                                showListeningInfo = pendingShowListeningInfo,
                                onDismissListeningInfo = { neverShowAgain ->
                                    pendingShowListeningInfo = false
                                    if (neverShowAgain) {
                                        listeningInfoSeen = true
                                        lockPrefs.edit().putBoolean("listening_info_seen", true).apply()
                                    }
                                },
                                deviceAdminActive = SequenceDeviceAdminReceiver.isActive(this@MainActivity),
                                onRequestDeviceAdmin = { requestDeviceAdmin() },
                                fullWipeEnabled = isFullWipeEnabled(lockPrefs),
                                onToggleFullWipe = { enabled -> setFullWipeEnabled(lockPrefs, enabled) },
                                trackerBlockingActive = trackerBlockingEnabled,
                                onToggleTrackerBlocking = { enabled ->
                                    trackerBlockingEnabled = enabled
                                    toggleTrackerBlocking(enabled)
                                },
                                onOpenRouterSettings = {
                                    openUrlInPreferredBrowser(this@MainActivity, "https://myrouter.io")
                                },
                                cedalSharedSystemEnabled = cedalSharedSystemEnabled,
                                onToggleCedalSharedSystem = { enabled ->
                                    cedalSharedSystemEnabled = enabled
                                    CedalSharedSystem.setEnabled(CedalSharedSystem.prefs(this@MainActivity), enabled)
                                    if (enabled) {
                                        CedalSharedSystem.broadcastToSiblings(this@MainActivity)
                                    }
                                },
                                kioskModeEnabled = kioskModeEnabled,
                                onToggleKioskMode = { enabled ->
                                    kioskModeEnabled = enabled
                                    lockPrefs.edit().putBoolean("kiosk_mode_enabled", enabled).apply()
                                    if (enabled) {
                                        runCatching { startLockTask() }
                                    } else {
                                        runCatching { stopLockTask() }
                                    }
                                },
                                onOpenLockScreenSettings = {
                                    runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                                },
                                onArmSequenceMode = {
                                    enterSequenceMode(this@MainActivity, lockPrefs)
                                    speak("Sequence mode activated.")
                                },
                                onExitSequenceMode = {
                                    showBiometricPrompt(
                                        title = "Exit Sequence Mode",
                                        subtitle = "Verify it's really you",
                                        onSuccess = {
                                            exitSequenceMode(this@MainActivity, lockPrefs)
                                            speak("Sequence mode cancelled. Welcome back.")
                                        },
                                        onFailure = {
                                            speak("Negative. Biometric confirmation required to exit sequence mode.")
                                        }
                                    )
                                },
                                onRequestCallScreeningRole = { requestCallScreeningRole() }
                            )
                        }

                        showStorage -> {
                            StorageScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                lockPrefs = lockPrefs,
                                logs = intruderLogs,
                                allApps = allApps,
                                onBack = {
                                    showStorage = false
                                    showSecurity = true
                                }
                            )
                        }

                        showFileBrowser -> {
                            val hasFileAccess = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                android.os.Environment.isExternalStorageManager()
                            } else {
                                ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                            }
                            FileBrowserScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                hasAccess = hasFileAccess,
                                onRequestAccess = {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                        runCatching {
                                            val uri = Uri.parse("package:$packageName")
                                            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri))
                                        }.onFailure {
                                            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                                        }
                                    } else {
                                        sequenceModePermissionsLauncher.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
                                    }
                                },
                                onBack = {
                                    showFileBrowser = false
                                    showSecurity = true
                                }
                            )
                        }

                        showRequests -> {
                            RequestsScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                entries = ActionLog.loadAll(this@MainActivity),
                                onBack = {
                                    showRequests = false
                                    showSecurity = true
                                }
                            )
                        }

                        showUpdates -> {
                            UpdatesScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                entries = UpdateProposalLog.loadAll(this@MainActivity),
                                onBack = {
                                    showUpdates = false
                                    showSecurity = true
                                },
                                onReview = { entry ->
                                    // Elene-originated proposals land here without an immediate
                                    // prompt (she wasn't asked) - reviewing them from this screen
                                    // routes through the exact same fingerprint gate every
                                    // device-owner action already uses, not a separate one.
                                    val actionWord = when (entry.category) {
                                        UpdateCategory.FEATURE_ADDED -> "Add feature"
                                        UpdateCategory.BUG_FIX -> "Fix"
                                        UpdateCategory.FEATURE_REMOVED -> "Remove"
                                        UpdateCategory.OTHER -> "Update"
                                    }
                                    requestDeviceActionConfirmation(
                                        actionLabel = "$actionWord: ${entry.title}",
                                        reason = entry.description,
                                        target = entry.id.toString(),
                                        onApprove = {
                                            UpdateProposalLog.updateStatus(this@MainActivity, entry.id, UpdateProposalStatus.APPROVED)
                                        },
                                        onDeny = {
                                            UpdateProposalLog.updateStatus(this@MainActivity, entry.id, UpdateProposalStatus.DENIED)
                                        }
                                    )
                                }
                            )
                        }

                        showAppLog -> {
                            AppLogScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                entries = (ErrorLog.loadAll(this@MainActivity) + SystemEventLog.loadAll(this@MainActivity))
                                    .sortedByDescending { it.timestamp },
                                onBack = {
                                    showAppLog = false
                                    showSecurity = true
                                }
                            )
                        }

                        showCommands -> {
                            CommandReferenceScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                onBack = {
                                    showCommands = false
                                    showSecurity = true
                                }
                            )
                        }

                        showInstallFlags -> {
                            InstallFlagsScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                entries = InstallFlags.loadAll(this@MainActivity),
                                onBack = {
                                    showInstallFlags = false
                                    showSecurity = true
                                }
                            )
                        }

                        showLocationHistory -> {
                            LocationHistoryScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                entries = LocationHistory.loadAll(this@MainActivity),
                                onOpenInMaps = { entry ->
                                    runCatching {
                                        startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("geo:${entry.lat},${entry.lon}?q=${entry.lat},${entry.lon}")
                                            )
                                        )
                                    }
                                },
                                onClearHistory = {
                                    LocationHistory.clear(this@MainActivity)
                                    showLocationHistory = false
                                    showSecurity = true
                                },
                                onBack = {
                                    showLocationHistory = false
                                    showSecurity = true
                                }
                            )
                        }

                        showNearbyDevices -> {
                            NearbyDevicesScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                hasPermissions = hasNearbyDevicesPermissions(),
                                onRequestPermissions = { requestNearbyDevicesPermissions() },
                                onBack = { showNearbyDevices = false }
                            )
                        }

                        showScreenRecordSetup -> {
                            ScreenRecordSetupScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                selectedArea = pendingRecordArea,
                                onBack = { showScreenRecordSetup = false },
                                onChoosePartialArea = {
                                    showScreenRecordSetup = false
                                    showCropSelector = true
                                },
                                onSelectFullScreen = { pendingRecordArea = null },
                                onStart = { audioMode ->
                                    showScreenRecordSetup = false
                                    beginScreenRecording(audioMode, pendingRecordArea) { recording ->
                                        screenRecordingState = recording
                                    }
                                }
                            )
                        }

                        showCropSelector -> {
                            CropSelectorOverlay(
                                themeColor = themeColor,
                                backgroundBitmap = cropBackgroundBitmap,
                                onConfirm = { rect ->
                                    pendingRecordArea = rect
                                    showCropSelector = false
                                    showScreenRecordSetup = true
                                },
                                onCancel = {
                                    pendingRecordArea = null
                                    showCropSelector = false
                                    showScreenRecordSetup = true
                                }
                            )
                        }

                        showLaptopControl -> {
                            LaptopControlScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                savedToken = laptopTokenState,
                                onSaveToken = { token ->
                                    saveLaptopToken(token)
                                    laptopTokenState = token
                                },
                                onForgetToken = {
                                    forgetLaptopToken()
                                    laptopTokenState = null
                                },
                                onScanQr = { onResult -> launchQrScan(onResult) },
                                onBack = { showLaptopControl = false }
                            )
                        }

                        showFreezer -> {
                            val frozenApps = visibleApps.filter { it.packageName in hiddenApps }
                            FreezerScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                apps = frozenApps,
                                onBack = {
                                    showFreezer = false
                                    showSecurity = true   // go back to Security screen
                                },
                                onAppClick = { pkg ->
                                    launchApp(pkg, visibleApps, pm, maxRecents, lastOpenedPrefs) { updated ->
                                        recentApps = updated
                                    }
                                }
                            )
                        }

                        else -> {
                            DashboardScreen(
                                modifier = Modifier.fillMaxSize(),
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                currentThemeIndex = themeIndex,
                                onBackToWelcome = { showWelcome = true },
                                onOpenApps = {
                                    showApps = true
                                    showWelcome = false
                                    showRecents = false
                                    showSettings = false
                                    showSecurity = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                onOpenRecents = {
                                    showRecents = true
                                    showApps = false
                                    showWelcome = false
                                    showSettings = false
                                    showSecurity = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                onResetLauncher = {
                                    showWelcome = true
                                    showApps = false
                                    showRecents = false
                                    showSettings = false
                                    showSecurity = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                    isPageMode = false
                                    recentApps = emptyList()
                                    hiddenApps = emptySet()
                                    favoriteAppsPkgs = emptySet()
                                },
                                onOpenSettings = {
                                    showSettings = true
                                    showApps = false
                                    showRecents = false
                                    showWelcome = false
                                    showSecurity = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                onOpenSecurity = {
                                    showSecurity = true
                                    showApps = false
                                    showRecents = false
                                    showWelcome = false
                                    showSettings = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                favoriteApps = favoriteApps
                            )
                        }
                    }

                    // DEVICE ACTION CONFIRMATION - never auto-approves. 90s of no response
                    // (voice, tap, anything) auto-snoozes rather than treating silence as yes.
                    // Approval always requires a fingerprint - tapping/saying "yes" only
                    // triggers the prompt, it never approves by itself. Denying the fingerprint
                    // (or the prompt's own Deny button) counts as a real "no".
                    if (pendingConfirmationId != null) {
                        val confId = pendingConfirmationId!!
                        val confLabel = pendingConfirmationLabel
                        val confReason = pendingConfirmationReason

                        fun denyConfirmation() {
                            ActionLog.updateStatus(this@MainActivity, confId, ActionRequestStatus.DENIED)
                            pendingConfirmationDeny?.invoke()
                            pendingConfirmationApprove = null
                            pendingConfirmationDeny = null
                            pendingConfirmationScheduleType = null
                            pendingConfirmationScheduleTarget = null
                            pendingConfirmationId = null
                            // Purely informational - the denial already happened above. A
                            // dismissive answer ("no reason", "nothing") is treated as no
                            // elaboration rather than a literal reason to store.
                            captureVoicePassphrase("Why not? You can just say no reason.") { spoken ->
                                val text = spoken?.trim().orEmpty()
                                val dismissive = setOf("no reason", "none", "nothing", "just no", "no", "n/a", "na")
                                if (text.isNotBlank() && text.lowercase() !in dismissive) {
                                    ActionLog.recordDenialReason(this@MainActivity, confId, text)
                                }
                            }
                        }

                        fun approveConfirmation(onApproved: () -> Unit) {
                            showBiometricPrompt(
                                title = confLabel,
                                subtitle = confReason,
                                onSuccess = {
                                    onApproved()
                                    // Voice ID is a supporting signal on top of fingerprint, not a
                                    // gate - it never delays or blocks the action, just logs whether
                                    // the mic picked up a matching voice at approval time.
                                    if (VoiceIdManager.isEnrolled(this@MainActivity)) {
                                        scope.launch {
                                            val sample = recordVoiceSample(this@MainActivity)
                                            val best = sample?.let { VoiceIdManager.verifyBest(this@MainActivity, it) }
                                            if (best != null) {
                                                ActionLog.recordVoiceMatch(this@MainActivity, confId, best.second)
                                            }
                                        }
                                    }
                                },
                                onFailure = { denyConfirmation() }
                            )
                        }

                        fun snoozeConfirmation(delayMinutes: Int) {
                            ActionLog.updateStatus(this@MainActivity, confId, ActionRequestStatus.SNOOZED)
                            val savedApprove = pendingConfirmationApprove
                            val savedDeny = pendingConfirmationDeny
                            val savedTarget = pendingConfirmationScheduleTarget
                            val savedType = pendingConfirmationScheduleType
                            pendingConfirmationApprove = null
                            pendingConfirmationDeny = null
                            pendingConfirmationScheduleType = null
                            pendingConfirmationScheduleTarget = null
                            pendingConfirmationId = null
                            if (savedApprove != null) {
                                speak("I'll check back in ${formatMinutesForSpeech(delayMinutes)}.")
                                scope.launch {
                                    kotlinx.coroutines.delay(delayMinutes * 60_000L)
                                    requestDeviceActionConfirmation(confLabel, confReason, savedTarget, savedType, savedDeny, savedApprove)
                                }
                            }
                        }

                        DeviceActionConfirmationPanel(
                            themeColor = themeColor,
                            isDark = isDark,
                            actionLabel = confLabel,
                            reason = confReason,
                            allowDelay = pendingConfirmationScheduleType != null,
                            onYesDelayed = { delayMinutes ->
                                approveConfirmation {
                                    val type = pendingConfirmationScheduleType
                                    val target = pendingConfirmationScheduleTarget
                                    if (type != null && target != null) {
                                        scheduleAction(type, target, confLabel, delayMinutes * 60_000L)
                                        ActionLog.updateStatus(this@MainActivity, confId, ActionRequestStatus.APPROVED)
                                        speak("Scheduled: $confLabel in ${formatMinutesForSpeech(delayMinutes)}.")
                                    }
                                    pendingConfirmationApprove = null
                                    pendingConfirmationDeny = null
                                    pendingConfirmationScheduleType = null
                                    pendingConfirmationScheduleTarget = null
                                    pendingConfirmationId = null
                                }
                            },
                            onYes = {
                                approveConfirmation {
                                    ActionLog.updateStatus(this@MainActivity, confId, ActionRequestStatus.APPROVED)
                                    pendingConfirmationApprove?.invoke()
                                    pendingConfirmationApprove = null
                                    pendingConfirmationDeny = null
                                    pendingConfirmationScheduleType = null
                                    pendingConfirmationScheduleTarget = null
                                    pendingConfirmationId = null
                                }
                            },
                            onNo = { denyConfirmation() },
                            onAskLater = {
                                captureVoicePassphrase("How long? Say a time, or I'll check back in 30 minutes.") { spoken ->
                                    if (pendingConfirmationId == confId) {
                                        snoozeConfirmation(parseDurationMinutes(spoken) ?: 30)
                                    }
                                }
                            },
                            onVoiceTap = {
                                captureVoicePassphrase("Yes, no, or ask again later?") { spoken ->
                                    val normalized = spoken?.lowercase()?.trim().orEmpty()
                                    val affirmative = listOf("yes", "yeah", "go ahead", "confirm", "approve", "affirmative", "do it")
                                    val negative = listOf("no", "negative", "deny", "cancel")
                                    val later = listOf("later", "ask again", "snooze", "remind me")
                                    when {
                                        pendingConfirmationId != confId -> Unit // already resolved
                                        affirmative.any { normalized.contains(it) } -> {
                                            approveConfirmation {
                                                ActionLog.updateStatus(this@MainActivity, confId, ActionRequestStatus.APPROVED)
                                                pendingConfirmationApprove?.invoke()
                                                pendingConfirmationApprove = null
                                                pendingConfirmationDeny = null
                                                pendingConfirmationScheduleType = null
                                                pendingConfirmationScheduleTarget = null
                                                pendingConfirmationId = null
                                            }
                                        }
                                        negative.any { normalized.contains(it) } -> denyConfirmation()
                                        later.any { normalized.contains(it) } -> snoozeConfirmation(parseDurationMinutes(normalized) ?: 30)
                                        else -> Unit // unrecognized - leave pending, they can try again
                                    }
                                }
                            }
                        )

                        LaunchedEffect(confId) {
                            kotlinx.coroutines.delay(90_000L)
                            if (pendingConfirmationId == confId) {
                                snoozeConfirmation(30)
                            }
                        }
                    }

                    if (showMessageDraftConfirm) {
                        MessageDraftConfirmationPanel(
                            themeColor = themeColor,
                            isDark = isDark,
                            recipientLabel = pendingMessageRecipientLabel,
                            channelLabel = when (pendingMessageChannel) {
                                "direct_reply" -> "reply"
                                "sms" -> "SMS"
                                else -> "WhatsApp"
                            },
                            draftText = pendingMessageDraftText,
                            onDraftTextChange = { pendingMessageDraftText = it },
                            onRereadTap = { speak(pendingMessageDraftText) },
                            onCancel = { showMessageDraftConfirm = false },
                            onSend = {
                                showMessageDraftConfirm = false
                                when (pendingMessageChannel) {
                                    "direct_reply" -> {
                                        val sent = XenosNotificationListener.instance?.sendDirectReply(pendingMessageTarget, pendingMessageDraftText) == true
                                        speak(if (sent) "Sent." else "Couldn't send that - the app may not be available.")
                                    }
                                    "sms" -> {
                                        val sent = sendSmsAlert(this@MainActivity, pendingMessageTarget, pendingMessageDraftText)
                                        speak(if (sent) "Sent." else "Couldn't send that - the app may not be available.")
                                    }
                                    // WhatsApp's send is now a real poll-and-tap (up to ~10s),
                                    // not a single guessed delay - the true/false result only
                                    // arrives once that finishes, so the confirmation speaks
                                    // from the callback, not synchronously here.
                                    else -> sendWhatsAppAlert(this@MainActivity, pendingMessageTarget, pendingMessageDraftText) { sent ->
                                        speak(if (sent) "Sent." else "Couldn't send that - WhatsApp may not have opened the chat in time.")
                                    }
                                }
                            }
                        )
                    }

                    // Custom notification/quick-settings bar: stands in for the system shade,
                    // which Kiosk mode blocks. Left = notifications, right = settings - same
                    // split as the real status bar's two independent pull zones. Both are
                    // drag-to-open/drag-to-close, no close button.
                    // These float on top of every screen regardless of which one is showing,
                    // so they need to respect the same safe-area inset every screen's own
                    // header does - without systemBarsPadding here, they sat flush at the
                    // absolute top edge and visually crowded into each screen's back button.
                    NotificationBarTab(
                        themeColor = themeColor,
                        unreadCount = notificationFeed.size,
                        onClick = { showNotificationPanel = true },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .systemBarsPadding()
                    )
                    QuickSettingsTab(
                        themeColor = themeColor,
                        onClick = { showQuickSettingsPanel = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .systemBarsPadding()
                    )

                    if (showNotificationPanel) {
                        NotificationsPanel(
                            themeColor = themeColor,
                            isDark = isDark,
                            listenerEnabled = isNotificationListenerEnabled(this@MainActivity),
                            onEnableListener = {
                                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                            },
                            notifications = notificationFeed.asReversed(),
                            onOpen = { item ->
                                openAppSmart(item.packageName, allAppsState, favoriteAppsPkgs, contextAndroid)
                                XenosNotificationListener.dismissMissed(item)
                                notificationFeed = XenosNotificationListener.missedNotifications.toList()
                                showNotificationPanel = false
                            },
                            onDismiss = { item ->
                                XenosNotificationListener.dismissMissed(item)
                                notificationFeed = XenosNotificationListener.missedNotifications.toList()
                            },
                            onClearAll = {
                                XenosNotificationListener.clearMissed()
                                notificationFeed = emptyList()
                            },
                            onClose = { showNotificationPanel = false }
                        )
                    }

                    if (showQuickSettingsPanel) {
                        // Refresh real system state each time the panel opens, and keeps
                        // polling while it's open, since Bluetooth/airplane mode changes made
                        // outside this panel (e.g. in real Settings, after a deep-link)
                        // wouldn't otherwise be reflected.
                        LaunchedEffect(Unit) {
                            while (true) {
                                airplaneModeOnState = isAirplaneModeOn()
                                bluetoothOnState = isBluetoothOn()
                                volumeModeState = currentVolumeMode()
                                locationOnState = isLocationOn()
                                dndOnState = isDndOn()
                                brightnessPercentState = currentBrightnessPercent()
                                kotlinx.coroutines.delay(1000L)
                            }
                        }
                        QuickSettingsPanel(
                            themeColor = themeColor,
                            isDark = isDark,
                            batteryPercent = (getSystemService(BATTERY_SERVICE) as? android.os.BatteryManager)
                                ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                                ?.coerceIn(0, 100) ?: 0,
                            quickToggles = QuickToggleState(
                                airplaneModeOn = airplaneModeOnState,
                                flashlightOn = flashlightOnState,
                                bluetoothOn = bluetoothOnState
                            ),
                            extraToggles = ExtraQuickToggleState(
                                volumeMode = volumeModeState,
                                locationOn = locationOnState,
                                dndOn = dndOnState,
                                eyeComfortOn = eyeComfortOnState,
                                screenRecording = screenRecordingState,
                                brightnessPercent = brightnessPercentState
                            ),
                            extraActions = ExtraControlActions(
                                onCycleVolume = { volumeModeState = cycleVolumeMode() },
                                onOpenMobileData = { openMobileDataSettings() },
                                onOpenHotspot = { openHotspotSettings() },
                                onOpenPowerSaving = { openBatterySaverSettings() },
                                onToggleLocation = { locationOnState = toggleLocation() },
                                onOpenLaptopLink = {
                                    showQuickSettingsPanel = false
                                    showLaptopControl = true
                                },
                                onToggleScreenRecord = {
                                    if (screenRecordingState) {
                                        toggleScreenRecord(true) { recording ->
                                            screenRecordingState = recording
                                        }
                                    } else {
                                        showQuickSettingsPanel = false
                                        pendingRecordArea = null
                                        cropBackgroundBitmap = captureScreenBitmap()
                                        showScreenRecordSetup = true
                                    }
                                },
                                onToggleEyeComfort = { eyeComfortOnState = toggleEyeComfort(eyeComfortOnState) },
                                onToggleDnd = { dndOnState = toggleDnd() },
                                onOpenWifiCalling = { openWifiCallingSettings() },
                                onScanQr = { launchQrScan() },
                                onOpenMultiControl = { openMultiWindowSettings() },
                                onOpenSecureFolder = { openSecureFolder() },
                                onOpenAudioBroadcast = { openAudioSharingSettings() },
                                onBrightnessChange = { percent ->
                                    brightnessPercentState = percent
                                    setBrightnessPercent(percent)
                                },
                                onOpenNearbyDevices = {
                                    showQuickSettingsPanel = false
                                    showNearbyDevices = true
                                }
                            ),
                            onOpenAirplaneModeSettings = { openAirplaneModeSettings() },
                            onToggleFlashlight = { enable ->
                                setFlashlight(contextAndroid, on = enable)
                                flashlightOnState = enable
                            },
                            onToggleBluetooth = {
                                val hasPermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                                    ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                                when {
                                    !hasPermission -> runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                                    isBluetoothOn() -> {
                                        // Android reserves silent Bluetooth-off for the Settings
                                        // app itself - no way around that, on-device or Device Owner.
                                        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                                    }
                                    else -> launchBluetoothEnableRequest()
                                }
                            },
                            onClose = { showQuickSettingsPanel = false }
                        )
                    }

                    // The launcher used to render its own separate Elene bubble here - removed.
                    // Two independent "brains" (this one, and ScifiAccessibilityService's
                    // cross-app overlay bubble) kept drifting out of sync - different voice,
                    // different conversation memory, different screen awareness, and this one
                    // could get stuck displaying a REPLYING state with no way to recover short
                    // of restarting the launcher. The overlay bubble is now shown on the home
                    // screen too (see ScifiAccessibilityService's foreground-app tracking) and
                    // is the only Elene UI anywhere - one implementation, actually kept in sync
                    // with itself by construction instead of by remembering to patch two places.
                }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val pm: PackageManager = packageManager
        allAppsState = loadAllApps(pm)
        applyKioskLockTaskFeatures()

        // Screen Pinning doesn't survive an app restart on its own - re-pin if the user had
        // Kiosk mode on and we're not already pinned (e.g. after a reboot).
        val kioskWanted = getSharedPreferences("lock_prefs", MODE_PRIVATE).getBoolean("kiosk_mode_enabled", false)
        val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
        val alreadyPinned = am?.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
        if (kioskWanted && alreadyPinned != true) {
            runCatching { startLockTask() }
        }
    }

    private fun loadAllApps(pm: PackageManager): List<AppItem> {
        val labelPrefs = getSharedPreferences("app_label_prefs", MODE_PRIVATE)
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        return resolveInfos
            .map {
                val pkg = it.activityInfo.packageName
                AppItem(
                    label = loadAppLabelOverride(labelPrefs, pkg) ?: it.loadLabel(pm).toString(),
                    packageName = pkg,
                    iconBitmap = it.activityInfo.loadIcon(pm).toBitmap()
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Shares the installed app's APK (or, for split-install apps, a zip of base + splits)
     * so it can be sideloaded on another device without the Play Store. Apps with multiple
     * split APKs need a split-APK installer (e.g. "SAI") on the receiving end.
     */
    private fun shareApp(targetPackage: String) {
        runCatching {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(targetPackage, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            val apkPaths = buildList {
                add(appInfo.sourceDir)
                appInfo.splitSourceDirs?.let { addAll(it) }
            }

            val outDir = File(cacheDir, "shared_apks").apply { mkdirs() }
            val outFile: File
            val mimeType: String

            if (apkPaths.size == 1) {
                outFile = File(outDir, "$label.apk")
                File(apkPaths[0]).copyTo(outFile, overwrite = true)
                mimeType = "application/vnd.android.package-archive"
            } else {
                outFile = File(outDir, "$label.apks")
                java.util.zip.ZipOutputStream(outFile.outputStream()).use { zip ->
                    apkPaths.forEachIndexed { i, path ->
                        zip.putNextEntry(java.util.zip.ZipEntry(if (i == 0) "base.apk" else "split_$i.apk"))
                        File(path).inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                mimeType = "application/zip"
                Toast.makeText(
                    this,
                    "$label uses multiple install files - whoever you send it to needs a split-APK installer app (like \"SAI\") to install it.",
                    Toast.LENGTH_LONG
                ).show()
            }

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share $label"))
        }.onFailure {
            Toast.makeText(this, "Could not share this app.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBiometricPrompt(
        title: String? = null,
        subtitle: String? = null,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        pendingBiometricCallback = onSuccess to onFailure
        pendingBiometricLabel = title ?: "Device action"
        SystemEventLog.record(this, "Biometric", "$pendingBiometricLabel: shown")
        runCatching {
            biometricAuthLauncher.launch(
                Intent(this, BiometricAuthActivity::class.java).apply {
                    if (title != null) putExtra(BiometricAuthActivity.EXTRA_TITLE, title)
                    if (subtitle != null) putExtra(BiometricAuthActivity.EXTRA_SUBTITLE, subtitle)
                }
            )
        }.onFailure {
            pendingBiometricCallback = null
            onFailure()
        }
    }

    /**
     * Wrong PIN was entered. Warns the user, requests a fingerprint, and starts a 5-minute
     * timer - if biometrics aren't confirmed in that window, Sequence Mode activates.
     */
    private fun launchApp(
        pkg: String,
        apps: List<AppItem>,
        pm: PackageManager,
        maxRecents: Int,
        lastOpenedPrefs: SharedPreferences,
        updateRecents: (List<AppItem>) -> Unit
    ) {
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            startActivity(launchIntent)
            recordLastOpened(lastOpenedPrefs, pkg)

            val clickedApp = apps.firstOrNull { it.packageName == pkg }
            if (clickedApp != null) {
                val recents = listOf(clickedApp)
                updateRecents(recents.take(maxRecents))
            }
        }
    }

    // Open another app by exact package name. Returns whether an activity actually launched.
    fun openApp(packageName: String, context: Context): Boolean {
        return try {
            val pm: PackageManager = context.packageManager
            val launchIntent: Intent? = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                Log.e("Elene", "App not found: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e("Elene", "Failed to open app: $packageName", e)
            false
        }
    }

    /** Elene's LLM doesn't actually know which apps are installed on this specific phone, so
     * "open_app:<value>" may carry a guessed package name that doesn't resolve, or just be the
     * plain spoken app name. AppResolver is the shared matcher (also used by the cross-app
     * overlay bubble) - it queries PackageManager live rather than a cached list, so a just-
     * installed app resolves immediately. [apps] is unused now but kept so existing call sites
     * don't need to change. */
    fun openAppSmart(pkgOrLabel: String, apps: List<AppItem>, favorites: Set<String>, context: Context): Boolean {
        val pkg = AppResolver.resolvePackageName(context, pkgOrLabel, favorites) ?: return false
        return openApp(pkg, context)
    }

    // Simple page enum for your launcher; adjust names to your real pages
    enum class LauncherPage { HOME, APPS, GAMES, SETTINGS }

    // Dummy navigation + scroll functions to avoid unresolved references.
// Replace their bodies with your real logic (state updates, scroll, etc.).
    fun setPage(page: LauncherPage) {
        Log.d("Elene", "Switch page to $page (TODO: implement real navigation)")
    }

    fun scrollCurrentListUp() {
        Log.d("Elene", "Scroll up (TODO: hook into your LazyColumn / pager)")
    }

    fun scrollCurrentListDown() {
        Log.d("Elene", "Scroll down (TODO: hook into your LazyColumn / pager)")
    }

    fun goToPreviousPage() {
        Log.d("Elene", "Go to previous page (TODO: implement)")
    }

    fun goToNextPage() {
        Log.d("Elene", "Go to next page (TODO: implement)")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val themePrefs = getSharedPreferences("theme_prefs", MODE_PRIVATE)
            val lang = loadLanguage(themePrefs)
            val locale = when (lang) {
                LanguageOption.ENGLISH -> Locale.UK
                LanguageOption.YORUBA -> Locale("yo")
                LanguageOption.MANDARIN -> Locale.SIMPLIFIED_CHINESE
                LanguageOption.KOREAN -> Locale.KOREAN
                LanguageOption.FRENCH -> Locale.FRENCH
                LanguageOption.SPANISH -> Locale("es")
                LanguageOption.GERMAN -> Locale.GERMAN
            }
            tts?.language = locale
            tts?.setSpeechRate(1.3f)
            tts?.setPitch(1.1f)

            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    val callback = pendingSpeechDoneCallback
                    pendingSpeechDoneCallback = null
                    if (callback != null) {
                        runOnUiThread { callback() }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    val callback = pendingSpeechDoneCallback
                    pendingSpeechDoneCallback = null
                    if (callback != null) {
                        runOnUiThread { callback() }
                    }
                }
            })
        }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Elene")
        }

        try {
            speechRecognitionLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice input not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun speak(text: String) = speakWithCompletion(text) {}

    // Speaks [text] via ElevenLabs (matches Elene's voice), falling back to the on-device
    // system voice if the backend isn't reachable/configured. Invokes onDone once playback
    // finishes (or immediately if speech is suppressed) - used to time the bubble's glow.
    private fun speakWithCompletion(text: String, onDone: () -> Unit) {
        val themePrefs = getSharedPreferences("theme_prefs", MODE_PRIVATE)
        val batteryPrefs = getSharedPreferences("battery_prefs", MODE_PRIVATE)

        val eleneVoiceOn = themePrefs.getBoolean("elene_voice_on", true)
        val mode = loadBatterySaverMode(batteryPrefs)
        val canSpeak = eleneVoiceOn && mode == BatterySaverMode.OFF

        if (!canSpeak) {
            onDone()
            return
        }

        lifecycleScope.launch {
            val audio = runCatching { EleneApiClient.fetchTtsAudio(text) }
                .onFailure { Log.e("EleneVoice", "fetchTtsAudio threw", it) }
                .getOrNull()
            Log.d("EleneVoice", "fetchTtsAudio returned ${audio?.size ?: 0} bytes")

            val played = if (audio != null) playAudioBytes(audio, onDone) else false
            if (!played) {
                Log.d("EleneVoice", "Falling back to system TTS")
                pendingSpeechDoneCallback = onDone
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, VOICE_ID)
            }
        }
    }

    private var mediaPlayer: android.media.MediaPlayer? = null

    /** Plays [bytes] as mp3 via MediaPlayer (off the main thread). Returns true if
     * playback actually started. */
    private suspend fun playAudioBytes(bytes: ByteArray, onDone: () -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                withContext(Dispatchers.Main) { mediaPlayer?.release() }
                val file = File(cacheDir, "elene_tts_${System.currentTimeMillis()}.mp3")
                file.writeBytes(bytes)

                val player = android.media.MediaPlayer()
                player.setOnCompletionListener { mp ->
                    mp.release()
                    if (mediaPlayer === mp) mediaPlayer = null
                    file.delete()
                    onDone()
                }
                player.setOnErrorListener { mp, what, extra ->
                    Log.e("EleneVoice", "MediaPlayer error: what=$what extra=$extra")
                    mp.release()
                    if (mediaPlayer === mp) mediaPlayer = null
                    file.delete()
                    onDone()
                    true
                }
                player.setDataSource(file.absolutePath)
                player.prepare()
                withContext(Dispatchers.Main) {
                    player.start()
                    mediaPlayer = player
                }
                true
            }.onFailure { Log.e("EleneVoice", "playAudioBytes failed", it) }
                .getOrDefault(false)
        }

    override fun onDestroy() {
        unregisterReceiver(notificationVoiceReceiver)
        unregisterReceiver(uninstallStatusReceiver)
        unregisterReceiver(packageChangeReceiver)
        tts?.stop()
        tts?.shutdown()
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

// =====================
//  ELENE ENTRY POINT
// =====================

    fun onUserText(text: String) {
        val normalized = text.trim()
        val lower = normalized.lowercase(Locale.getDefault())

        // load user-defined command word, default "elene"
        val lockPrefs = getSharedPreferences("lock_prefs", MODE_PRIVATE)
        val cmdWord = lockPrefs.getString("voice_command_word", "elene") ?: "elene"
        val cmdLower = cmdWord.lowercase(Locale.getDefault()).trim()

        // 1) Exact "command word" → treat like old "hey elene"
        if (lower == cmdLower || lower == "hey elene") {
            val lang = currentLanguage()
            val phonePrefs = getSharedPreferences("phone_prefs", MODE_PRIVATE)
            val userName = loadUserName(phonePrefs)
            speak(elenePhrase("hey_elene_prompt", lang, userName))
            return
        }

        // 2) Starts with command word (e.g. "elene open whatsapp")
        if (cmdLower.isNotEmpty() && lower.startsWith(cmdLower)) {
            val afterName = normalized.drop(cmdLower.length).trim()
            val command = if (afterName.isBlank()) "let's talk" else afterName
            handleAssistantCommand(command)
            return
        }

        // 3) Legacy: if user literally says "elene ..." keep supporting it
        if (lower.startsWith("elene")) {
            val afterName = normalized.drop(5).trim()
            val command = if (afterName.isBlank()) "let's talk" else afterName
            handleAssistantCommand(command)
        } else {
            handleAssistantCommand(normalized)
        }
    }

    fun handleAssistantCommand(input: String) {
        handleAssistantCommandLocal(input)
    }

    fun formatShortTime(millis: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(millis))
    }

    private fun handleAssistantCommandLocal(input: String) {
        val normalized = input.lowercase(Locale.getDefault())
        val missed = XenosNotificationListener.missedNotifications
        val msg = XenosNotificationListener.lastMessageInfo

        // DEV reset
        if (normalized == "reset notifications") {
            XenosNotificationListener.missedNotifications.clear()
            XenosNotificationListener.lastMessageInfo = null
            XenosNotificationListener.hasUnreadMessage = false
            speak("I cleared your missed notifications history.")
            return
        }

        // waiting for reply text → send direct reply
        if (waitingForReplyText &&
            replyTargetKey != null &&
            replyTargetAppName != null &&
            replyTargetTitle != null
        ) {
            val key = replyTargetKey!!
            val service = XenosNotificationListener.instance
            val ok = service?.sendDirectReply(key, input) ?: false
            if (ok) {
                speak("Okay. I replied to $replyTargetTitle on $replyTargetAppName.")
            } else {
                speak("I tried to reply, but this notification does not support direct reply or the listener is not connected.")
            }
            waitingForReplyText = false
            replyTargetKey = null
            replyTargetTitle = null
            replyTargetAppName = null
            return
        }

        // yes/no after "hey elene"
        if (normalized == "yes" || normalized == "yeah" || normalized == "yep") {
            if (!readingAllMissed && missed.isNotEmpty()) {
                readingAllMissed = true
                currentReadIndex = 0
                speak("Okay. I will read your missed notifications.")
                readNextMissed()
                return
            }
        } else if (normalized == "no" || normalized == "nah") {
            if (!readingAllMissed) {
                speak("Okay. I won't check missed notifications right now.")
                return
            }
        }

        // read again
        if (normalized.contains("read again") && missed.isNotEmpty()) {
            readingAllMissed = true
            currentReadIndex = 0
            speak("Restarting the summary of your missed notifications.")
            readNextMissed()
            return
        }

        // summary
        if (normalized.contains("give summary") ||
            normalized.contains("notification summary") ||
            normalized.contains("summary of notifications")
        ) {
            if (missed.isEmpty()) {
                speak("You don't have any missed notifications right now.")
                return
            }

            val byApp = missed.groupBy { it.appName }
            val parts = byApp.map { (app, list) -> "${list.size} from $app" }
            val summary = parts.joinToString(", ")
            speak("You have ${missed.size} missed notifications: $summary.")
            return
        }

        // "reply" command
        if (normalized == "reply" || normalized.startsWith("reply ")) {
            if (missed.isEmpty()) {
                speak("There is no recent notification to reply to.")
                return
            }
            askingWhichToReply = true
            askingWhichPerson = false
            waitingForReplyText = false
            replyTargetKey = null
            replyTargetTitle = null
            replyTargetAppName = null
            pendingAppForReply = null

            val words = normalized.split(" ")
            val possibleApp = words.lastOrNull()
            if (possibleApp != null && possibleApp != "reply" && possibleApp != "to") {
                pendingAppForReply = possibleApp
            }

            val byApp = missed.groupBy { it.appName }
            val appNames = byApp.keys.joinToString(", ")
            val lang = currentLanguage()
            val phonePrefs = getSharedPreferences("phone_prefs", MODE_PRIVATE)
            val userName = loadUserName(phonePrefs)

            if (pendingAppForReply == null) {
                speak(elenePhrase("ask_which_app_general", lang, userName, appNames))
            } else {
                speak(elenePhrase("ask_which_app_retry", lang, userName))
            }
            return
        }

        // app selection
        if (askingWhichToReply) {
            val selection = if (pendingAppForReply != null) pendingAppForReply!! else normalized
            val selectionLower = selection.lowercase(Locale.ROOT)

            val replyables = XenosNotificationListener.replyableMap.values
            val forThisApp = replyables.filter {
                it.appName.lowercase(Locale.ROOT).contains(selectionLower) ||
                        it.packageName.lowercase(Locale.ROOT).contains(selectionLower)
            }

            if (forThisApp.isEmpty()) {
                val lang = currentLanguage()
                val phonePrefs = getSharedPreferences("phone_prefs", MODE_PRIVATE)
                val userName = loadUserName(phonePrefs)
                speak(elenePhrase("no_replyable_for_app", lang, userName))
                pendingAppForReply = null
                askingWhichToReply = false
                return
            }

            askingWhichToReply = false
            pendingAppForReply = selectionLower

            if (forThisApp.size == 1) {
                val chosen = forThisApp.first()
                replyTargetKey = chosen.key
                replyTargetTitle = chosen.title.ifBlank { "the chat" }
                replyTargetAppName = chosen.appName
                waitingForReplyText = true
                val lang = currentLanguage()
                val phonePrefs = getSharedPreferences("phone_prefs", MODE_PRIVATE)
                val userName = loadUserName(phonePrefs)
                speak(
                    elenePhrase(
                        "ask_message_text",
                        lang,
                        userName,
                        replyTargetTitle ?: "the chat",
                        chosen.appName
                    )
                )
            } else {
                askingWhichPerson = true
                val names = forThisApp.map { it.title.ifBlank { "a chat" } }.distinct()
                val listNames = names.joinToString(", ")
                val lang = currentLanguage()
                val phonePrefs = getSharedPreferences("phone_prefs", MODE_PRIVATE)
                val userName = loadUserName(phonePrefs)
                speak(elenePhrase("multiple_people_prompt", lang, userName, listNames))
            }
            return
        }

        // person selection
        if (askingWhichPerson && pendingAppForReply != null) {
            val selectionLower = normalized.lowercase(Locale.ROOT)

            val replyables = XenosNotificationListener.replyableMap.values
            val forThisApp = replyables.filter {
                it.appName.lowercase(Locale.ROOT).contains(pendingAppForReply!!) ||
                        it.packageName.lowercase(Locale.ROOT).contains(pendingAppForReply!!)
            }

            if (forThisApp.isEmpty()) {
                val lang = currentLanguage()
                val phonePrefs = getSharedPreferences("phone_prefs", MODE_PRIVATE)
                val userName = loadUserName(phonePrefs)
                speak(elenePhrase("lost_notifications_for_app", lang, userName))
                askingWhichPerson = false
                pendingAppForReply = null
                return
            }

            val candidates = forThisApp.filter {
                it.title.lowercase(Locale.ROOT).contains(selectionLower)
            }

            if (candidates.isEmpty()) {
                val names = forThisApp.map { it.title.ifBlank { "a chat" } }.distinct()
                val listNames = names.joinToString(", ")
                val lang = currentLanguage()
                val phonePrefs = getSharedPreferences("phone_prefs", MODE_PRIVATE)
                val userName = loadUserName(phonePrefs)
                speak(elenePhrase("person_not_found", lang, userName, listNames))
                return
            }

            val chosen = candidates.last()
            askingWhichPerson = false
            waitingForReplyText = true
            replyTargetKey = chosen.key
            replyTargetTitle = chosen.title.ifBlank { "the chat" }
            replyTargetAppName = chosen.appName
            speak("What do you want me to reply to $replyTargetTitle on ${chosen.appName}?")
            return
        }

        // no reply flow → normal notification logic
        if (msg == null || !XenosNotificationListener.hasUnreadMessage) {
            val lang = currentLanguage()
            val phonePrefs = getSharedPreferences("phone_prefs", MODE_PRIVATE)
            val userName = loadUserName(phonePrefs)
            speak(elenePhrase("im_here", lang, userName))
            return
        }

        val lowerText = msg.text.lowercase(Locale.getDefault())
        val isCallNotification = lowerText.contains("calling") ||
                lowerText.contains("incoming call") ||
                lowerText.contains("is calling you") ||
                lowerText.contains("missed call")

        if (isCallNotification) {
            speak("You had a call from ${msg.title} on ${msg.appName}.")
        } else {
            speak("From ${msg.title} on ${msg.appName}: ${msg.text}")
        }

        XenosNotificationListener.hasUnreadMessage = false
    }

    private fun readNextMissed() {
        val missed = XenosNotificationListener.missedNotifications
        if (currentReadIndex >= missed.size) {
            readingAllMissed = false
            currentReadIndex = 0
            XenosNotificationListener.hasUnreadMessage = false
            val lang = currentLanguage()
            val phonePrefs = getSharedPreferences("phone_prefs", MODE_PRIVATE)
            val userName = loadUserName(phonePrefs)
            speak(elenePhrase("all_missed_done", lang, userName))
            return
        }
        val item = missed[currentReadIndex]
        currentReadIndex++
        val from = item.title.ifBlank { "someone" }
        val text = item.text.ifBlank { "no content" }
        speak("From $from on ${item.appName}: $text.")
        if (readingAllMissed) {
            readNextMissed()
        }
    }
}

