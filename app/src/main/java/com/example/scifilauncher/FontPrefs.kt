package com.example.scifilauncher

import android.content.SharedPreferences

enum class FontSizeOption(val scale: Float) {
    SMALL(0.85f),
    NORMAL(1.0f),
    LARGE(1.15f),
    HUGE(1.3f)
}

fun loadFontSize(prefs: SharedPreferences): FontSizeOption {
    val name = prefs.getString("font_size_option", FontSizeOption.NORMAL.name)
    return try {
        FontSizeOption.valueOf(name ?: FontSizeOption.NORMAL.name)
    } catch (_: IllegalArgumentException) {
        FontSizeOption.NORMAL
    }
}

fun saveFontSize(prefs: SharedPreferences, option: FontSizeOption) {
    prefs.edit().putString("font_size_option", option.name).apply()
}
