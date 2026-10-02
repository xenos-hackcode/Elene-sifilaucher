package com.example.scifilauncher

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun SecurityScreen(
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    lockPrefs: SharedPreferences,
    languageOption: LanguageOption,
    onBackToDashboard: () -> Unit,
    onOpenHiddenApps: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenFileManager: () -> Unit,
    onOpenRequests: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenMyApps: () -> Unit,
    onOpenAppLog: () -> Unit,
    onOpenCommands: () -> Unit,
    onRequestBiometricForVoiceId: (onSuccess: () -> Unit) -> Unit,
    onOpenInstallFlags: () -> Unit,
    installWatchEnabled: Boolean,
    onToggleInstallWatch: (Boolean) -> Unit,
    currentDeviceLocation: Triple<Double, Double, Long>?,
    locationHistoryEnabled: Boolean,
    locationHistoryCount: Int,
    onToggleLocationHistory: (Boolean) -> Unit,
    onOpenLocationHistory: () -> Unit,
    showListeningInfo: Boolean,
    onDismissListeningInfo: (neverShowAgain: Boolean) -> Unit,
    onOpenFreezer: () -> Unit,
    deviceAdminActive: Boolean,
    onRequestDeviceAdmin: () -> Unit,
    fullWipeEnabled: Boolean,
    onToggleFullWipe: (Boolean) -> Unit,
    trackerBlockingActive: Boolean,
    onToggleTrackerBlocking: (Boolean) -> Unit,
    onOpenRouterSettings: () -> Unit,
    proxyAddress: String,
    onProxyAddressChange: (String) -> Unit,
    cedalSharedSystemEnabled: Boolean,
    onToggleCedalSharedSystem: (Boolean) -> Unit,
    kioskModeEnabled: Boolean,
    onToggleKioskMode: (Boolean) -> Unit,
    gestureControlEnabled: Boolean,
    onToggleGestureControl: (Boolean) -> Unit,
    onOpenGestureSettings: () -> Unit,
    pinchGestureEnabled: Boolean,
    onTogglePinchGesture: (Boolean) -> Unit,
    faceGesturesEnabled: Boolean,
    onToggleFaceGestures: (Boolean) -> Unit,
    onOpenLockScreenSettings: () -> Unit,
    onArmSequenceMode: () -> Unit,
    onExitSequenceMode: () -> Unit,
    antiTheftModeEnabled: Boolean,
    onToggleAntiTheftMode: (Boolean) -> Unit,
    onRequestCallScreeningRole: () -> Unit
) {
    val toolsContext = LocalContext.current
    var showListeningInfoDialog by remember { mutableStateOf(showListeningInfo) }

    var showManualArmInfo by remember { mutableStateOf(false) }
    var showAntiTheftInfo by remember { mutableStateOf(false) }
    var showEvacuationInfo by remember { mutableStateOf(false) }
    var showCedalSharedSystemInfo by remember { mutableStateOf(false) }
    var showLockdownInfo by remember { mutableStateOf(false) }
    var showLocationHistoryInfo by remember { mutableStateOf(false) }
    var showFullWipeInfo by remember { mutableStateOf(false) }
    var showKioskInfo by remember { mutableStateOf(false) }
    var showGestureControlInfo by remember { mutableStateOf(false) }
    var showFaceGesturesInfo by remember { mutableStateOf(false) }
    var showLockScreenInfo by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        PanelBackdrop(isDark = isDark)
        androidx.compose.runtime.CompositionLocalProvider(
            LocalPanelIsDark provides isDark,
            LocalLanguage provides languageOption
        ) {

        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 40.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            // Fixed header - stays on screen while the section content below scrolls, same as
            // SettingsScreen's own header. Previously this whole screen was one big scrollable
            // Column including the back button, so scrolling down this (long) screen's sections
            // scrolled the back button away too.
            Text(
                text = tr("back_dash"),
                color = themeColor,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .clickable { onBackToDashboard() }
            )

            Text(
                text = tr("security_center_title"),
                color = themeColor,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.Start
            ) {

            val statusContext = LocalContext.current
            val vpnConnected = remember { WireGuardVpnManager.currentState(statusContext) == com.wireguard.android.backend.Tunnel.State.UP }
            val shizukuOk = remember { ShizukuManager.hasPermission() }
            PanelSection(title = "PROTECTION STATUS", themeColor = themeColor) {
                ProtectionStatusRow("Tracker & ad blocking", trackerBlockingActive, themeColor)
                ProtectionStatusRow("VPN client", vpnConnected, themeColor)
                ProtectionStatusRow("Shizuku access", shizukuOk, themeColor)
                ProtectionStatusRow("Kiosk mode", kioskModeEnabled, themeColor)
                ProtectionStatusRow("Gesture control", gestureControlEnabled, themeColor, showDivider = false)
            }

            PanelSection(title = "Safety & creation", themeColor = themeColor) {
                PanelRow(label = "Safety check-in & device defense", themeColor = themeColor, onClick = {
                    toolsContext.startActivity(android.content.Intent(toolsContext, SafetyToolsActivity::class.java))
                })
                PanelRow(label = "Create an offline app", themeColor = themeColor, showDivider = false, onClick = {
                    toolsContext.startActivity(android.content.Intent(toolsContext, AppStarterActivity::class.java))
                })
            }

            PanelSection(title = tr("section_data_media"), themeColor = themeColor) {
                PanelRow(label = tr("row_freezer"), themeColor = themeColor, onClick = onOpenFreezer)
                PanelRow(label = tr("row_hidden_apps"), themeColor = themeColor, onClick = onOpenHiddenApps)
                PanelRow(label = tr("row_intruder_attempts"), themeColor = themeColor, onClick = onOpenStorage)
                PanelRow(label = tr("row_file_manager"), themeColor = themeColor, onClick = onOpenFileManager)
                PanelRow(label = tr("row_commands"), themeColor = themeColor, showDivider = false, onClick = onOpenCommands)
            }

            PanelSection(title = tr("section_activity"), themeColor = themeColor) {
                PanelRow(label = tr("row_requests"), themeColor = themeColor, onClick = onOpenRequests)
                PanelRow(label = tr("row_updates"), themeColor = themeColor, onClick = onOpenUpdates)
                PanelRow(label = tr("row_my_apps"), themeColor = themeColor, onClick = onOpenMyApps)
                PanelRow(label = tr("row_log"), themeColor = themeColor, onClick = onOpenAppLog)
                PanelRow(label = tr("row_new_app_installs"), themeColor = themeColor, onClick = onOpenInstallFlags)
                PanelToggleRow(
                    label = tr("row_watch_new_installs"),
                    themeColor = themeColor,
                    checked = installWatchEnabled,
                    showDivider = false,
                    onToggle = onToggleInstallWatch
                )
            }

            PanelSection(title = tr("section_sequence_mode"), themeColor = themeColor) {
                Text(
                    text = tr("sequence_mode_desc"),
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                PanelStaticInfoRow(
                    label = tr("row_anti_theft_status"),
                    value = when {
                        isSequenceModeActive(lockPrefs) -> tr("status_active_locked_down")
                        isMotionAlertPending(lockPrefs) -> tr("status_awaiting_confirmation")
                        else -> tr("status_standing_by")
                    },
                    valueColor = if (isSequenceModeActive(lockPrefs) || isMotionAlertPending(lockPrefs)) Color.Red else themeColor,
                    themeColor = themeColor
                )
                if (isSequenceModeActive(lockPrefs)) {
                    PanelRow(
                        label = tr("row_exit_sequence_mode"),
                        themeColor = themeColor,
                        onClick = onExitSequenceMode
                    )
                } else {
                    PanelRow(
                        label = tr("row_trigger_lockdown_now"),
                        themeColor = themeColor,
                        onInfoClick = { showManualArmInfo = true },
                        onClick = onArmSequenceMode
                    )
                }
                PanelToggleRow(
                    label = tr("row_anti_theft_mode"),
                    themeColor = themeColor,
                    checked = antiTheftModeEnabled,
                    onToggle = { onToggleAntiTheftMode(it) },
                    onInfoClick = { showAntiTheftInfo = true }
                )
                val lastLocation = loadLastKnownLocation(lockPrefs) ?: currentDeviceLocation
                PanelStaticInfoRow(
                    label = tr("row_last_known_location"),
                    value = if (lastLocation != null) {
                        val (lat, lng, at) = lastLocation
                        val minutesAgo = (System.currentTimeMillis() - at) / 60_000L
                        if (minutesAgo <= 0) tr("value_just_now") else tr("value_min_ago", minutesAgo.toString())
                    } else {
                        tr("value_unavailable")
                    },
                    valueColor = Color.Gray,
                    themeColor = themeColor
                )
                PanelRow(
                    label = tr("row_location_history"),
                    themeColor = themeColor,
                    value = tr("value_entries", locationHistoryCount.toString()),
                    onClick = onOpenLocationHistory
                )
                PanelToggleRow(
                    label = tr("row_track_location_history"),
                    themeColor = themeColor,
                    checked = locationHistoryEnabled,
                    onToggle = onToggleLocationHistory,
                    onInfoClick = { showLocationHistoryInfo = true }
                )
                if (deviceAdminActive) {
                    PanelStaticInfoRow(
                        label = tr("row_os_level_lockdown"),
                        value = tr("value_enabled"),
                        valueColor = themeColor,
                        themeColor = themeColor
                    )
                } else {
                    PanelRow(
                        label = tr("row_enable_os_level_lockdown"),
                        themeColor = themeColor,
                        onInfoClick = { showLockdownInfo = true },
                        onClick = onRequestDeviceAdmin
                    )
                }
                PanelToggleRow(
                    label = tr("row_full_device_wipe"),
                    themeColor = themeColor,
                    checked = fullWipeEnabled,
                    onToggle = { onToggleFullWipe(it) },
                    onInfoClick = { showFullWipeInfo = true }
                )
                val evacContext = LocalContext.current
                val evacScope = rememberCoroutineScope()
                var evacBusy by remember { mutableStateOf(false) }
                var evacStatus by remember { mutableStateOf<String?>(null) }
                val evacFailedMsg = tr("evac_failed_generic")
                PanelRow(
                    label = tr("row_test_evacuation_backup"),
                    themeColor = themeColor,
                    value = if (evacBusy) tr("value_uploading") else evacStatus ?: tr("value_not_run_yet"),
                    showDivider = false,
                    onInfoClick = { showEvacuationInfo = true },
                    onClick = {
                        if (evacBusy) return@PanelRow
                        evacBusy = true
                        evacStatus = null
                        evacScope.launch {
                            val result = PhoenixEvacuation.uploadBackup(evacContext)
                            evacStatus = result ?: evacFailedMsg
                            evacBusy = false
                        }
                    }
                )
            }

            PanelSection(title = tr("section_network_protection"), themeColor = themeColor) {
                PanelRow(label = "VPN countries & disconnect", themeColor = themeColor, onClick = {
                    toolsContext.startActivity(android.content.Intent(toolsContext, NetworkProtectionActivity::class.java))
                })
                PanelToggleRow(
                    label = tr("row_tracker_ad_blocking"),
                    themeColor = themeColor,
                    checked = trackerBlockingActive,
                    onToggle = { onToggleTrackerBlocking(it) }
                )
                val routerContext = LocalContext.current
                val gatewayIp = remember { currentWifiStatus(routerContext).gatewayIp }
                PanelRow(
                    label = tr("row_router"),
                    themeColor = themeColor,
                    value = gatewayIp ?: tr("value_unavailable"),
                    onClick = onOpenRouterSettings
                )
                PanelRow(
                    label = tr("row_vpn_client"),
                    themeColor = themeColor,
                    value = tr("value_manage"),
                    onClick = {
                        routerContext.startActivity(android.content.Intent(routerContext, VpnClientActivity::class.java))
                    }
                )
                val blockedTodayCount = remember { TrackerBlockStats.todayCount(routerContext) }
                PanelStaticInfoRow(
                    label = tr("row_blocked_today"),
                    value = blockedTodayCount.toString(),
                    valueColor = themeColor,
                    themeColor = themeColor
                )
                val blocklistScope = rememberCoroutineScope()
                var blocklistStatus by remember { mutableStateOf<String?>(null) }
                val updateNowLabel = tr("value_update_now")
                val updatingLabel = tr("value_updating")
                val updateFailedLabel = tr("value_update_failed")
                PanelRow(
                    label = tr("row_update_blocklist"),
                    themeColor = themeColor,
                    value = blocklistStatus ?: updateNowLabel,
                    onClick = {
                        blocklistStatus = updatingLabel
                        blocklistScope.launch {
                            val result = TrackerBlocklistUpdater.update(routerContext)
                            blocklistStatus = result.fold(
                                onSuccess = { count: Int -> "$count entries" },
                                onFailure = { _: Throwable -> updateFailedLabel }
                            )
                        }
                    }
                )
                PanelRow(
                    label = tr("row_app_exceptions"),
                    themeColor = themeColor,
                    value = tr("value_manage"),
                    onClick = {
                        routerContext.startActivity(android.content.Intent(routerContext, TrackerExceptionsActivity::class.java))
                    }
                )
                var showProxyDialog by remember { mutableStateOf(false) }
                var proxyInput by remember(proxyAddress) { mutableStateOf(proxyAddress) }
                PanelRow(
                    label = tr("row_traffic_proxy"),
                    themeColor = themeColor,
                    value = proxyAddress.ifBlank { tr("value_not_set") },
                    showDivider = false,
                    onInfoClick = { showProxyDialog = true },
                    onClick = { showProxyDialog = true }
                )
                if (showProxyDialog) {
                    AlertDialog(
                        onDismissRequest = { showProxyDialog = false },
                        title = { Text(tr("row_traffic_proxy"), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text(
                                    tr("dialog_traffic_proxy_body"),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                OutlinedTextField(
                                    value = proxyInput,
                                    onValueChange = { proxyInput = it },
                                    label = { Text(tr("label_ip_port"), fontFamily = FontFamily.Monospace) },
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                onProxyAddressChange(proxyInput.trim())
                                showProxyDialog = false
                            }) { Text(tr("action_save"), color = themeColor, fontFamily = FontFamily.Monospace) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showProxyDialog = false }) {
                                Text(tr("action_cancel"), color = themeColor, fontFamily = FontFamily.Monospace)
                            }
                        }
                    )
                }
            }

            PanelSection(title = tr("section_cedal_shared_system"), themeColor = themeColor) {
                PanelToggleRow(
                    label = tr("row_cedal_shared_system"),
                    themeColor = themeColor,
                    checked = cedalSharedSystemEnabled,
                    showDivider = false,
                    onToggle = { onToggleCedalSharedSystem(it) },
                    onInfoClick = { showCedalSharedSystemInfo = true }
                )
            }

            PanelSection(title = tr("section_shizuku"), themeColor = themeColor) {
                var shizukuAvailable by remember { mutableStateOf(ShizukuManager.isAvailable()) }
                var shizukuGranted by remember { mutableStateOf(ShizukuManager.hasPermission()) }
                var showShizukuInfo by remember { mutableStateOf(false) }
                PanelRow(
                    label = tr("row_shizuku_access"),
                    themeColor = themeColor,
                    value = when {
                        !shizukuAvailable -> tr("value_not_running")
                        shizukuGranted -> tr("value_granted")
                        else -> tr("value_tap_to_grant")
                    },
                    showDivider = false,
                    onInfoClick = { showShizukuInfo = true },
                    onClick = {
                        shizukuAvailable = ShizukuManager.isAvailable()
                        if (shizukuAvailable) {
                            ShizukuManager.requestPermission { granted -> shizukuGranted = granted }
                        }
                    }
                )
                if (showShizukuInfo) {
                    AlertDialog(
                        onDismissRequest = { showShizukuInfo = false },
                        confirmButton = {
                            TextButton(onClick = { showShizukuInfo = false }) {
                                Text(tr("action_close"), color = themeColor, fontFamily = FontFamily.Monospace)
                            }
                        },
                        title = { Text(tr("row_shizuku_access"), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                        text = {
                            Text(
                                tr("dialog_shizuku_body"),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }
                    )
                }
            }

            PanelSection(title = tr("section_phone_calls"), themeColor = themeColor) {
                val callContext = LocalContext.current
                var callScreeningGranted by remember { mutableStateOf(hasCallScreeningRole(callContext)) }
                var showCallScreeningInfo by remember { mutableStateOf(false) }
                PanelRow(
                    label = tr("row_decline_calls_by_voice"),
                    themeColor = themeColor,
                    value = if (callScreeningGranted) tr("value_granted") else tr("value_tap_to_grant"),
                    showDivider = false,
                    onInfoClick = { showCallScreeningInfo = true },
                    onClick = {
                        if (!callScreeningGranted) {
                            onRequestCallScreeningRole()
                        }
                        callScreeningGranted = hasCallScreeningRole(callContext)
                    }
                )
                if (showCallScreeningInfo) {
                    AlertDialog(
                        onDismissRequest = { showCallScreeningInfo = false },
                        confirmButton = {
                            TextButton(onClick = { showCallScreeningInfo = false }) {
                                Text(tr("action_close"), color = themeColor, fontFamily = FontFamily.Monospace)
                            }
                        },
                        title = { Text(tr("row_decline_calls_by_voice"), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                        text = {
                            Text(
                                tr("dialog_decline_calls_body"),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }
                    )
                }
            }

            PanelSection(title = tr("section_voice_id"), themeColor = themeColor) {
                val voiceContext = LocalContext.current
                val voiceScope = rememberCoroutineScope()
                var voiceEnrolled by remember { mutableStateOf(VoiceIdManager.isEnrolled(voiceContext)) }
                var voiceBusy by remember { mutableStateOf(false) }
                var voiceStatus by remember { mutableStateOf<String?>(null) }
                var showVoiceInfo by remember { mutableStateOf(false) }
                var voiceDrifting by remember { mutableStateOf(VoiceIdConfidenceLog.isDrifting(voiceContext)) }

                if (voiceDrifting) {
                    Text(
                        text = tr("voice_id_drifting_warning"),
                        color = Color(0xFFFFA726),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    )
                }

                val voiceCouldntRecordMsg = tr("voice_couldnt_record")
                PanelRow(
                    label = if (voiceEnrolled) tr("row_add_voice_samples") else tr("row_enroll_voice"),
                    themeColor = themeColor,
                    value = if (voiceBusy) tr("value_working") else if (voiceEnrolled) tr("value_enrolled") else tr("value_not_enrolled"),
                    onInfoClick = { showVoiceInfo = true },
                    onClick = {
                        if (voiceBusy) return@PanelRow
                        // Adding samples grows whose voice the device trusts - anyone with the
                        // phone unlocked could otherwise add their own voice into the mix.
                        // Fingerprint first, always.
                        onRequestBiometricForVoiceId {
                            voiceBusy = true
                            voiceScope.launch {
                                var failed = false
                                for (style in VoiceStyle.entries) {
                                    val samples = mutableListOf<FloatArray>()
                                    // 3 takes per style, same as before - just per style now
                                    // instead of one style repeated three times.
                                    for (take in 1..3) {
                                        var sample: FloatArray? = null
                                        // A single bad take (late start, mic hiccup, VAD finding
                                        // no speech) shouldn't nuke the whole 15-take sequence -
                                        // retry just that take a couple times before giving up.
                                        var attempt = 0
                                        while (sample == null && attempt < 3) {
                                            attempt++
                                            voiceStatus = (if (attempt == 1) "" else uiString("voice_didnt_catch_that", languageOption)) +
                                                uiString(
                                                    "voice_take_prompt", languageOption,
                                                    style.label, take.toString(), style.recordSeconds.toString(), style.prompt
                                                )
                                            sample = recordVoiceSample(
                                                voiceContext,
                                                style.recordSeconds * VOICE_SAMPLE_RATE
                                            )
                                        }
                                        if (sample == null) { failed = true; break }
                                        samples.add(sample)
                                    }
                                    if (failed) break
                                    if (!VoiceIdManager.enrollStyle(voiceContext, style, samples)) {
                                        failed = true
                                        break
                                    }
                                }
                                voiceStatus = if (failed) {
                                    voiceCouldntRecordMsg
                                } else {
                                    voiceEnrolled = true
                                    VoiceIdConfidenceLog.clear(voiceContext)
                                    voiceDrifting = false
                                    uiString("voice_enrolled_all_styles", languageOption)
                                }
                                voiceBusy = false
                            }
                        }
                    }
                )
                PanelRow(
                    label = tr("row_test_voice_match"),
                    themeColor = themeColor,
                    onClick = {
                        if (voiceBusy || !voiceEnrolled) return@PanelRow
                        voiceBusy = true
                        val style = VoiceStyle.entries.random()
                        voiceStatus = uiString("voice_say_seconds", languageOption, style.recordSeconds.toString(), style.prompt)
                        voiceScope.launch {
                            val sample = recordVoiceSample(voiceContext, style.recordSeconds * VOICE_SAMPLE_RATE)
                            voiceStatus = if (sample == null) {
                                voiceCouldntRecordMsg
                            } else {
                                val similarity = VoiceIdManager.verify(voiceContext, sample, style)
                                if (similarity == null) {
                                    uiString("voice_no_enrollment_for", languageOption, style.label)
                                } else {
                                    val pass = similarity >= style.threshold
                                    uiString(
                                        "voice_similarity_result", languageOption,
                                        style.label, "%.2f".format(similarity), "%.2f".format(style.threshold),
                                        uiString(if (pass) "voice_match" else "voice_no_match", languageOption)
                                    )
                                }
                            }
                            voiceBusy = false
                        }
                    }
                )
                PanelRow(
                    label = tr("row_reset_enrollment"),
                    themeColor = themeColor,
                    showDivider = false,
                    onClick = {
                        if (voiceBusy) return@PanelRow
                        onRequestBiometricForVoiceId {
                            VoiceIdManager.reset(voiceContext)
                            voiceEnrolled = false
                            voiceStatus = uiString("voice_enrollment_cleared", languageOption)
                        }
                    }
                )
                voiceStatus?.let {
                    Text(
                        text = it,
                        color = themeColor.copy(alpha = 0.8f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                    )
                }
                if (showVoiceInfo) {
                    AlertDialog(
                        onDismissRequest = { showVoiceInfo = false },
                        confirmButton = {
                            TextButton(onClick = { showVoiceInfo = false }) {
                                Text(tr("action_close"), color = themeColor, fontFamily = FontFamily.Monospace)
                            }
                        },
                        title = { Text(tr("dialog_voice_id_title"), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                        text = {
                            Text(
                                tr("dialog_voice_id_body"),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }
                    )
                }
            }

            PanelSection(title = tr("section_kiosk_lock_screen"), themeColor = themeColor) {
                PanelToggleRow(
                    label = tr("row_kiosk_mode"),
                    themeColor = themeColor,
                    checked = kioskModeEnabled,
                    onToggle = { onToggleKioskMode(it) },
                    onInfoClick = { showKioskInfo = true }
                )
                PanelToggleRow(
                    label = tr("row_gesture_control"),
                    themeColor = themeColor,
                    checked = gestureControlEnabled,
                    onToggle = { onToggleGestureControl(it) },
                    onInfoClick = { showGestureControlInfo = true }
                )
                PanelRow(
                    label = tr("row_configure_gestures"),
                    themeColor = themeColor,
                    onClick = onOpenGestureSettings
                )
                PanelToggleRow(
                    label = tr("row_pinch_gesture"),
                    themeColor = themeColor,
                    checked = pinchGestureEnabled,
                    onToggle = { onTogglePinchGesture(it) }
                )
                PanelToggleRow(
                    label = tr("row_face_gestures"),
                    themeColor = themeColor,
                    checked = faceGesturesEnabled,
                    onToggle = { onToggleFaceGestures(it) },
                    onInfoClick = { showFaceGesturesInfo = true }
                )
                PanelRow(
                    label = tr("row_change_lock_screen"),
                    themeColor = themeColor,
                    showDivider = false,
                    onInfoClick = { showLockScreenInfo = true },
                    onClick = onOpenLockScreenSettings
                )
            }

            IntegrityTamperSection(themeColor = themeColor)

            Spacer(modifier = Modifier.height(24.dp))
            }
        }

        if (showListeningInfoDialog) {
            AlertDialog(
                onDismissRequest = {
                    showListeningInfoDialog = false
                    onDismissListeningInfo(false)
                },
                title = {
                    Text(
                        text = tr("dialog_listening_mode_title"),
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Column {
                        Text(
                            text = tr("dialog_listening_mode_body"),
                            color = if (isDark) Color.White else Color.Black,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showListeningInfoDialog = false
                        onDismissListeningInfo(false)
                    }) {
                        Text(tr("action_ok"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showListeningInfoDialog = false
                        onDismissListeningInfo(true) // never show again
                    }) {
                        Text(tr("action_never_show_again"), color = themeColor.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        // MANUAL SEQUENCE MODE ARM disclosure
        if (showManualArmInfo) {
            AlertDialog(
                onDismissRequest = { showManualArmInfo = false },
                title = {
                    Text(
                        text = tr("row_trigger_lockdown_now"),
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = tr("dialog_trigger_lockdown_body"),
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showManualArmInfo = false }) {
                        Text(tr("action_ok"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        // ANTI-THEFT MODE disclosure
        if (showAntiTheftInfo) {
            AlertDialog(
                onDismissRequest = { showAntiTheftInfo = false },
                title = {
                    Text(
                        text = tr("row_anti_theft_mode"),
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = tr("dialog_anti_theft_body"),
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showAntiTheftInfo = false }) {
                        Text(tr("action_ok"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        // CEDAL SHARED SYSTEM disclosure
        if (showCedalSharedSystemInfo) {
            AlertDialog(
                onDismissRequest = { showCedalSharedSystemInfo = false },
                title = {
                    Text(
                        text = tr("row_cedal_shared_system"),
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = tr("dialog_cedal_body"),
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showCedalSharedSystemInfo = false }) {
                        Text(tr("action_ok"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        // OS-LEVEL LOCKDOWN disclosure
        if (showLockdownInfo) {
            AlertDialog(
                onDismissRequest = { showLockdownInfo = false },
                title = {
                    Text(
                        text = tr("row_os_level_lockdown"),
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = tr("dialog_os_lockdown_body"),
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showLockdownInfo = false }) {
                        Text(tr("action_ok"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        // LOCATION HISTORY disclosure
        if (showLocationHistoryInfo) {
            AlertDialog(
                onDismissRequest = { showLocationHistoryInfo = false },
                title = {
                    Text(
                        text = tr("row_location_history"),
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = tr("dialog_location_history_body"),
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showLocationHistoryInfo = false }) {
                        Text(tr("action_ok"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        // FULL-DEVICE WIPE disclosure
        if (showFullWipeInfo) {
            AlertDialog(
                onDismissRequest = { showFullWipeInfo = false },
                title = {
                    Text(
                        text = tr("row_full_device_wipe"),
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = tr("dialog_full_wipe_body"),
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showFullWipeInfo = false }) {
                        Text(tr("action_ok"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        // EVACUATION BACKUP disclosure
        if (showEvacuationInfo) {
            AlertDialog(
                onDismissRequest = { showEvacuationInfo = false },
                title = {
                    Text(
                        text = tr("row_test_evacuation_backup"),
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = tr("dialog_evacuation_body"),
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showEvacuationInfo = false }) {
                        Text(tr("action_ok"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        // KIOSK MODE disclosure
        if (showKioskInfo) {
            AlertDialog(
                onDismissRequest = { showKioskInfo = false },
                title = {
                    Text(
                        text = tr("row_kiosk_mode"),
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = tr("dialog_kiosk_body"),
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showKioskInfo = false }) {
                        Text(tr("action_ok"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        // GESTURE CONTROL disclosure
        if (showGestureControlInfo) {
            AlertDialog(
                onDismissRequest = { showGestureControlInfo = false },
                title = {
                    Text(
                        text = tr("row_gesture_control"),
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = tr("dialog_gesture_control_body"),
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showGestureControlInfo = false }) {
                        Text(tr("action_ok"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        // FACE GESTURES disclosure
        if (showFaceGesturesInfo) {
            AlertDialog(
                onDismissRequest = { showFaceGesturesInfo = false },
                title = {
                    Text(
                        text = tr("row_face_gestures"),
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = tr("dialog_face_gestures_body"),
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showFaceGesturesInfo = false }) {
                        Text(tr("action_ok"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        // CHANGE ANDROID LOCK SCREEN disclosure
        if (showLockScreenInfo) {
            AlertDialog(
                onDismissRequest = { showLockScreenInfo = false },
                title = {
                    Text(
                        text = tr("row_change_lock_screen"),
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = tr("dialog_lock_screen_body"),
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showLockScreenInfo = false }) {
                        Text(tr("action_ok"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        }
    }
}

/** Extracted out of SecurityScreen's own body - that composable had grown large enough that this
 * self-contained block (unchanged from the original anti-tampering/RASP hardening commit) started
 * failing to compile in place with a confusing, tightly-scoped "not a composable context" cascade
 * once more sections were added around it, despite its own content being correct. Splitting it
 * into its own named composable resolved it - smaller composable functions are also just better
 * practice than one 900+ line function regardless. */
@Composable
private fun IntegrityTamperSection(themeColor: Color) {
    PanelSection(title = tr("section_integrity_tamper"), themeColor = themeColor) {
        var showIntegrityInfo by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val isGenuine = remember { AppIntegrityCheck.isGenuine(context) }
        val rootStatus = remember { RootDetection.check(context) }
        val fridaResult = remember { runCatching { FridaDetector.scan() }.getOrNull() }

        PanelStaticInfoRow(
            label = tr("row_app_integrity"),
            value = if (isGenuine) tr("value_verified") else tr("value_modified"),
            valueColor = if (isGenuine) themeColor else Color.Red,
            themeColor = themeColor
        )
        PanelStaticInfoRow(
            label = tr("row_root_magisk"),
            value = if (rootStatus.looksRooted) tr("value_detected") else tr("value_not_detected"),
            valueColor = if (rootStatus.looksRooted) Color.Red else themeColor,
            themeColor = themeColor
        )
        PanelStaticInfoRow(
            label = tr("row_selinux"),
            value = when (rootStatus.selinuxEnforcing) {
                true -> tr("value_enforcing")
                false -> tr("value_permissive")
                null -> tr("value_unknown")
            },
            valueColor = when (rootStatus.selinuxEnforcing) {
                true -> themeColor
                false -> Color.Red
                null -> Color.Gray
            },
            themeColor = themeColor
        )
        PanelStaticInfoRow(
            label = tr("row_instrumentation_frida"),
            value = when {
                fridaResult == null -> tr("value_frida_unavailable")
                fridaResult.signalCount == 0 -> tr("value_not_detected")
                fridaResult.signalCount == 1 -> tr("value_frida_possible")
                else -> tr("value_frida_detected", fridaResult.signalCount.toString())
            },
            valueColor = when {
                fridaResult == null -> Color.Gray
                fridaResult.signalCount == 0 -> themeColor
                fridaResult.signalCount == 1 -> Color(0xFFFFA500)
                else -> Color.Red
            },
            themeColor = themeColor
        )
        PanelRow(
            label = tr("row_about_these_checks"),
            themeColor = themeColor,
            showDivider = false,
            onClick = { showIntegrityInfo = true }
        )
        if (showIntegrityInfo) {
            AlertDialog(
                onDismissRequest = { showIntegrityInfo = false },
                confirmButton = {
                    TextButton(onClick = { showIntegrityInfo = false }) {
                        Text(tr("action_close"), color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                },
                title = { Text(tr("section_integrity_tamper"), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        text = tr("dialog_integrity_body"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            )
        }
    }
}

