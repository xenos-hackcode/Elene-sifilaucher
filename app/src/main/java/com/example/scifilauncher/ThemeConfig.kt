package com.example.scifilauncher

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

// Base theme definition with default battery colors
data class CedalTheme(
    val name: String,
    val primary: Color,
    val secondary: Color,
    val defaultBatteryHigh: Color,
    val defaultBatteryMedium: Color,
    val defaultBatteryLow: Color
)

val CedalThemes = listOf(
    CedalTheme(
        name = "Matrix",
        primary = MatrixGreen,
        secondary = Color(0xFF00B0FF),
        defaultBatteryHigh = MatrixGreen,
        defaultBatteryMedium = Color(0xFFFFC107),
        defaultBatteryLow = Color(0xFFFF3B30)
    ),
    CedalTheme(
        name = "Inferno",
        primary = Color(0xFFFF3B3B),
        secondary = Color(0xFFFFA000),
        defaultBatteryHigh = Color(0xFFFFA000),
        defaultBatteryMedium = Color(0xFFFFC107),
        defaultBatteryLow = Color(0xFFFF3B30)
    ),
    CedalTheme(
        name = "Ocean",
        primary = Color(0xFF00B0FF),
        secondary = Color(0xFF00FFC8),
        defaultBatteryHigh = Color(0xFF00FFC8),
        defaultBatteryMedium = Color(0xFFFFC107),
        defaultBatteryLow = Color(0xFFFF3B30)
    ),
    CedalTheme(
        name = "Violet",
        primary = Color(0xFFBB86FC),
        secondary = Color(0xFF03DAC5),
        defaultBatteryHigh = Color(0xFFBB86FC),
        defaultBatteryMedium = Color(0xFFFFC107),
        defaultBatteryLow = Color(0xFFFF3B30)
    )
)

// Small palette of selectable battery colors
val BatteryColorOptions = listOf(
    Color(0xFF00FF00), // neon green
    Color(0xFF00B0FF), // blue
    Color(0xFFFFC107), // amber
    Color(0xFFFF3B30), // red
    Color(0xFFBB86FC), // violet
    Color(0xFF00FFC8)  // cyan
)

// ---------- Battery color persistence helpers ----------

private const val BATTERY_THEME_PREFS = "battery_theme_prefs"

// Keys look like: "theme_0_high", "theme_0_medium", "theme_0_low"
private fun key(themeIndex: Int, level: String) = "theme_${themeIndex}_$level"

private fun saveColor(prefs: SharedPreferences, key: String, color: Color) {
    prefs.edit().putInt(key, color.toArgb()).apply()
}

private fun loadColor(prefs: SharedPreferences, key: String, defaultColor: Color): Color {
    return if (prefs.contains(key)) {
        Color(prefs.getInt(key, defaultColor.toArgb()))
    } else {
        defaultColor
    }
}

// Public API used in Settings and HudCircle
data class BatteryColors(
    val high: Color,
    val medium: Color,
    val low: Color
)

fun loadBatteryColorsForTheme(
    prefs: SharedPreferences,
    themeIndex: Int
): BatteryColors {
    val base = CedalThemes[themeIndex % CedalThemes.size]

    val highKey = key(themeIndex, "high")
    val mediumKey = key(themeIndex, "medium")
    val lowKey = key(themeIndex, "low")

    // Initialise prefs with defaults on first load for this theme
    if (!prefs.contains(highKey) || !prefs.contains(mediumKey) || !prefs.contains(lowKey)) {
        saveBatteryHighColor(prefs, themeIndex, base.defaultBatteryHigh)
        saveBatteryMediumColor(prefs, themeIndex, base.defaultBatteryMedium)
        saveBatteryLowColor(prefs, themeIndex, base.defaultBatteryLow)
    }

    val high = loadColor(prefs, highKey, base.defaultBatteryHigh)
    val medium = loadColor(prefs, mediumKey, base.defaultBatteryMedium)
    val low = loadColor(prefs, lowKey, base.defaultBatteryLow)

    return BatteryColors(high = high, medium = medium, low = low)
}

fun saveBatteryHighColor(
    prefs: SharedPreferences,
    themeIndex: Int,
    color: Color
) {
    saveColor(prefs, key(themeIndex, "high"), color)
}

fun saveBatteryMediumColor(
    prefs: SharedPreferences,
    themeIndex: Int,
    color: Color
) {
    saveColor(prefs, key(themeIndex, "medium"), color)
}

fun saveBatteryLowColor(
    prefs: SharedPreferences,
    themeIndex: Int,
    color: Color
) {
    saveColor(prefs, key(themeIndex, "low"), color)
}
