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
    onBackToDashboard: () -> Unit,
    onOpenHiddenApps: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenFileManager: () -> Unit,
    onOpenRequests: () -> Unit,
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
    cedalSharedSystemEnabled: Boolean,
    onToggleCedalSharedSystem: (Boolean) -> Unit,
    kioskModeEnabled: Boolean,
    onToggleKioskMode: (Boolean) -> Unit,
    onOpenLockScreenSettings: () -> Unit,
    onArmSequenceMode: () -> Unit,
    onExitSequenceMode: () -> Unit
) {
    var showListeningInfoDialog by remember { mutableStateOf(showListeningInfo) }

    var showManualArmInfo by remember { mutableStateOf(false) }
    var showCedalSharedSystemInfo by remember { mutableStateOf(false) }
    var showLockdownInfo by remember { mutableStateOf(false) }
    var showLocationHistoryInfo by remember { mutableStateOf(false) }
    var showFullWipeInfo by remember { mutableStateOf(false) }
    var showKioskInfo by remember { mutableStateOf(false) }
    var showLockScreenInfo by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        PanelBackdrop(isDark = isDark)
        androidx.compose.runtime.CompositionLocalProvider(LocalPanelIsDark provides isDark) {

        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 40.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "< DASH",
                color = themeColor,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .clickable { onBackToDashboard() }
            )

            Text(
                text = "SECURITY CENTER",
                color = themeColor,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            PanelSection(title = "DATA & MEDIA", themeColor = themeColor) {
                PanelRow(label = "Freezer", themeColor = themeColor, onClick = onOpenFreezer)
                PanelRow(label = "Hidden apps", themeColor = themeColor, onClick = onOpenHiddenApps)
                PanelRow(label = "Intruder attempts", themeColor = themeColor, onClick = onOpenStorage)
                PanelRow(label = "File manager", themeColor = themeColor, onClick = onOpenFileManager)
                PanelRow(label = "Commands", themeColor = themeColor, showDivider = false, onClick = onOpenCommands)
            }

            PanelSection(title = "ACTIVITY", themeColor = themeColor) {
                PanelRow(label = "Requests", themeColor = themeColor, onClick = onOpenRequests)
                PanelRow(label = "Log", themeColor = themeColor, onClick = onOpenAppLog)
                PanelRow(label = "New app installs", themeColor = themeColor, onClick = onOpenInstallFlags)
                PanelToggleRow(
                    label = "Watch new installs",
                    themeColor = themeColor,
                    checked = installWatchEnabled,
                    showDivider = false,
                    onToggle = onToggleInstallWatch
                )
            }

            PanelSection(title = "SEQUENCE MODE", themeColor = themeColor) {
                Text(
                    text = "Anti-theft system. \"Standing by\" = watching normally. \"ACTIVE\" " +
                            "means a failed identity check triggered lockdown just now.",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                PanelStaticInfoRow(
                    label = "Anti-theft status",
                    value = if (isSequenceModeActive(lockPrefs)) "ACTIVE - locked down" else "Standing by",
                    valueColor = if (isSequenceModeActive(lockPrefs)) Color.Red else themeColor,
                    themeColor = themeColor
                )
                if (isSequenceModeActive(lockPrefs)) {
                    PanelRow(
                        label = "Exit Sequence Mode",
                        themeColor = themeColor,
                        onClick = onExitSequenceMode
                    )
                } else {
                    PanelRow(
                        label = "Trigger lockdown now",
                        themeColor = themeColor,
                        onInfoClick = { showManualArmInfo = true },
                        onClick = onArmSequenceMode
                    )
                }
                val lastLocation = loadLastKnownLocation(lockPrefs) ?: currentDeviceLocation
                PanelStaticInfoRow(
                    label = "Last known location",
                    value = if (lastLocation != null) {
                        val (lat, lng, at) = lastLocation
                        val minutesAgo = (System.currentTimeMillis() - at) / 60_000L
                        if (minutesAgo <= 0) "Just now" else "$minutesAgo min ago"
                    } else {
                        "Unavailable"
                    },
                    valueColor = Color.Gray,
                    themeColor = themeColor
                )
                PanelRow(
                    label = "Location history",
                    themeColor = themeColor,
                    value = "${locationHistoryCount} entries",
                    onClick = onOpenLocationHistory
                )
                PanelToggleRow(
                    label = "Track location history",
                    themeColor = themeColor,
                    checked = locationHistoryEnabled,
                    onToggle = onToggleLocationHistory,
                    onInfoClick = { showLocationHistoryInfo = true }
                )
                if (deviceAdminActive) {
                    PanelStaticInfoRow(
                        label = "OS-level lockdown",
                        value = "ENABLED",
                        valueColor = themeColor,
                        themeColor = themeColor
                    )
                } else {
                    PanelRow(
                        label = "Enable OS-level lockdown",
                        themeColor = themeColor,
                        onInfoClick = { showLockdownInfo = true },
                        onClick = onRequestDeviceAdmin
                    )
                }
                PanelToggleRow(
                    label = "Full-device wipe if never recovered",
                    themeColor = themeColor,
                    checked = fullWipeEnabled,
                    showDivider = false,
                    onToggle = { onToggleFullWipe(it) },
                    onInfoClick = { showFullWipeInfo = true }
                )
            }

            PanelSection(title = "NETWORK PROTECTION", themeColor = themeColor) {
                PanelToggleRow(
                    label = "Tracker & ad blocking",
                    themeColor = themeColor,
                    checked = trackerBlockingActive,
                    onToggle = { onToggleTrackerBlocking(it) }
                )
                PanelRow(
                    label = "Router",
                    themeColor = themeColor,
                    value = "Sky",
                    showDivider = false,
                    onClick = onOpenRouterSettings
                )
            }

            PanelSection(title = "CEDAL SHARED SYSTEM", themeColor = themeColor) {
                PanelToggleRow(
                    label = "Cedal Shared System",
                    themeColor = themeColor,
                    checked = cedalSharedSystemEnabled,
                    showDivider = false,
                    onToggle = { onToggleCedalSharedSystem(it) },
                    onInfoClick = { showCedalSharedSystemInfo = true }
                )
            }

            PanelSection(title = "SHIZUKU", themeColor = themeColor) {
                var shizukuAvailable by remember { mutableStateOf(ShizukuManager.isAvailable()) }
                var shizukuGranted by remember { mutableStateOf(ShizukuManager.hasPermission()) }
                var showShizukuInfo by remember { mutableStateOf(false) }
                PanelRow(
                    label = "Shizuku access",
                    themeColor = themeColor,
                    value = when {
                        !shizukuAvailable -> "Not running"
                        shizukuGranted -> "Granted"
                        else -> "Tap to grant"
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
                                Text("CLOSE", color = themeColor, fontFamily = FontFamily.Monospace)
                            }
                        },
                        title = { Text("Shizuku access", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                        text = {
                            Text(
                                "Shizuku lets this app run commands with real ADB/shell-level " +
                                    "privilege - the same access `adb shell` has - without " +
                                    "rooting the phone. It unlocks a genuine force-stop " +
                                    "(matching Settings > App Info > Force Stop exactly), " +
                                    "instead of the lighter \"stop background processes\" this " +
                                    "app falls back to without it.\n\n" +
                                    "Setup happens outside this app: install Shizuku, pair it " +
                                    "with wireless debugging (Developer Options), and start " +
                                    "its service. On most phones this needs redoing after a " +
                                    "reboot unless the device is rooted. Nothing here works " +
                                    "silently - you'll see Shizuku's own permission prompt the " +
                                    "first time this app asks.",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }
                    )
                }
            }

            PanelSection(title = "VOICE ID", themeColor = themeColor) {
                val voiceContext = LocalContext.current
                val voiceScope = rememberCoroutineScope()
                var voiceEnrolled by remember { mutableStateOf(VoiceIdManager.isEnrolled(voiceContext)) }
                var voiceBusy by remember { mutableStateOf(false) }
                var voiceStatus by remember { mutableStateOf<String?>(null) }
                var showVoiceInfo by remember { mutableStateOf(false) }

                PanelRow(
                    label = if (voiceEnrolled) "Add voice samples" else "Enroll voice",
                    themeColor = themeColor,
                    value = if (voiceBusy) "Working..." else if (voiceEnrolled) "Enrolled" else "Not enrolled",
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
                                        voiceStatus = "${style.label} (take $take of 3, " +
                                            "${style.recordSeconds}s) - say:\n\"${style.prompt}\""
                                        val sample = recordVoiceSample(
                                            voiceContext,
                                            style.recordSeconds * VOICE_SAMPLE_RATE
                                        )
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
                                    "Couldn't record - check microphone permission."
                                } else {
                                    voiceEnrolled = true
                                    "Enrolled - all 5 styles."
                                }
                                voiceBusy = false
                            }
                        }
                    }
                )
                PanelRow(
                    label = "Test voice match",
                    themeColor = themeColor,
                    onClick = {
                        if (voiceBusy || !voiceEnrolled) return@PanelRow
                        voiceBusy = true
                        val style = VoiceStyle.entries.random()
                        voiceStatus = "Say (${style.recordSeconds}s): \"${style.prompt}\""
                        voiceScope.launch {
                            val sample = recordVoiceSample(voiceContext, style.recordSeconds * VOICE_SAMPLE_RATE)
                            voiceStatus = if (sample == null) {
                                "Couldn't record - check microphone permission."
                            } else {
                                val similarity = VoiceIdManager.verify(voiceContext, sample, style)
                                if (similarity == null) {
                                    "No enrollment on file for ${style.label}."
                                } else {
                                    val pass = similarity >= style.threshold
                                    "${style.label}: similarity %.2f (threshold %.2f) - %s".format(
                                        similarity, style.threshold, if (pass) "MATCH" else "NO MATCH"
                                    )
                                }
                            }
                            voiceBusy = false
                        }
                    }
                )
                PanelRow(
                    label = "Reset enrollment",
                    themeColor = themeColor,
                    showDivider = false,
                    onClick = {
                        if (voiceBusy) return@PanelRow
                        onRequestBiometricForVoiceId {
                            VoiceIdManager.reset(voiceContext)
                            voiceEnrolled = false
                            voiceStatus = "Enrollment cleared."
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
                                Text("CLOSE", color = themeColor, fontFamily = FontFamily.Monospace)
                            }
                        },
                        title = { Text("Voice ID", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                        text = {
                            Text(
                                "Offline speaker verification (ECAPA-TDNN) - runs entirely on " +
                                    "this device, nothing is sent anywhere. Enrollment records " +
                                    "five different styles (a long phrase, a medium phrase, a " +
                                    "short word, reciting letters, reciting digits) since a " +
                                    "single word and a full sentence sound different enough " +
                                    "that one reference doesn't compare fairly to both. Each " +
                                    "style has its own match threshold - shorter ones are more " +
                                    "lenient since there's less audio to work with.\n\n" +
                                    "The lock screen's voice option just records and compares " +
                                    "against the \"short word\" style directly.\n\n" +
                                    "\"Add voice samples\" doesn't replace what's already " +
                                    "enrolled - it adds to it. Your voice isn't one fixed " +
                                    "thing (tired, sick, or just talking differently all sound " +
                                    "a bit different), so if you keep getting rejected, come " +
                                    "back here and add a fresh sample in whatever state your " +
                                    "voice is in right now - verification checks against every " +
                                    "sample you've added and accepts the closest match, not an " +
                                    "average of all of them.",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }
                    )
                }
            }

            PanelSection(title = "KIOSK & LOCK SCREEN", themeColor = themeColor) {
                PanelToggleRow(
                    label = "Kiosk mode",
                    themeColor = themeColor,
                    checked = kioskModeEnabled,
                    onToggle = { onToggleKioskMode(it) },
                    onInfoClick = { showKioskInfo = true }
                )
                PanelRow(
                    label = "Change Android lock screen",
                    themeColor = themeColor,
                    showDivider = false,
                    onInfoClick = { showLockScreenInfo = true },
                    onClick = onOpenLockScreenSettings
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        if (showListeningInfoDialog) {
            AlertDialog(
                onDismissRequest = {
                    showListeningInfoDialog = false
                    onDismissListeningInfo(false)
                },
                title = {
                    Text(
                        text = "Listening mode",
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "When Listening mode is ON, Elene will use your voice input as commands or chat whenever you tap the mic.\n\n" +
                                    "Pros: hands-free, faster commands.\n" +
                                    "Drawbacks: anything you say after tapping the mic may trigger actions even if you didn't mean it.",
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
                        Text("OK", color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showListeningInfoDialog = false
                        onDismissListeningInfo(true) // never show again
                    }) {
                        Text("NEVER SHOW AGAIN", color = themeColor.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace)
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
                        text = "Trigger lockdown now",
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = "Immediately arms Sequence Mode: locks the phone right now (if " +
                                "OS-level lockdown is enabled) and starts the recovery countdown " +
                                "toward full-device wipe if that's turned on. Use this if the " +
                                "phone is lost or stolen. Exiting afterward requires a " +
                                "fingerprint.",
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showManualArmInfo = false }) {
                        Text("OK", color = themeColor, fontFamily = FontFamily.Monospace)
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
                        text = "Cedal Shared System",
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = "Cedal Shared System lets this app exchange basic version and " +
                                "configuration information with other apps you've installed that " +
                                "are also made by Cedal - verified by matching digital signature, " +
                                "so no other app can use this channel. It helps keep security " +
                                "settings consistent across Cedal apps. No personal data is " +
                                "shared. You can turn this off here at any time.",
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showCedalSharedSystemInfo = false }) {
                        Text("OK", color = themeColor, fontFamily = FontFamily.Monospace)
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
                        text = "OS-level lockdown",
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = "Enables the real Android lockscreen for Sequence Mode, not just " +
                                "this app's PIN, and is required if you also want full-device " +
                                "wipe. Once enabled, it can't be turned off from inside this app " +
                                "- only through Android's own Device Admin settings. That's " +
                                "intentional: if your phone is taken, whoever has it can't just " +
                                "tap a toggle in here to undo it.",
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showLockdownInfo = false }) {
                        Text("OK", color = themeColor, fontFamily = FontFamily.Monospace)
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
                        text = "Location history",
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = "When on, this records the device's location roughly every 15 " +
                                "minutes (using whatever fix the phone already has, not an active " +
                                "GPS request each time) so you can see where it's been, not just " +
                                "where it is right now. Stored locally only - view it under " +
                                "Location history above. Turn off any time; existing entries stay " +
                                "until you clear them.",
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showLocationHistoryInfo = false }) {
                        Text("OK", color = themeColor, fontFamily = FontFamily.Monospace)
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
                        text = "Full-device wipe if never recovered",
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = "If Sequence Mode is triggered and never recovered (about 30 " +
                                "days), this app normally only clears its own local data - " +
                                "locks, hidden/frozen app lists, intruder photos. Turning this ON " +
                                "instead factory-resets the entire phone at that point, not just " +
                                "this app. This requires OS-level lockdown to be enabled too, and " +
                                "cannot be undone once it happens - use it only if you'd rather " +
                                "lose everything than risk your data.",
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showFullWipeInfo = false }) {
                        Text("OK", color = themeColor, fontFamily = FontFamily.Monospace)
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
                        text = "Kiosk mode",
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = "Pins this app in full-screen using Android's own Screen " +
                                "Pinning feature - no other app, the notification shade, or " +
                                "Recents can be reached until it's unpinned with your app PIN. " +
                                "The first time you turn this on, Android will show its own " +
                                "one-time confirmation for pinning.",
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showKioskInfo = false }) {
                        Text("OK", color = themeColor, fontFamily = FontFamily.Monospace)
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
                        text = "Change Android lock screen",
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = "Opens Android's own Security settings, where you can change " +
                                "your lock method to \"Swipe\"/\"None\" if you want this app's " +
                                "PIN to be the only thing gating the phone. Doing that removes " +
                                "Android's own secure PIN prompt, but it also weakens the " +
                                "phone's underlying disk encryption, since that PIN is part of " +
                                "what protects your data at rest - not just a screen you see. " +
                                "This app can't make that change for you; only you can, inside " +
                                "Android's own settings.",
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showLockScreenInfo = false }) {
                        Text("OK", color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        }
    }
}

