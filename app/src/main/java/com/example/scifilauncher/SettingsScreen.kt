package com.example.scifilauncher

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    themeColor: Color,
    batteryMode: BatterySaverMode,
    currentThemeIndex: Int,
    currentCycleMinutes: Int,
    onThemeChange: (Int) -> Unit,
    onCycleMinutesChange: (Int) -> Unit,
    onBackToDashboard: () -> Unit,
    onOpenBatteryAllowedApps: () -> Unit
) {
    val context = LocalContext.current

    var showThemePanel by remember { mutableStateOf(false) }

    val timePrefs = remember {
        context.getSharedPreferences("time_prefs", Context.MODE_PRIVATE)
    }
    val batteryPrefs = remember {
        context.getSharedPreferences("battery_prefs", Context.MODE_PRIVATE)
    }
    val fontPrefs = remember {
        context.getSharedPreferences("font_prefs", Context.MODE_PRIVATE)
    }
    val batteryThemePrefs = remember {
        context.getSharedPreferences("battery_theme_prefs", Context.MODE_PRIVATE)
    }
    val themePrefs = remember {
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
    }
    var keyboardStyle by remember { mutableStateOf(loadKeyboardStyle(themePrefs)) }

    var showTimeFormatPanel by remember { mutableStateOf(false) }
    var showBatterySaverPanel by remember { mutableStateOf(false) }
    var showFontSizePanel by remember { mutableStateOf(false) }
    var showLanguagePanel by remember { mutableStateOf(false) }

    var timeFormat by remember { mutableStateOf(loadTimeFormat(timePrefs)) }
    var batteryMode by remember { mutableStateOf(loadBatterySaverMode(batteryPrefs)) }
    var fontSizeOption by remember { mutableStateOf(loadFontSize(fontPrefs)) }
    var darkModeOption by remember { mutableStateOf(loadDarkMode(themePrefs)) }
    var languageOption by remember { mutableStateOf(loadLanguage(themePrefs)) }
    var showKeyboardPanel by remember { mutableStateOf(false) }
    val baseFontSize = 14.sp
    val headerFontSize = 18.sp
    val smallHintFontSize = 10.sp
    val textColor = if (darkModeOption == DarkModeOption.DARK) Color.White else Color.Black

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        MatrixBackground(
            themeColor = themeColor,
            isDark = (darkModeOption == DarkModeOption.DARK),
            batteryMode = batteryMode
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp)
        ) {
            // HEADER
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "< DASH",
                    color = themeColor,
                    fontSize = baseFontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { onBackToDashboard() }
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "SETTINGS",
                    color = themeColor,
                    fontSize = headerFontSize,
                    fontFamily = FontFamily.Monospace
                )
            }

            // SCROLLABLE CONTENT
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.Start
            ) {
                SectionTitle("APPEARANCE", themeColor, baseFontSize, textColor)


                SettingsRow(
                    label = "Theme (${CedalThemes[currentThemeIndex].name})",
                    fontSize = baseFontSize,
                    textColor = textColor,
                    onClick = { showThemePanel = true }
                )

                SettingsRow(
                    label = "Font size (${when (fontSizeOption) {
                        FontSizeOption.SMALL -> "Small"
                        FontSizeOption.NORMAL -> "Normal"
                        FontSizeOption.LARGE -> "Large"
                        FontSizeOption.HUGE -> "Huge"
                    }})",
                    fontSize = baseFontSize,
                    textColor = textColor,
                    onClick = { showFontSizePanel = true }
                )

                SettingsRow(
                    label = "Dark / Light mode (${if (darkModeOption == DarkModeOption.DARK) "Dark" else "Light"})",
                    fontSize = baseFontSize,
                    textColor = textColor,
                    onClick = {
                        darkModeOption = if (darkModeOption == DarkModeOption.DARK) {
                            DarkModeOption.LIGHT
                        } else {
                            DarkModeOption.DARK
                        }
                        saveDarkMode(themePrefs, darkModeOption)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = themeColor.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))

                SectionTitle("SYSTEM & BEHAVIOR", themeColor, baseFontSize, textColor)

                SettingsRow(
                    label = "Time format (${if (timeFormat == TimeFormatOption.FORMAT_24H) "24-hour" else "12-hour"})",
                    fontSize = baseFontSize,
                    textColor = textColor,
                    onClick = { showTimeFormatPanel = true }
                )

                SettingsRow(
                    label = "Battery saver mode (${when (batteryMode) {
                        BatterySaverMode.OFF -> "Off"
                        BatterySaverMode.BALANCED -> "Balanced"
                        BatterySaverMode.AGGRESSIVE -> "Aggressive"
                    }})",
                    fontSize = baseFontSize,
                    textColor = textColor,
                    onClick = { showBatterySaverPanel = true }
                )

                SettingsRow(
                    label = "Battery saver allowed apps",
                    fontSize = baseFontSize,
                    textColor = textColor,
                    onClick = onOpenBatteryAllowedApps
                )

                SettingsRow(
                    label = "Language (${when (languageOption) {
                        LanguageOption.ENGLISH -> "English"
                        LanguageOption.YORUBA -> "Yoruba"
                        LanguageOption.MANDARIN -> "Mandarin"
                        LanguageOption.KOREAN -> "Korean"
                        LanguageOption.FRENCH -> "French"
                        LanguageOption.SPANISH -> "Spanish"
                        LanguageOption.GERMAN -> "German"
                    }})",
                    fontSize = baseFontSize,
                    textColor = textColor,
                    onClick = { showLanguagePanel = true }
                )
                SettingsRow(
                    label = "Keyboard (${when (keyboardStyle) {
                        KeyboardStyle.NORMAL -> "Normal"
                        KeyboardStyle.XENOS -> "Xenos"
                        KeyboardStyle.CEDAL -> "Cedal"
                    }})",
                    fontSize = baseFontSize,
                    textColor = textColor,
                    onClick = { showKeyboardPanel = true }
                )
                SettingsRow("Weather", fontSize = baseFontSize, textColor = textColor)
                SettingsRow("Notification setting", fontSize = baseFontSize,textColor = textColor)

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = themeColor.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))

                SectionTitle("ABOUT & SUPPORT", themeColor, baseFontSize, textColor)

                SettingsRow("Rate us", fontSize = baseFontSize,textColor = textColor)
                SettingsRow("Feedback", fontSize = baseFontSize,textColor = textColor)
                SettingsRow("Privacy policy", fontSize = baseFontSize,textColor = textColor)
                SettingsRow("Make default launcher", fontSize = baseFontSize,textColor = textColor)
                SettingsRow("About", fontSize = baseFontSize,textColor = textColor)
                SettingsRow("More apps", fontSize = baseFontSize,textColor = textColor)

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // PANELS

        if (showThemePanel) {
            ThemePanel(
                themeColor = themeColor,
                currentThemeIndex = currentThemeIndex,
                currentCycleMinutes = currentCycleMinutes,
                batteryThemePrefs = batteryThemePrefs,
                onSelectTheme = { idx ->
                    onThemeChange(idx)
                    themePrefs.edit().putInt("theme_index", idx).apply()
                },
                        onSelectMinutes = { minutes -> onCycleMinutesChange(minutes) },
                onDismiss = { showThemePanel = false }
            )
        }

        if (showTimeFormatPanel) {
            TimeFormatPanel(
                themeColor = themeColor,
                current = timeFormat,
                titleFontSize = headerFontSize,
                rowFontSize = baseFontSize,
                hintFontSize = smallHintFontSize,
                onSelect = { chosen: TimeFormatOption ->
                    timeFormat = chosen
                    saveTimeFormat(timePrefs, chosen)
                    showTimeFormatPanel = false
                },
                onDismiss = { showTimeFormatPanel = false }
            )
        }

        if (showBatterySaverPanel) {
            BatterySaverPanel(
                themeColor = themeColor,
                current = batteryMode,
                titleFontSize = headerFontSize,
                rowFontSize = baseFontSize,
                hintFontSize = smallHintFontSize,
                onSelect = { chosen: BatterySaverMode ->
                    batteryMode = chosen
                    saveBatterySaverMode(batteryPrefs, chosen)
                    showBatterySaverPanel = false
                },
                onDismiss = { showBatterySaverPanel = false }
            )
        }

        if (showFontSizePanel) {
            FontSizePanel(
                themeColor = themeColor,
                current = fontSizeOption,
                titleFontSize = headerFontSize,
                rowFontSize = baseFontSize,
                hintFontSize = smallHintFontSize,
                onSelect = { chosen: FontSizeOption ->
                    fontSizeOption = chosen
                    saveFontSize(fontPrefs, chosen)
                    showFontSizePanel = false
                },
                onDismiss = { showFontSizePanel = false }
            )
        }
        if (showLanguagePanel) {
            LanguagePanel(
                themeColor = themeColor,
                current = languageOption,
                titleFontSize = headerFontSize,
                rowFontSize = baseFontSize,
                hintFontSize = smallHintFontSize,
                onSelect = { chosen ->
                    languageOption = chosen
                    saveLanguage(themePrefs, chosen)
                    showLanguagePanel = false
                },
                onDismiss = { showLanguagePanel = false }
            )
        }
        if (showKeyboardPanel) {
            KeyboardPanel(
                themeColor = themeColor,
                current = keyboardStyle,
                titleFontSize = headerFontSize,
                rowFontSize = baseFontSize,
                hintFontSize = smallHintFontSize,
                onSelect = { style ->
                    keyboardStyle = style
                    saveKeyboardStyle(themePrefs, style)
                    showKeyboardPanel = false
                },
                onDismiss = { showKeyboardPanel = false }
            )
        }
    }
}

// ---------- Dark mode option & prefs ----------

enum class DarkModeOption {
    LIGHT,
    DARK
}

fun loadDarkMode(prefs: SharedPreferences): DarkModeOption {
    val name = prefs.getString("dark_mode_option", DarkModeOption.DARK.name)
    return try {
        DarkModeOption.valueOf(name ?: DarkModeOption.DARK.name)
    } catch (_: IllegalArgumentException) {
        DarkModeOption.DARK
    }
}

fun saveDarkMode(prefs: SharedPreferences, option: DarkModeOption) {
    prefs.edit().putString("dark_mode_option", option.name).apply()
}

// ----------------- Small helpers -----------------

@Composable
private fun SectionTitle(
    text: String,
    themeColor: Color,
    fontSize: TextUnit,
    textColor: Color
) {
    val titleSize = (fontSize.value - 2).coerceAtLeast(8f).sp
    Text(
        text = text,
        color = textColor.copy(alpha = 0.9f),
        fontSize = titleSize,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SettingsRow(
    label: String,
    fontSize: TextUnit,
    textColor: Color,
    onClick: () -> Unit = {}
) {
    Text(
        text = label,
        color = textColor,
        fontSize = fontSize,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onClick() }
    )
}

@Composable
private fun LanguagePanel(
    themeColor: Color,
    current: LanguageOption,
    titleFontSize: TextUnit,
    rowFontSize: TextUnit,
    hintFontSize: TextUnit,
    onSelect: (LanguageOption) -> Unit,
    onDismiss: () -> Unit
) {
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
            ) {
                Text(
                    text = "LANGUAGE",
                    color = themeColor,
                    fontSize = titleFontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                LanguageRow("English", current == LanguageOption.ENGLISH, themeColor, rowFontSize) {
                    onSelect(LanguageOption.ENGLISH)
                }
                LanguageRow("Yoruba", current == LanguageOption.YORUBA, themeColor, rowFontSize) {
                    onSelect(LanguageOption.YORUBA)
                }
                LanguageRow("Mandarin", current == LanguageOption.MANDARIN, themeColor, rowFontSize) {
                    onSelect(LanguageOption.MANDARIN)
                }
                LanguageRow("Korean", current == LanguageOption.KOREAN, themeColor, rowFontSize) {
                    onSelect(LanguageOption.KOREAN)
                }
                LanguageRow("French", current == LanguageOption.FRENCH, themeColor, rowFontSize) {
                    onSelect(LanguageOption.FRENCH)
                }
                LanguageRow("Spanish", current == LanguageOption.SPANISH, themeColor, rowFontSize) {
                    onSelect(LanguageOption.SPANISH)
                }
                LanguageRow("German", current == LanguageOption.GERMAN, themeColor, rowFontSize) {
                    onSelect(LanguageOption.GERMAN)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap outside to cancel.",
                    color = Color.Gray,
                    fontSize = hintFontSize,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun LanguageRow(
    label: String,
    selected: Boolean,
    themeColor: Color,
    fontSize: TextUnit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = if (selected) "SELECTED" else "",
            color = if (selected) themeColor else Color.Transparent,
            fontSize = (fontSize.value - 4).coerceAtLeast(8f).sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun TimeFormatPanel(
    themeColor: Color,
    current: TimeFormatOption,
    titleFontSize: TextUnit,
    rowFontSize: TextUnit,
    hintFontSize: TextUnit,
    onSelect: (TimeFormatOption) -> Unit,
    onDismiss: () -> Unit
) {
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
            ) {
                Text(
                    text = "TIME FORMAT",
                    color = themeColor,
                    fontSize = titleFontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                TimeFormatOptionRow(
                    label = "12-hour (AM/PM)",
                    selected = current == TimeFormatOption.FORMAT_12H,
                    themeColor = themeColor,
                    fontSize = rowFontSize,
                    onClick = { onSelect(TimeFormatOption.FORMAT_12H) }
                )

                TimeFormatOptionRow(
                    label = "24-hour",
                    selected = current == TimeFormatOption.FORMAT_24H,
                    themeColor = themeColor,
                    fontSize = rowFontSize,
                    onClick = { onSelect(TimeFormatOption.FORMAT_24H) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Tap outside to cancel.",
                    color = Color.Gray,
                    fontSize = hintFontSize,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun TimeFormatOptionRow(
    label: String,
    selected: Boolean,
    themeColor: Color,
    fontSize: TextUnit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = if (selected) "SELECTED" else "",
            color = if (selected) themeColor else Color.Transparent,
            fontSize = (fontSize.value - 4).coerceAtLeast(8f).sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun BatterySaverPanel(
    themeColor: Color,
    current: BatterySaverMode,
    titleFontSize: TextUnit,
    rowFontSize: TextUnit,
    hintFontSize: TextUnit,
    onSelect: (BatterySaverMode) -> Unit,
    onDismiss: () -> Unit
) {
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
            ) {
                Text(
                    text = "BATTERY SAVER MODE",
                    color = themeColor,
                    fontSize = titleFontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                BatteryOptionRow(
                    label = "Off (full experience)",
                    selected = current == BatterySaverMode.OFF,
                    themeColor = themeColor,
                    fontSize = rowFontSize,
                    onClick = { onSelect(BatterySaverMode.OFF) }
                )

                BatteryOptionRow(
                    label = "Balanced (limit some effects)",
                    selected = current == BatterySaverMode.BALANCED,
                    themeColor = themeColor,
                    fontSize = rowFontSize,
                    onClick = { onSelect(BatterySaverMode.BALANCED) }
                )

                BatteryOptionRow(
                    label = "Aggressive (minimum animations)",
                    selected = current == BatterySaverMode.AGGRESSIVE,
                    themeColor = themeColor,
                    fontSize = rowFontSize,
                    onClick = { onSelect(BatterySaverMode.AGGRESSIVE) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Tap outside to cancel.",
                    color = Color.Gray,
                    fontSize = hintFontSize,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun BatteryOptionRow(
    label: String,
    selected: Boolean,
    themeColor: Color,
    fontSize: TextUnit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = if (selected) "SELECTED" else "",
            color = if (selected) themeColor else Color.Transparent,
            fontSize = (fontSize.value - 4).coerceAtLeast(8f).sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun FontSizePanel(
    themeColor: Color,
    current: FontSizeOption,
    titleFontSize: TextUnit,
    rowFontSize: TextUnit,
    hintFontSize: TextUnit,
    onSelect: (FontSizeOption) -> Unit,
    onDismiss: () -> Unit
) {
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
            ) {
                Text(
                    text = "FONT SIZE",
                    color = themeColor,
                    fontSize = titleFontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                FontSizeRow("Small", current == FontSizeOption.SMALL, themeColor, rowFontSize) {
                    onSelect(FontSizeOption.SMALL)
                }
                FontSizeRow("Normal", current == FontSizeOption.NORMAL, themeColor, rowFontSize) {
                    onSelect(FontSizeOption.NORMAL)
                }
                FontSizeRow("Large", current == FontSizeOption.LARGE, themeColor, rowFontSize) {
                    onSelect(FontSizeOption.LARGE)
                }
                FontSizeRow("Huge", current == FontSizeOption.HUGE, themeColor, rowFontSize) {
                    onSelect(FontSizeOption.HUGE)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap outside to cancel.",
                    color = Color.Gray,
                    fontSize = hintFontSize,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}


@Composable
private fun FontSizeRow(
    label: String,
    selected: Boolean,
    themeColor: Color,
    fontSize: TextUnit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = if (selected) "SELECTED" else "",
            color = if (selected) themeColor else Color.Transparent,
            fontSize = (fontSize.value - 4).coerceAtLeast(8f).sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
@Composable
private fun KeyboardPanel(
    themeColor: Color,
    current: KeyboardStyle,
    titleFontSize: TextUnit,
    rowFontSize: TextUnit,
    hintFontSize: TextUnit,
    onSelect: (KeyboardStyle) -> Unit,
    onDismiss: () -> Unit
) {
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
            ) {
                Text(
                    text = "KEYBOARD STYLE",
                    color = themeColor,
                    fontSize = titleFontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                KeyboardRow("Normal", current == KeyboardStyle.NORMAL, themeColor, rowFontSize) {
                    onSelect(KeyboardStyle.NORMAL)
                }
                KeyboardRow("Xenos (matrix)", current == KeyboardStyle.XENOS, themeColor, rowFontSize) {
                    onSelect(KeyboardStyle.XENOS)
                }
                KeyboardRow("Cedal", current == KeyboardStyle.CEDAL, themeColor, rowFontSize) {
                    onSelect(KeyboardStyle.CEDAL)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap outside to cancel.",
                    color = Color.Gray,
                    fontSize = hintFontSize,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun KeyboardRow(
    label: String,
    selected: Boolean,
    themeColor: Color,
    fontSize: TextUnit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = if (selected) "SELECTED" else "",
            color = if (selected) themeColor else Color.Transparent,
            fontSize = (fontSize.value - 4).coerceAtLeast(8f).sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

