package com.example.scifilauncher

import android.content.Context
import android.os.Build
import android.util.Log

/** Phoenix Protocol (small version, 2026-08-07) - not the full detect/freeze/evacuate/restore
 * flow that was deferred in planner/not_started.md (that one creates a complete off-device copy
 * of every sensitive thing this app holds, which is its own new leak risk). This is the scoped-
 * down piece that was actually judged worth building: a single one-way backup of just the
 * intruder-capture photos and location history, uploaded right before Sequence Mode's own
 * ~30-day auto-wipe deletes them for good, so a stolen/wiped phone doesn't also mean losing the
 * only evidence of who took it. */
object PhoenixEvacuation {
    private const val TAG = "PhoenixEvacuation"

    /** Gathers current IntruderCaptureLog + LocationHistory entries, reads each photo file off
     * disk and base64-encodes it, and uploads all of it to the backend. Returns a short, human-
     * readable summary on success or null on failure - callers decide what that means for them
     * (the real wipe path treats this as best-effort and proceeds regardless of the result). */
    suspend fun uploadBackup(context: Context): String? {
        val intruders = IntruderCaptureLog.loadAll(context)
        val locations = LocationHistory.loadAll(context)
        if (intruders.isEmpty() && locations.isEmpty()) {
            return "Nothing to back up yet (no intruder photos or location history recorded)."
        }

        val photoPayload = intruders.map { entry ->
            val photoBase64 = entry.photoPath?.let { path ->
                runCatching {
                    val bytes = java.io.File(path).readBytes()
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }.onFailure { Log.e(TAG, "Couldn't read intruder photo at $path", it) }.getOrNull()
            }
            EvacuationPhoto(
                id = entry.id,
                timestamp = entry.timestamp,
                reason = entry.reason,
                lat = entry.lat,
                lng = entry.lng,
                photoBase64 = photoBase64
            )
        }
        val locationPayload = locations.map { EvacuationLocationPoint(it.timestamp, it.lat, it.lon) }

        val deviceLabel = "${Build.MANUFACTURER}-${Build.MODEL}".replace(" ", "_")
        val result = EleneApiClient.evacuateBackup(deviceLabel, photoPayload, locationPayload)
        if (result == null) {
            Log.e(TAG, "Evacuation backup failed or backend unreachable")
            return null
        }
        return "Uploaded ${result.uploadedPhotos}/${photoPayload.size} photo(s) and " +
            "${result.locationPoints} location point(s)."
    }
}
