package com.example.scifilauncher

import android.content.SharedPreferences

/** Local nickname overrides for how an app's name shows up in this launcher only. */

fun loadAppLabelOverride(prefs: SharedPreferences, packageName: String): String? =
    prefs.getString("label_$packageName", null)?.ifBlank { null }

fun saveAppLabelOverride(prefs: SharedPreferences, packageName: String, label: String) {
    prefs.edit().putString("label_$packageName", label.trim()).apply()
}

fun clearAppLabelOverride(prefs: SharedPreferences, packageName: String) {
    prefs.edit().remove("label_$packageName").apply()
}
