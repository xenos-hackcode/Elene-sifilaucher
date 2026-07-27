package com.example.scifilauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

data class CurrentMeeting(val title: String, val endsAt: Long)

/** Informational only - lets Elene know/mention that the user is currently in a scheduled
 * meeting. Never triggers any action (muting, silencing, etc.) on its own; that only ever
 * happens as its own explicit user-approved command, same as everything else this app does. */
fun currentMeeting(context: Context): CurrentMeeting? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
        return null
    }

    val now = System.currentTimeMillis()
    val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
    android.content.ContentUris.appendId(uriBuilder, now)
    android.content.ContentUris.appendId(uriBuilder, now)

    return runCatching {
        context.contentResolver.query(
            uriBuilder.build(),
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.END),
            null, null,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
                val title = if (titleIdx >= 0) cursor.getString(titleIdx) else null
                val endsAt = if (endIdx >= 0) cursor.getLong(endIdx) else 0L
                if (!title.isNullOrBlank()) CurrentMeeting(title, endsAt) else null
            } else {
                null
            }
        }
    }.getOrNull()
}
