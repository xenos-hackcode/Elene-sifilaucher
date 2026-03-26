package com.example.scifilauncher

import android.content.SharedPreferences

fun filterAppsForBatteryMode(
    allApps: List<AppItem>,
    batteryMode: BatterySaverMode,
    batteryPrefs: SharedPreferences
): List<AppItem> {
    // TEMP: no filtering, just return everything.
    // Later you can add real logic based on batteryMode and prefs.
    return allApps
}
