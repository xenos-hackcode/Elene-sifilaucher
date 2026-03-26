package com.example.scifilauncher

import android.content.SharedPreferences

// When user has successfully passed PIN for a locked app
fun recordUnlock(prefs: SharedPreferences) {
    val now = System.currentTimeMillis()
    prefs.edit().putLong("last_unlock_time", now).apply()
}

// If this returns true -> show PIN dialog and treat app as "locked".
// If false -> skip PIN and treat app as "unlocked" within timer window.
fun shouldRequireUnlock(
    prefs: SharedPreferences,
    lockTimeoutMinutes: Int?
): Boolean {
    // OFF → always require PIN
    if (lockTimeoutMinutes == null) return true

    val lastUnlock = prefs.getLong("last_unlock_time", -1L)
    if (lastUnlock <= 0L) return true

    val diff = System.currentTimeMillis() - lastUnlock
    val timeoutMillis = lockTimeoutMinutes * 60_000L
    return diff >= timeoutMillis
}

// Read timer (minutes) from prefs. null = OFF.
fun loadLockTimeoutMinutes(prefs: SharedPreferences): Int? {
    val stored = prefs.getInt("lock_timeout_minutes", -1)
    return if (stored <= 0) null else stored
}

fun saveLockTimeoutMinutes(prefs: SharedPreferences, minutes: Int?) {
    prefs.edit()
        .putInt("lock_timeout_minutes", minutes ?: -1)
        .apply()
}

// Persist locked apps as a comma‑separated list
fun loadLockedApps(prefs: SharedPreferences): Set<String> {
    val raw = prefs.getString("locked_apps", "") ?: ""
    if (raw.isEmpty()) return emptySet()
    return raw.split(",").filter { it.isNotBlank() }.toSet()
}

fun saveLockedApps(prefs: SharedPreferences, set: Set<String>) {
    val raw = set.joinToString(",")
    prefs.edit().putString("locked_apps", raw).apply()
}

// Whether to hide notifications for locked apps while they are locked
fun loadHideLockedNotifications(prefs: SharedPreferences): Boolean {
    return prefs.getBoolean("hide_locked_notifications", true) // default ON
}

fun saveHideLockedNotifications(prefs: SharedPreferences, value: Boolean) {
    prefs.edit().putBoolean("hide_locked_notifications", value).apply()
}
