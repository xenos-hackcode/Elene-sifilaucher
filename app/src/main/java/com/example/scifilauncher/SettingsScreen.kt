package com.example.scifilauncher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    themeColor: Color,
    batteryMode: BatterySaverMode,
    currentThemeIndex: Int,
    onThemeChange: (Int) -> Unit,
    onBackToDashboard: () -> Unit,
    onMakeDefaultLauncher: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenMoreApps: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onOpenMemory: () -> Unit,
    onRequestBiometricForWifiPassword: (onSuccess: () -> Unit) -> Unit,
    onDarkModeChange: (DarkModeOption) -> Unit = {},
    currentIconPackPkg: String? = null,
    onSelectIconPack: (String?) -> Unit = {},
    onLanguageChange: (LanguageOption) -> Unit = {}
) {
    val context = LocalContext.current

    var showThemePanel by remember { mutableStateOf(false) }
    var showIconPackPanel by remember { mutableStateOf(false) }
    var showFeedbackDialog by remember { mutableStateOf(false) }
    var showBackendUrlDialog by remember { mutableStateOf(false) }
    var backendUrlConfigured by remember { mutableStateOf(EleneApiClient.currentBaseUrl().isNotBlank()) }

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
    var alwaysListeningOn by remember { mutableStateOf(themePrefs.getBoolean("elene_always_listening", false)) }
    var showAlwaysListeningInfo by remember { mutableStateOf(false) }
    val heyEleneScope = rememberCoroutineScope()
    var heyEleneEnrolled by remember { mutableStateOf(HeyEleneWakeWord.isEnrolled(context)) }
    var heyEleneBusy by remember { mutableStateOf(false) }
    var heyEleneStatus by remember { mutableStateOf<String?>(null) }
    var showHeyEleneRecordInfo by remember { mutableStateOf(false) }
    var heyEleneShowRecordingDialog by remember { mutableStateOf(false) }
    var heyEleneTakesDone by remember { mutableIntStateOf(0) }
    var heyEleneDialogProcessing by remember { mutableStateOf(false) }
    var heyEleneCancelled by remember { mutableStateOf(false) }
    val heyEleneDesiredTakes = 5


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
        androidx.compose.runtime.CompositionLocalProvider(
            LocalPanelIsDark provides (darkModeOption == DarkModeOption.DARK),
            LocalLanguage provides languageOption
        ) {
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
                    text = tr("back_dash"),
                    color = themeColor,
                    fontSize = baseFontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { onBackToDashboard() }
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = tr("settings_title"),
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
                PanelSection(title = tr("section_appearance"), themeColor = themeColor) {
                    PanelRow(
                        label = tr("row_theme"),
                        themeColor = themeColor,
                        value = CedalThemes[currentThemeIndex].name,
                        onClick = { showThemePanel = true }
                    )
                    PanelRow(
                        label = tr("row_font_size"),
                        themeColor = themeColor,
                        value = when (fontSizeOption) {
                            FontSizeOption.SMALL -> tr("font_small")
                            FontSizeOption.NORMAL -> tr("font_normal")
                            FontSizeOption.LARGE -> tr("font_large")
                            FontSizeOption.HUGE -> tr("font_huge")
                        },
                        onClick = { showFontSizePanel = true }
                    )
                    PanelRow(
                        label = tr("row_icon_pack"),
                        themeColor = themeColor,
                        value = currentIconPackPkg?.let { pkg ->
                            runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }
                                .getOrDefault(tr("font_normal"))
                        } ?: tr("font_normal"),
                        showDivider = false,
                        onClick = { showIconPackPanel = true }
                    )
                }

                PanelSection(title = tr("section_network"), themeColor = themeColor) {
                    var wifiStatus by remember { mutableStateOf(currentWifiStatus(context)) }
                    var revealedPassword by remember { mutableStateOf<String?>(null) }
                    var revealFailReason by remember { mutableStateOf<String?>(null) }
                    var showWifiInfo by remember { mutableStateOf(false) }

                    PanelRow(
                        label = tr("row_wifi"),
                        themeColor = themeColor,
                        value = wifiStatus.ssid ?: tr("wifi_not_connected"),
                        onInfoClick = { showWifiInfo = true },
                        onClick = { wifiStatus = currentWifiStatus(context) }
                    )
                    if (wifiStatus.connected) {
                        PanelRow(
                            label = tr("row_signal_speed"),
                            themeColor = themeColor,
                            value = listOfNotNull(
                                wifiStatus.rssiDbm?.let { "${it}dBm" },
                                wifiStatus.linkSpeedMbps?.let { "${it}Mbps" }
                            ).joinToString(" · ").ifBlank { "-" },
                            onClick = {}
                        )
                        PanelRow(
                            label = tr("row_ip_address"),
                            themeColor = themeColor,
                            value = wifiStatus.ipAddress ?: "-",
                            onClick = {}
                        )
                        PanelRow(
                            label = tr("row_reveal_password"),
                            themeColor = themeColor,
                            value = when {
                                revealedPassword != null -> revealedPassword!!
                                revealFailReason != null -> tr("value_unavailable")
                                else -> tr("value_tap_to_reveal")
                            },
                            showDivider = false,
                            onClick = {
                                if (revealedPassword != null || revealFailReason != null) {
                                    // Already shown - tapping again hides it rather than
                                    // leaving a saved password sitting on screen indefinitely.
                                    revealedPassword = null
                                    revealFailReason = null
                                    return@PanelRow
                                }
                                onRequestBiometricForWifiPassword {
                                    when (val result = currentWifiPassword(context)) {
                                        is WifiPasswordResult.Found -> revealedPassword = result.password
                                        is WifiPasswordResult.Unavailable -> revealFailReason = result.reason
                                    }
                                }
                            }
                        )
                        revealFailReason?.let {
                            Text(
                                text = it,
                                color = Color.Gray,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                            )
                        }
                    }
                    if (showWifiInfo) {
                        AlertDialog(
                            onDismissRequest = { showWifiInfo = false },
                            confirmButton = {
                                TextButton(onClick = { showWifiInfo = false }) {
                                    Text(tr("action_close"), color = themeColor, fontFamily = FontFamily.Monospace)
                                }
                            },
                            title = { Text(tr("wifi_dialog_title"), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                            text = {
                                Text(
                                    tr("wifi_dialog_body"),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                )
                            }
                        )
                    }
                }

                PanelSection(title = tr("section_system_behavior"), themeColor = themeColor) {
                    PanelRow(
                        label = tr("row_time_format"),
                        themeColor = themeColor,
                        value = if (timeFormat == TimeFormatOption.FORMAT_24H) tr("time_24h") else tr("time_12h"),
                        onClick = { showTimeFormatPanel = true }
                    )
                    PanelToggleRow(
                        label = tr("row_battery_saver"),
                        themeColor = themeColor,
                        checked = batteryMode != BatterySaverMode.OFF,
                        onToggle = { on ->
                            batteryMode = if (on) BatterySaverMode.AGGRESSIVE else BatterySaverMode.OFF
                            saveBatterySaverMode(batteryPrefs, batteryMode)
                        }
                    )
                    PanelRow(
                        label = tr("row_language"),
                        themeColor = themeColor,
                        value = when (languageOption) {
                            LanguageOption.ENGLISH -> tr("lang_english")
                            LanguageOption.YORUBA -> tr("lang_yoruba")
                            LanguageOption.MANDARIN -> tr("lang_mandarin")
                            LanguageOption.KOREAN -> tr("lang_korean")
                            LanguageOption.FRENCH -> tr("lang_french")
                            LanguageOption.SPANISH -> tr("lang_spanish")
                            LanguageOption.GERMAN -> tr("lang_german")
                        },
                        onClick = { showLanguagePanel = true }
                    )
                    PanelRow(
                        label = tr("row_keyboard"),
                        themeColor = themeColor,
                        value = when (keyboardStyle) {
                            KeyboardStyle.NORMAL -> tr("keyboard_normal")
                            KeyboardStyle.XENOS -> tr("keyboard_xenos")
                            KeyboardStyle.CEDAL -> tr("keyboard_cedal")
                        },
                        onClick = { showKeyboardPanel = true }
                    )
                    PanelToggleRow(
                        label = tr("row_xenos_voice"),
                        themeColor = themeColor,
                        checked = eleneVoiceOn,
                        onToggle = {
                            eleneVoiceOn = it
                            themePrefs.edit().putBoolean("elene_voice_on", eleneVoiceOn).apply()
                        }
                    )
                    PanelToggleRow(
                        label = tr("row_xenos_keeps_listening"),
                        themeColor = themeColor,
                        checked = continuousListeningOn,
                        onToggle = {
                            continuousListeningOn = it
                            themePrefs.edit().putBoolean("elene_continuous_listening", continuousListeningOn).apply()
                        },
                        onInfoClick = { showContinuousListeningInfo = true }
                    )
                    PanelToggleRow(
                        label = tr("row_always_listening"),
                        themeColor = themeColor,
                        checked = alwaysListeningOn,
                        onToggle = {
                            alwaysListeningOn = it
                            themePrefs.edit().putBoolean("elene_always_listening", alwaysListeningOn).apply()
                        },
                        onInfoClick = { showAlwaysListeningInfo = true }
                    )
                    PanelRow(
                        label = if (heyEleneEnrolled) tr("row_rerecord_hey_xenos") else tr("row_record_hey_xenos"),
                        themeColor = themeColor,
                        value = heyEleneStatus ?: if (heyEleneEnrolled) {
                            tr("hey_xenos_enrolled", HeyEleneWakeWord.sampleCount(context).toString())
                        } else {
                            tr("hey_xenos_not_recorded")
                        },
                        showDivider = false,
                        onInfoClick = { showHeyEleneRecordInfo = true },
                        onClick = {
                            if (heyEleneBusy) return@PanelRow
                            heyEleneBusy = true
                            heyEleneStatus = null
                            heyEleneCancelled = false
                            heyEleneTakesDone = 0
                            heyEleneShowRecordingDialog = true
                            heyEleneScope.launch {
                                val desiredTakes = 5
                                val maxAttempts = 8
                                val samples = mutableListOf<FloatArray>()
                                var attempt = 0
                                while (samples.size < desiredTakes && attempt < maxAttempts && !heyEleneCancelled) {
                                    attempt++
                                    val sample = recordVoiceSample(context, WAKE_WORD_CAPTURE_SAMPLES)
                                    if (sample != null) {
                                        samples.add(sample)
                                        heyEleneTakesDone = samples.size
                                    }
                                }
                                if (heyEleneCancelled) {
                                    heyEleneShowRecordingDialog = false
                                    heyEleneBusy = false
                                    return@launch
                                }
                                if (samples.size < 3) {
                                    heyEleneShowRecordingDialog = false
                                    heyEleneStatus = uiString("hey_xenos_couldnt_record", languageOption)
                                    heyEleneBusy = false
                                    return@launch
                                }
                                heyEleneDialogProcessing = true
                                val ok = HeyEleneWakeWord.enroll(context, samples)
                                heyEleneEnrolled = HeyEleneWakeWord.isEnrolled(context)
                                heyEleneShowRecordingDialog = false
                                heyEleneDialogProcessing = false
                                heyEleneStatus = if (ok) {
                                    uiString("hey_xenos_recorded_ok", languageOption, samples.size.toString())
                                } else {
                                    uiString("hey_xenos_recording_failed", languageOption)
                                }
                                heyEleneBusy = false
                            }
                        }
                    )
                }

                PanelSection(title = tr("section_capabilities"), themeColor = themeColor) {
                    PanelRow(
                        label = tr("row_what_app_can_do"),
                        themeColor = themeColor,
                        showDivider = false,
                        onClick = onOpenCapabilities
                    )
                }

                PanelSection(title = tr("section_memory"), themeColor = themeColor) {
                    PanelRow(
                        label = tr("row_what_xenos_remembers"),
                        themeColor = themeColor,
                        showDivider = false,
                        onClick = onOpenMemory
                    )
                }

                PanelSection(title = tr("section_elene_backend"), themeColor = themeColor) {
                    PanelRow(
                        label = tr("row_backend_url"),
                        themeColor = themeColor,
                        value = if (backendUrlConfigured) tr("backend_url_configured") else tr("backend_url_not_configured"),
                        showDivider = false,
                        onClick = { showBackendUrlDialog = true }
                    )
                }

                PanelSection(title = tr("section_about_support"), themeColor = themeColor) {
                    PanelRow(label = tr("row_feedback"), themeColor = themeColor, onClick = { showFeedbackDialog = true })
                    PanelRow(label = tr("row_privacy_policy"), themeColor = themeColor, onClick = onPrivacyPolicy)
                    PanelRow(label = tr("row_make_default_launcher"), themeColor = themeColor, onClick = onMakeDefaultLauncher)
                    PanelRow(label = tr("row_about"), themeColor = themeColor, onClick = onOpenAbout)
                    PanelRow(label = tr("row_more_apps"), themeColor = themeColor, showDivider = false, onClick = onOpenMoreApps)
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

        if (showIconPackPanel) {
            IconPackPanel(
                themeColor = themeColor,
                currentPackPkg = currentIconPackPkg,
                onSelectPack = onSelectIconPack,
                onDismiss = { showIconPackPanel = false }
            )
        }

        if (showBackendUrlDialog) {
            BackendUrlDialog(
                themeColor = themeColor,
                onDismiss = { showBackendUrlDialog = false },
                onSaved = { backendUrlConfigured = EleneApiClient.currentBaseUrl().isNotBlank() }
            )
        }

        if (showFeedbackDialog) {
            FeedbackDialog(
                themeColor = themeColor,
                onDismiss = { showFeedbackDialog = false }
            )
        }


        if (showContinuousListeningInfo) {
            AlertDialog(
                onDismissRequest = { showContinuousListeningInfo = false },
                confirmButton = {
                    TextButton(onClick = { showContinuousListeningInfo = false }) {
                        Text(tr("action_close"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                },
                title = { Text(tr("dialog_keeps_listening_title"), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        tr("dialog_keeps_listening_body"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
            )
        }

        if (showAlwaysListeningInfo) {
            AlertDialog(
                onDismissRequest = { showAlwaysListeningInfo = false },
                confirmButton = {
                    TextButton(onClick = { showAlwaysListeningInfo = false }) {
                        Text(tr("action_close"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                },
                title = { Text(tr("dialog_always_listening_title"), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        tr("dialog_always_listening_body"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
            )
        }

        if (showHeyEleneRecordInfo) {
            AlertDialog(
                onDismissRequest = { showHeyEleneRecordInfo = false },
                confirmButton = {
                    TextButton(onClick = { showHeyEleneRecordInfo = false }) {
                        Text(tr("action_close"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                },
                title = { Text(tr("dialog_record_hey_xenos_title"), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        tr("dialog_record_hey_xenos_body"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
            )
        }

        if (heyEleneShowRecordingDialog) {
            AlertDialog(
                onDismissRequest = { /* modal while recording - use Cancel below */ },
                dismissButton = {
                    if (!heyEleneDialogProcessing) {
                        TextButton(onClick = { heyEleneCancelled = true }) {
                            Text(tr("action_cancel"), color = Color.Gray, fontFamily = FontFamily.Monospace)
                        }
                    }
                },
                confirmButton = {},
                title = {
                    Text(
                        tr("hey_xenos_dialog_title"),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = themeColor
                    )
                },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (heyEleneDialogProcessing) tr("hey_xenos_processing") else tr("hey_xenos_say_it_now"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            if (heyEleneDialogProcessing) "" else tr(
                                "hey_xenos_take_of",
                                (heyEleneTakesDone + 1).coerceAtMost(heyEleneDesiredTakes).toString(),
                                heyEleneDesiredTakes.toString()
                            ),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                    }
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
                    onLanguageChange(chosen)
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
                    text = tr("panel_language_title"),
                    color = themeColor,
                    fontSize = titleFontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                LanguageRow(tr("lang_english"), current == LanguageOption.ENGLISH, themeColor, rowFontSize) {
                    onSelect(LanguageOption.ENGLISH)
                }
                LanguageRow(tr("lang_yoruba"), current == LanguageOption.YORUBA, themeColor, rowFontSize) {
                    onSelect(LanguageOption.YORUBA)
                }
                LanguageRow(tr("lang_mandarin"), current == LanguageOption.MANDARIN, themeColor, rowFontSize) {
                    onSelect(LanguageOption.MANDARIN)
                }
                LanguageRow(tr("lang_korean"), current == LanguageOption.KOREAN, themeColor, rowFontSize) {
                    onSelect(LanguageOption.KOREAN)
                }
                LanguageRow(tr("lang_french"), current == LanguageOption.FRENCH, themeColor, rowFontSize) {
                    onSelect(LanguageOption.FRENCH)
                }
                LanguageRow(tr("lang_spanish"), current == LanguageOption.SPANISH, themeColor, rowFontSize) {
                    onSelect(LanguageOption.SPANISH)
                }
                LanguageRow(tr("lang_german"), current == LanguageOption.GERMAN, themeColor, rowFontSize) {
                    onSelect(LanguageOption.GERMAN)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = tr("tap_outside_to_cancel"),
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
            text = if (selected) tr("value_selected") else "",
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
                    text = tr("panel_time_format_title"),
                    color = themeColor,
                    fontSize = titleFontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                TimeFormatOptionRow(
                    label = tr("time_12h_ampm"),
                    selected = current == TimeFormatOption.FORMAT_12H,
                    themeColor = themeColor,
                    fontSize = rowFontSize,
                    onClick = { onSelect(TimeFormatOption.FORMAT_12H) }
                )

                TimeFormatOptionRow(
                    label = tr("time_24h"),
                    selected = current == TimeFormatOption.FORMAT_24H,
                    themeColor = themeColor,
                    fontSize = rowFontSize,
                    onClick = { onSelect(TimeFormatOption.FORMAT_24H) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = tr("tap_outside_to_cancel"),
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
            text = if (selected) tr("value_selected") else "",
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
                    text = tr("panel_font_size_title"),
                    color = themeColor,
                    fontSize = titleFontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                FontSizeRow(tr("font_small"), current == FontSizeOption.SMALL, themeColor, rowFontSize) {
                    onSelect(FontSizeOption.SMALL)
                }
                FontSizeRow(tr("font_normal"), current == FontSizeOption.NORMAL, themeColor, rowFontSize) {
                    onSelect(FontSizeOption.NORMAL)
                }
                FontSizeRow(tr("font_large"), current == FontSizeOption.LARGE, themeColor, rowFontSize) {
                    onSelect(FontSizeOption.LARGE)
                }
                FontSizeRow(tr("font_huge"), current == FontSizeOption.HUGE, themeColor, rowFontSize) {
                    onSelect(FontSizeOption.HUGE)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = tr("tap_outside_to_cancel"),
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
            text = if (selected) tr("value_selected") else "",
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
                    text = tr("panel_keyboard_title"),
                    color = themeColor,
                    fontSize = titleFontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                KeyboardRow(tr("keyboard_normal"), current == KeyboardStyle.NORMAL, themeColor, rowFontSize) {
                    onSelect(KeyboardStyle.NORMAL)
                    // Normal = system default keyboard, just close panel
                    onDismiss()
                }

                KeyboardRow(tr("keyboard_xenos_matrix"), current == KeyboardStyle.XENOS, themeColor, rowFontSize) {
                    onSelect(KeyboardStyle.XENOS)
                    // Ask system to show picker so user selects XenosKeyboardService
                    showInputMethodPicker(context)
                    onDismiss()
                }

                KeyboardRow(tr("keyboard_cedal"), current == KeyboardStyle.CEDAL, themeColor, rowFontSize) {
                    onSelect(KeyboardStyle.CEDAL)
                    // Ask system to show picker so user selects CedalKeyboardService
                    showInputMethodPicker(context)
                    onDismiss()
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = tr("keyboard_picker_hint"),
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
            text = if (selected) tr("value_selected") else "",
            color = if (selected) themeColor else Color.Transparent,
            fontSize = (fontSize.value - 4).coerceAtLeast(8f).sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** Open source means no shared backend - each install needs its own deployment (see
 * backend/elene/.env.example), and this is the friendliest way to point at it: no rebuild, no
 * touching local.properties, just paste the URL. Saved via EleneApiClient.setBaseUrl(), which
 * both persists it (theme_prefs) and updates the live in-memory value immediately. */
@Composable
private fun BackendUrlDialog(
    themeColor: Color,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(EleneApiClient.currentBaseUrl()) }

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
                .padding(16.dp)
                .clickable(enabled = false) {},
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
                    text = tr("backend_url_dialog_title"),
                    color = themeColor,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Text(
                    text = tr("backend_url_help"),
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(tr("backend_url_hint"), fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tr("action_cancel"),
                        color = Color.Gray,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { onDismiss() }
                    )
                    Button(
                        onClick = {
                            EleneApiClient.setBaseUrl(context, url)
                            onSaved()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = themeColor)
                    ) {
                        Text(
                            tr("action_save"),
                            color = Color.Black,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private const val FEEDBACK_RECIPIENT = "hackerxenos06@gmail.com"

/** In-app feedback: types a message (+ optionally their own Gmail so a reply is possible), SEND
 * opens the phone's own mail app with the recipient/subject/body pre-filled - it sends through
 * their own already-logged-in mail account with one more tap there, no backend/credentials
 * needed on this end at all. */
@Composable
private fun FeedbackDialog(
    themeColor: Color,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var message by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }

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
                .padding(16.dp)
                .clickable(enabled = false) {}, // absorb taps so they don't fall through to dismiss
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
                    text = tr("row_feedback"),
                    color = themeColor,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text(tr("feedback_message_hint"), fontFamily = FontFamily.Monospace) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(tr("feedback_email_hint"), fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tr("action_cancel"),
                        color = Color.Gray,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { onDismiss() }
                    )
                    Button(
                        onClick = {
                            if (message.isBlank()) return@Button
                            val bodyText = if (email.isNotBlank()) "$message\n\n-- from: $email" else message
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:$FEEDBACK_RECIPIENT")
                                putExtra(Intent.EXTRA_SUBJECT, "SciFiLauncher Feedback")
                                putExtra(Intent.EXTRA_TEXT, bodyText)
                            }
                            runCatching { context.startActivity(intent) }
                            onDismiss()
                        },
                        enabled = message.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = themeColor)
                    ) {
                        Text(
                            tr("action_send"),
                            color = Color.Black,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

