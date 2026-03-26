package com.example.scifilauncher

import android.content.SharedPreferences

enum class IconMode {
    DARK,
    LIGHT
}

fun loadIconMode(prefs: SharedPreferences): IconMode {
    val name = prefs.getString("icon_mode", IconMode.DARK.name)
    return try {
        IconMode.valueOf(name ?: IconMode.DARK.name)
    } catch (_: IllegalArgumentException) {
        IconMode.DARK
    }
}

fun saveIconMode(prefs: SharedPreferences, mode: IconMode) {
    prefs.edit().putString("icon_mode", mode.name).apply()
}
