package com.example.scifilauncher

import androidx.compose.ui.graphics.Color

data class CedalTheme(
    val name: String,
    val primary: Color,
    val secondary: Color
)

val CedalThemes = listOf(
    CedalTheme(name = "Matrix", primary = MatrixGreen, secondary = Color(0xFF00B0FF)),
    CedalTheme(name = "Terminal", primary = Color(0xFF33FF33), secondary = Color(0xFFCCCCCC)),
    CedalTheme(name = "Volcano", primary = Color(0xFFFF3B3B), secondary = Color(0xFFFFA000)),
    CedalTheme(name = "SciFi", primary = Color(0xFF00E5FF), secondary = Color(0xFFBB86FC)),
    CedalTheme(name = "Hacker", primary = Color(0xFF00FF66), secondary = Color(0xFF0B0F18)),
    CedalTheme(name = "Coder", primary = Color(0xFFFFC107), secondary = Color(0xFF2979FF)),
    CedalTheme(name = "Ocean", primary = Color(0xFF00B0FF), secondary = Color(0xFF00FFC8)),
    CedalTheme(name = "Violet", primary = Color(0xFFBB86FC), secondary = Color(0xFF03DAC5)),
    CedalTheme(name = "Neon", primary = Color(0xFFFF2E88), secondary = Color(0xFF00F0FF)),
    CedalTheme(name = "Ghost", primary = Color(0xFFE0E0E0), secondary = Color(0xFF616161))
)

/** Battery ring/indicator color - a fixed rule, not per-theme or customizable: 50-100% is
 * always green, 20-49% is always yellow, 0-19% is always red. */
fun batteryLevelColor(batteryLevel: Int): Color = when {
    batteryLevel >= 50 -> Color(0xFF00E676)
    batteryLevel >= 20 -> Color(0xFFFFC107)
    else -> Color(0xFFFF3B30)
}
