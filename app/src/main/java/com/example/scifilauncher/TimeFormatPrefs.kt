package com.example.scifilauncher

import android.content.SharedPreferences

enum class TimeFormatOption {
    FORMAT_12H,
    FORMAT_24H
}

fun loadTimeFormat(prefs: SharedPreferences): TimeFormatOption {
    val name = prefs.getString("time_format", TimeFormatOption.FORMAT_24H.name)
    return try {
        TimeFormatOption.valueOf(name ?: TimeFormatOption.FORMAT_24H.name)
    } catch (e: IllegalArgumentException) {
        TimeFormatOption.FORMAT_24H
    }
}

fun saveTimeFormat(prefs: SharedPreferences, option: TimeFormatOption) {
    prefs.edit().putString("time_format", option.name).apply()
}
