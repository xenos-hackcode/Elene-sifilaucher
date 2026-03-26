package com.example.scifilauncher

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun recordLastOpened(prefs: SharedPreferences, pkg: String) {
    prefs.edit()
        .putLong(pkg, System.currentTimeMillis())
        .apply()
}

fun getLastOpenedText(prefs: SharedPreferences, pkg: String): String {
    val ts = prefs.getLong(pkg, -1L)
    if (ts <= 0L) return "Last opened: --"
    val fmt = SimpleDateFormat("HH:mm dd MMM", Locale.getDefault())
    return "Last opened: " + fmt.format(Date(ts))
}
