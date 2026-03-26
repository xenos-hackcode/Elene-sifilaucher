package com.example.scifilauncher

import android.content.SharedPreferences

enum class KeyboardStyle {
    NORMAL,
    XENOS,
    CEDAL
}

fun loadKeyboardStyle(prefs: SharedPreferences): KeyboardStyle {
    val name = prefs.getString("keyboard_style", KeyboardStyle.NORMAL.name)
    return try {
        KeyboardStyle.valueOf(name ?: KeyboardStyle.NORMAL.name)
    } catch (_: Exception) {
        KeyboardStyle.NORMAL
    }
}

fun saveKeyboardStyle(prefs: SharedPreferences, style: KeyboardStyle) {
    prefs.edit().putString("keyboard_style", style.name).apply()
}
