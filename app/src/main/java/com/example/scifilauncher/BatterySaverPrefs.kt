package com.example.scifilauncher

import android.content.SharedPreferences

enum class BatterySaverMode {
    OFF,
    BALANCED,
    AGGRESSIVE
}

fun loadBatterySaverMode(prefs: SharedPreferences): BatterySaverMode {
    val name = prefs.getString("battery_saver_mode", BatterySaverMode.OFF.name)
    return try {
        BatterySaverMode.valueOf(name ?: BatterySaverMode.OFF.name)
    } catch (_: IllegalArgumentException) {
        BatterySaverMode.OFF
    }
}

fun saveBatterySaverMode(prefs: SharedPreferences, mode: BatterySaverMode) {
    prefs.edit().putString("battery_saver_mode", mode.name).apply()
}

// ADD THESE TWO:

fun loadBatteryAllowedApps(prefs: SharedPreferences): Set<String> {
    val raw = prefs.getString("battery_allowed_apps", "") ?: ""
    if (raw.isEmpty()) return emptySet()
    return raw.split(",").filter { it.isNotBlank() }.toSet()
}

fun saveBatteryAllowedApps(prefs: SharedPreferences, set: Set<String>) {
    val raw = set.joinToString(",")
    prefs.edit().putString("battery_allowed_apps", raw).apply()
}
