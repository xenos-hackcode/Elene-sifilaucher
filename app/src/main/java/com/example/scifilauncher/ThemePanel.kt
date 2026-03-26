package com.example.scifilauncher

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ThemePanel(
    themeColor: Color,
    currentThemeIndex: Int,
    currentCycleMinutes: Int,
    batteryThemePrefs: SharedPreferences,
    onSelectTheme: (Int) -> Unit,
    onSelectMinutes: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // Start from current theme + its saved battery colors
    var localThemeIndex by remember { mutableStateOf(currentThemeIndex) }
    var localBatteryColors by remember(currentThemeIndex) {
        mutableStateOf(loadBatteryColorsForTheme(batteryThemePrefs, currentThemeIndex))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = Color(0xFF05070B),
            tonalElevation = 8.dp,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // HEADER + BACK / SAVE
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "< BACK",
                        color = themeColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { onDismiss() }
                    )
                    Text(
                        text = "THEME",
                        color = themeColor,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "SAVE",
                        color = themeColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable {
                            // save selected battery colors
                            saveBatteryHighColor(
                                batteryThemePrefs,
                                localThemeIndex,
                                localBatteryColors.high
                            )
                            saveBatteryMediumColor(
                                batteryThemePrefs,
                                localThemeIndex,
                                localBatteryColors.medium
                            )
                            saveBatteryLowColor(
                                batteryThemePrefs,
                                localThemeIndex,
                                localBatteryColors.low
                            )

                            onSelectTheme(localThemeIndex)
                            onDismiss()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // THEME PRESETS
                CedalThemes.forEachIndexed { index, t ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                localThemeIndex = index
                                localBatteryColors =
                                    loadBatteryColorsForTheme(batteryThemePrefs, index)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = t.name,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(t.primary, RoundedCornerShape(3.dp))
                                )
                                Text(
                                    text = "Primary",
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(t.secondary, RoundedCornerShape(3.dp))
                                )
                                Text(
                                    text = "Secondary",
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        Text(
                            text = if (index == localThemeIndex) "SELECTED" else "",
                            color = if (index == localThemeIndex) themeColor else Color.Transparent,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "BATTERY COLORS",
                    color = themeColor,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Text(
                    text = "Note: Battery colors are per-theme.\n" +
                            "Changing theme may reset these colors.",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                BatteryColorRow(
                    label = "High (50%–100%)",
                    current = localBatteryColors.high,
                    onColorSelected = { c: Color ->
                        localBatteryColors = localBatteryColors.copy(high = c)
                    }
                )
                BatteryColorRow(
                    label = "Medium (20%–49%)",
                    current = localBatteryColors.medium,
                    onColorSelected = { c: Color ->
                        localBatteryColors = localBatteryColors.copy(medium = c)
                    }
                )
                BatteryColorRow(
                    label = "Low (0%–19%)",
                    current = localBatteryColors.low,
                    onColorSelected = { c: Color ->
                        localBatteryColors = localBatteryColors.copy(low = c)
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Cycle primary / secondary every:",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                listOf(5, 10, 15).forEach { minutes ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectMinutes(minutes) }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$minutes minutes",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (minutes == currentCycleMinutes) "SELECTED" else "",
                            color = if (minutes == currentCycleMinutes) themeColor else Color.Transparent,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Changes apply only when you press SAVE.",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun BatteryColorRow(
    label: String,
    current: Color,
    onColorSelected: (Color) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(current, RoundedCornerShape(4.dp))
            )
            BatteryColorOptions.forEach { c: Color ->
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(c, RoundedCornerShape(4.dp))
                        .clickable { onColorSelected(c) }
                )
            }
        }
    }
}
