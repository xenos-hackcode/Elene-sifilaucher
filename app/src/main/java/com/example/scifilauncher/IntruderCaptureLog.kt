package com.example.scifilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Every genuine failed-fingerprint attempt on a device-owner action confirmation - not a
 * deliberate "Deny" tap (a legitimate owner declining something they were actually asked), but
 * a real non-matching biometric scan (see BiometricAuthActivity.onAuthenticationFailed). Each
 * entry: a photo (front camera, silent, no preview shown), location (best-effort, reused from
 * SequenceMode's own location-capture), and when. */
data class IntruderCapture(
    val id: Long,
    val photoPath: String?,
    val lat: Double?,
    val lng: Double?,
    val timestamp: Long,
    val reason: String
)

private const val PREFS_NAME = "intruder_capture_prefs"
private const val KEY_ENTRIES = "entries"
private const val MAX_ENTRIES = 300

object IntruderCaptureLog {
    @Synchronized
    fun record(context: Context, photoPath: String?, lat: Double?, lng: Double?, reason: String) {
        val list = loadAll(context).toMutableList()
        list.add(0, IntruderCapture(System.currentTimeMillis(), photoPath, lat, lng, System.currentTimeMillis(), reason))
        while (list.size > MAX_ENTRIES) {
            // Delete the photo file along with the log entry it belongs to, not just the record -
            // otherwise old intruder photos accumulate on disk forever past the log's own cap.
            list.removeAt(list.lastIndex).photoPath?.let { runCatching { java.io.File(it).delete() } }
        }
        saveAll(context, list)
    }

    fun loadAll(context: Context): List<IntruderCapture> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ENTRIES, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                IntruderCapture(
                    id = o.getLong("id"),
                    photoPath = if (o.isNull("photoPath")) null else o.optString("photoPath"),
                    lat = if (o.has("lat") && !o.isNull("lat")) o.getDouble("lat") else null,
                    lng = if (o.has("lng") && !o.isNull("lng")) o.getDouble("lng") else null,
                    timestamp = o.getLong("timestamp"),
                    reason = o.optString("reason", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(context: Context, list: List<IntruderCapture>) {
        val arr = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
            o.put("id", e.id)
            o.put("photoPath", e.photoPath)
            o.put("lat", e.lat)
            o.put("lng", e.lng)
            o.put("timestamp", e.timestamp)
            o.put("reason", e.reason)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }
}
