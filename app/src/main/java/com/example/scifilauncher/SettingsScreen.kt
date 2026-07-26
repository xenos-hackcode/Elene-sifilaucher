package com.example.scifilauncher

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    themeColor: Color,
    batteryMode: BatterySaverMode,
    currentThemeIndex: Int,
    onThemeChange: (Int) -> Unit,
    onBackToDashboard: () -> Unit,
    onFeedback: () -> Unit,
    onMakeDefaultLauncher: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenMoreApps: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onDarkModeChange: (DarkModeOption) -> Unit = {}
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
    val themePrefs = remember {
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
    }
    var keyboardStyle by remember { mutableStateOf(loadKeyboardStyle(themePrefs)) }
    var eleneVoiceOn by remember { mutableStateOf(themePrefs.getBoolean("elene_voice_on", true)) }
    var continuousListeningOn by remember { mutableStateOf(themePrefs.getBoolean("elene_continuous_listening", true)) }
    var showContinuousListeningInfo by remember { mutableStateOf(false) }


    var showTimeFormatPanel by remember { mutableStateOf(false) }
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
        PanelBackdrop(isDark = darkModeOption == DarkModeOption.DARK)
        androidx.compose.runtime.CompositionLocalProvider(LocalPanelIsDark provides (darkModeOption == DarkModeOption.DARK)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            // The notification/quick-settings pull tabs float persistently at the very top
            // corners on every screen (see NotificationBarTab/QuickSettingsTab in
            // MainActivity) - extra top clearance here keeps this header from visually
            // crowding into them.
            Spacer(modifier = Modifier.height(28.dp))

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
                PanelSection(title = "APPEARANCE", themeColor = themeColor) {
                    PanelRow(
                        label = "Theme",
                        themeColor = themeColor,
                        value = CedalThemes[currentThemeIndex].name,
                        onClick = { showThemePanel = true }
                    )
                    PanelRow(
                        label = "Font size",
                        themeColor = themeColor,
                        value = when (fontSizeOption) {
                            FontSizeOption.SMALL -> "Small"
                            FontSizeOption.NORMAL -> "Normal"
                            FontSizeOption.LARGE -> "Large"
                            FontSizeOption.HUGE -> "Huge"
                        },
                        showDivider = false,
                        onClick = { showFontSizePanel = true }
                    )
                }

                PanelSection(title = "SYSTEM & BEHAVIOR", themeColor = themeColor) {
                    PanelRow(
                        label = "Time format",
                        themeColor = themeColor,
                        value = if (timeFormat == TimeFormatOption.FORMAT_24H) "24-hour" else "12-hour",
                        onClick = { showTimeFormatPanel = true }
                    )
                    PanelToggleRow(
                        label = "Battery saver",
                        themeColor = themeColor,
                        checked = batteryMode != BatterySaverMode.OFF,
                        onToggle = { on ->
                            batteryMode = if (on) BatterySaverMode.AGGRESSIVE else BatterySaverMode.OFF
                            saveBatterySaverMode(batteryPrefs, batteryMode)
                        }
                    )
                    PanelRow(
                        label = "Language",
                        themeColor = themeColor,
                        value = when (languageOption) {
                            LanguageOption.ENGLISH -> "English"
                            LanguageOption.YORUBA -> "Yoruba"
                            LanguageOption.MANDARIN -> "Mandarin"
                            LanguageOption.KOREAN -> "Korean"
                            LanguageOption.FRENCH -> "French"
                            LanguageOption.SPANISH -> "Spanish"
                            LanguageOption.GERMAN -> "German"
                        },
                        onClick = { showLanguagePanel = true }
                    )
                    PanelRow(
                        label = "Keyboard",
                        themeColor = themeColor,
                        value = when (keyboardStyle) {
                            KeyboardStyle.NORMAL -> "Normal"
                            KeyboardStyle.XENOS -> "Xenos"
                            KeyboardStyle.CEDAL -> "Cedal"
                        },
                        onClick = { showKeyboardPanel = true }
                    )
                    PanelToggleRow(
                        label = "Elene voice",
                        themeColor = themeColor,
                        checked = eleneVoiceOn,
                        onToggle = {
                            eleneVoiceOn = it
                            themePrefs.edit().putBoolean("elene_voice_on", eleneVoiceOn).apply()
                        }
                    )
                    PanelToggleRow(
                        label = "Elene keeps listening",
                        themeColor = themeColor,
                        checked = continuousListeningOn,
                        showDivider = false,
                        onToggle = {
                            continuousListeningOn = it
                            themePrefs.edit().putBoolean("elene_continuous_listening", continuousListeningOn).apply()
                        },
                        onInfoClick = { showContinuousListeningInfo = true }
                    )
                }

                PanelSection(title = "CAPABILITIES", themeColor = themeColor) {
                    PanelRow(
                        label = "What this app can do",
                        themeColor = themeColor,
                        showDivider = false,
                        onClick = onOpenCapabilities
                    )
                }

                PanelSection(title = "ABOUT & SUPPORT", themeColor = themeColor) {
                    PanelRow(label = "Feedback", themeColor = themeColor, onClick = onFeedback)
                    PanelRow(label = "Privacy policy", themeColor = themeColor, onClick = onPrivacyPolicy)
                    PanelRow(label = "Make default launcher", themeColor = themeColor, onClick = onMakeDefaultLauncher)
                    PanelRow(label = "About", themeColor = themeColor, onClick = onOpenAbout)
                    PanelRow(label = "More apps", themeColor = themeColor, showDivider = false, onClick = onOpenMoreApps)
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // PANELS

        if (showThemePanel) {
            ThemePanel(
                themeColor = themeColor,
                currentThemeIndex = currentThemeIndex,
                currentDarkMode = darkModeOption,
                onSelectTheme = { idx ->
                    onThemeChange(idx)
                    themePrefs.edit().putInt("theme_index", idx).apply()
                },
                onSelectDarkMode = { mode ->
                    darkModeOption = mode
                    saveDarkMode(themePrefs, mode)
                    onDarkModeChange(mode)
                },
                onDismiss = { showThemePanel = false }
            )
        }


        if (showContinuousListeningInfo) {
            AlertDialog(
                onDismissRequest = { showContinuousListeningInfo = false },
                confirmButton = {
                    TextButton(onClick = { showContinuousListeningInfo = false }) {
                        Text("CLOSE", color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                },
                title = { Text("Elene keeps listening", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "On: after replying, Elene keeps the mic open and waits for your next " +
                            "thing - she only stops when you say \"stop listening\" (or a clear " +
                            "equivalent like \"go away\").\n\n" +
                            "Off: she stops listening automatically after every single reply, " +
                            "the same as before - you'll need to tap the bubble again each time.",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
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
    val context = LocalContext.current

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
                    // Normal = system default keyboard, just close panel
                    onDismiss()
                }

                KeyboardRow("Xenos (matrix)", current == KeyboardStyle.XENOS, themeColor, rowFontSize) {
                    onSelect(KeyboardStyle.XENOS)
                    // Ask system to show picker so user selects XenosKeyboardService
                    showInputMethodPicker(context)
                    onDismiss()
                }

                KeyboardRow("Cedal", current == KeyboardStyle.CEDAL, themeColor, rowFontSize) {
                    onSelect(KeyboardStyle.CEDAL)
                    // Ask system to show picker so user selects CedalKeyboardService
                    showInputMethodPicker(context)
                    onDismiss()
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "After choosing, select the keyboard in the system picker.",
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

