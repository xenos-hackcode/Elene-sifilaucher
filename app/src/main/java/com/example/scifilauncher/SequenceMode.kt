package com.example.scifilauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.TimeUnit

// Sequence Mode: anti-theft lockdown + family/email alerts + delayed wipe.
// All state lives in the shared "lock_prefs" SharedPreferences file (also used for a handful
// of unrelated settings elsewhere - kiosk mode, install-watch, location history, etc.).

const val KEY_SEQUENCE_MODE_ACTIVE = "sequence_mode_active"
private const val KEY_SEQUENCE_MODE_STARTED_AT = "sequence_mode_started_at"
const val KEY_SEQUENCE_MODE_WIPED = "sequence_mode_wiped"
private const val KEY_LAST_LOCATION_LAT = "last_location_lat"
private const val KEY_LAST_LOCATION_LNG = "last_location_lng"
private const val KEY_LAST_LOCATION_AT = "last_location_at"
private const val KEY_FULL_WIPE_ENABLED = "sequence_mode_full_wipe_enabled"

const val SEQUENCE_ALERT_WORK_NAME = "sequence_mode_alerts"
const val SEQUENCE_WIPE_WORK_NAME = "sequence_mode_wipe"
private val ALERT_REPEAT_WINDOW_MILLIS = TimeUnit.DAYS.toMillis(2)
private val WIPE_DELAY_MILLIS = TimeUnit.DAYS.toMillis(30)

data class FamilyContact(val label: String, val displayName: String, val phoneNumber: String)

fun isSequenceModeActive(prefs: SharedPreferences): Boolean =
    prefs.getBoolean(KEY_SEQUENCE_MODE_ACTIVE, false)

fun sequenceModeStartedAt(prefs: SharedPreferences): Long =
    prefs.getLong(KEY_SEQUENCE_MODE_STARTED_AT, System.currentTimeMillis())

fun isDeviceWiped(prefs: SharedPreferences): Boolean =
    prefs.getBoolean(KEY_SEQUENCE_MODE_WIPED, false)

/** Opt-in only: whether a never-recovered Sequence Mode should factory-reset the whole
 * device (via Device Admin) instead of just clearing this app's protected data. */
fun isFullWipeEnabled(prefs: SharedPreferences): Boolean =
    prefs.getBoolean(KEY_FULL_WIPE_ENABLED, false)

fun setFullWipeEnabled(prefs: SharedPreferences, enabled: Boolean) {
    prefs.edit().putBoolean(KEY_FULL_WIPE_ENABLED, enabled).apply()
}

fun loadLastKnownLocation(prefs: SharedPreferences): Triple<Double, Double, Long>? {
    val at = prefs.getLong(KEY_LAST_LOCATION_AT, -1L)
    if (at < 0) return null
    val lat = prefs.getString(KEY_LAST_LOCATION_LAT, null)?.toDoubleOrNull() ?: return null
    val lng = prefs.getString(KEY_LAST_LOCATION_LNG, null)?.toDoubleOrNull() ?: return null
    return Triple(lat, lng, at)
}

private fun saveLastKnownLocation(prefs: SharedPreferences, lat: Double, lng: Double) {
    prefs.edit()
        .putString(KEY_LAST_LOCATION_LAT, lat.toString())
        .putString(KEY_LAST_LOCATION_LNG, lng.toString())
        .putLong(KEY_LAST_LOCATION_AT, System.currentTimeMillis())
        .apply()
}

@SuppressLint("MissingPermission")
fun captureLastLocation(context: Context, prefs: SharedPreferences) {
    val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) return

    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
    val best = lm.getProviders(true)
        .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }
        ?: return

    saveLastKnownLocation(prefs, best.latitude, best.longitude)
}

/** Fixed emergency-alert numbers for Sequence Mode's WhatsApp/SMS distress alert - not a
 * contacts-app lookup, a direct number list the user set. */
private val EMERGENCY_CONTACT_NUMBERS = listOf(
    "+447784412528",
    "+447476853698",
    "+447901613720",
    "+2349037855461",
    "+447391333985",
    "+447501965634",
    "+2208770997",
    "+2348163592559",
    "+2348051982615",
    "+2348035774599",
    "+2347015061644",
    "+2347014748693",
    "+447767105821",
    "+27643716245"
)

fun resolveFamilyContacts(context: Context): List<FamilyContact> =
    EMERGENCY_CONTACT_NUMBERS.mapIndexed { index, number ->
        FamilyContact("emergency contact ${index + 1}", number, number)
    }

fun buildAlertMessage(prefs: SharedPreferences): String {
    val loc = loadLastKnownLocation(prefs)
    val locationPart = if (loc != null) {
        val (lat, lng, _) = loc
        "Last known location: https://maps.google.com/?q=$lat,$lng"
    } else {
        "Location unavailable."
    }
    return "This is an automated alert from Xenos's phone. It may be lost or stolen and is currently locked down. $locationPart"
}

/** Opens a WhatsApp chat pre-filled with [message] and taps Send via Accessibility. [onResult]
 * reports whether Send was actually tapped - a single fixed-delay attempt (the original
 * approach here) turned out unreliable on real hardware: a first-time/unsaved number shows an
 * intermediate "Continue to chat" screen before the real compose screen ever appears, and
 * WhatsApp's own cold-start time varies. Polls for up to ~10s instead of guessing one delay,
 * and tries to tap through that interstitial on every poll (harmless no-op via clickByText if
 * it's not actually there). */
fun sendWhatsAppAlert(context: Context, phoneNumber: String, message: String, onResult: (Boolean) -> Unit = {}) {
    val sanitized = phoneNumber.filter { it.isDigit() || it == '+' }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse("https://wa.me/$sanitized?text=${Uri.encode(message)}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val opened = runCatching { context.startActivity(intent) }.isSuccess
    if (!opened) {
        onResult(false)
        return
    }

    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    // WhatsApp shows this for a number with no existing chat/not in contacts - has to be
    // dismissed before the real compose screen (with the actual Send button) ever appears.
    val continueLabels = listOf("Continue to Chat", "Continue to chat", "CONTINUE TO CHAT", "Continue")
    var attemptsLeft = 18 // ~9s at 500ms, after an initial 1.2s head start for cold-start launch
    lateinit var tick: () -> Unit
    tick = {
        val service = ScifiAccessibilityService.instance
        when {
            service == null -> onResult(false)
            service.clickByText("Send") -> onResult(true)
            else -> {
                continueLabels.forEach { runCatching { service.clickByText(it) } }
                if (attemptsLeft > 0) {
                    attemptsLeft--
                    handler.postDelayed(tick, 500L)
                } else {
                    onResult(false)
                }
            }
        }
    }
    handler.postDelayed(tick, 1200L)
}

/** Direct SMS alert over the cellular network - unlike WhatsApp above, this doesn't depend on
 * WhatsApp being installed, the Accessibility service being enabled, or any internet/data
 * connection at all (SMS rides the cell network directly), so it's sent unconditionally
 * alongside the WhatsApp attempt rather than only as a detected fallback - there's no reliable
 * signal from the WhatsApp tap-to-send that it actually succeeded. */
@SuppressLint("MissingPermission")
fun sendSmsAlert(context: Context, phoneNumber: String, message: String): Boolean {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
        return false
    }
    return runCatching {
        val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(android.telephony.SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            android.telephony.SmsManager.getDefault()
        }
        val parts = smsManager.divideMessage(message)
        smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
        true
    }.getOrDefault(false)
}

fun enterSequenceMode(context: Context, lockPrefs: SharedPreferences) {
    if (isSequenceModeActive(lockPrefs)) return

    lockPrefs.edit()
        .putBoolean(KEY_SEQUENCE_MODE_ACTIVE, true)
        .putLong(KEY_SEQUENCE_MODE_STARTED_AT, System.currentTimeMillis())
        .apply()

    captureLastLocation(context, lockPrefs)
    SequenceDeviceAdminReceiver.lockNow(context)
    LockNotificationListenerService.instance?.applySilence(true)
    SystemEventLog.record(context, "SequenceMode", "Armed")

    val alertRequest = PeriodicWorkRequestBuilder<SequenceAlertWorker>(12, TimeUnit.HOURS).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        SEQUENCE_ALERT_WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, alertRequest
    )

    val wipeRequest = OneTimeWorkRequestBuilder<SequenceWipeWorker>()
        .setInitialDelay(WIPE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        SEQUENCE_WIPE_WORK_NAME, ExistingWorkPolicy.REPLACE, wipeRequest
    )
}

fun exitSequenceMode(context: Context, lockPrefs: SharedPreferences) {
    lockPrefs.edit()
        .putBoolean(KEY_SEQUENCE_MODE_ACTIVE, false)
        .remove(KEY_SEQUENCE_MODE_STARTED_AT)
        .apply()
    WorkManager.getInstance(context).cancelUniqueWork(SEQUENCE_ALERT_WORK_NAME)
    WorkManager.getInstance(context).cancelUniqueWork(SEQUENCE_WIPE_WORK_NAME)
    LockNotificationListenerService.instance?.applySilence(false)
    SystemEventLog.record(context, "SequenceMode", "Exited")
}

/** Called only if Sequence Mode is still active ~30 days after it started (never recovered). */
fun performSequenceWipe(context: Context) {
    val lockPrefs = context.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
    val labelPrefs = context.getSharedPreferences("app_label_prefs", Context.MODE_PRIVATE)
    val phonePrefs = context.getSharedPreferences("phone_prefs", Context.MODE_PRIVATE)

    val fullWipe = isFullWipeEnabled(lockPrefs) && SequenceDeviceAdminReceiver.isActive(context)

    lockPrefs.edit().clear().apply()
    labelPrefs.edit().clear().apply()
    phonePrefs.edit().clear().apply()

    File(context.filesDir, "intruders").deleteRecursively()

    lockPrefs.edit().putBoolean(KEY_SEQUENCE_MODE_WIPED, true).apply()

    // Only reaches here if the user explicitly opted into full-device wipe beforehand -
    // this factory-resets the entire phone, not just this app's data.
    if (fullWipe) {
        SequenceDeviceAdminReceiver.wipeEntireDevice(context)
    }
}

fun isAlertWindowExpired(lockPrefs: SharedPreferences): Boolean {
    val elapsed = System.currentTimeMillis() - sequenceModeStartedAt(lockPrefs)
    return elapsed >= ALERT_REPEAT_WINDOW_MILLIS
}
