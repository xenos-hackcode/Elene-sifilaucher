package com.example.scifilauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.TimeUnit

// Sequence Mode: anti-theft lockdown + family/email alerts + delayed wipe.
// All state lives in the same "lock_prefs" SharedPreferences used by LockPrefs.kt.

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

/** Resolves phone numbers for contacts named/labelled father, mother, brother ("bro"), lil sis. */
fun resolveFamilyContacts(context: Context): List<FamilyContact> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
        return emptyList()
    }

    val targets = linkedMapOf(
        "father" to listOf("father", "dad"),
        "mother" to listOf("mother", "mum", "mom"),
        "brother" to listOf("brother", "bro"),
        "lil sis" to listOf("lil sis", "little sister", "sister", "sis")
    )

    val resolver = context.contentResolver
    val found = mutableListOf<FamilyContact>()

    for ((label, aliases) in targets) {
        for (alias in aliases) {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$alias%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val number = if (numberIdx >= 0) cursor.getString(numberIdx) else null
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else alias
                    if (!number.isNullOrBlank()) {
                        found.add(FamilyContact(label, name ?: alias, number))
                    }
                }
            }
            if (found.any { it.label == label }) break
        }
    }
    return found
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

/** Opens a WhatsApp chat pre-filled with [message] and taps Send via Accessibility. */
fun sendWhatsAppAlert(context: Context, phoneNumber: String, message: String) {
    val sanitized = phoneNumber.filter { it.isDigit() || it == '+' }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse("https://wa.me/$sanitized?text=${Uri.encode(message)}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }

    // Give WhatsApp a moment to open and render the chat before trying to tap Send.
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        ScifiAccessibilityService.instance?.clickByText("Send")
    }, 3500L)
}

fun enterSequenceMode(context: Context, lockPrefs: SharedPreferences) {
    if (isSequenceModeActive(lockPrefs)) return

    lockPrefs.edit()
        .putBoolean(KEY_SEQUENCE_MODE_ACTIVE, true)
        .putLong(KEY_SEQUENCE_MODE_STARTED_AT, System.currentTimeMillis())
        .apply()

    captureLastLocation(context, lockPrefs)
    SequenceDeviceAdminReceiver.lockNow(context)

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
