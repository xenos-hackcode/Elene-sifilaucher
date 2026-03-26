package com.example.scifilauncher

import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import java.util.Locale
import android.speech.tts.TextToSpeech
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.example.scifilauncher.ui.theme.SciFiLauncherTheme

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null

    // launcher-wide apps state so we can refresh onResume
    private var allAppsState by mutableStateOf(listOf<AppItem>())

    // assistant state
    private var waitingForReadConfirmation: Boolean = false
    private var readingAllMissed: Boolean = false
    private var currentReadIndex: Int = 0

    private var askingWhichToReply: Boolean = false
    private var askingWhichPerson: Boolean = false

    // reply state
    private var waitingForReplyText: Boolean = false
    private var replyTargetKey: String? = null
    private var replyTargetTitle: String? = null
    private var replyTargetAppName: String? = null
    private var pendingAppForReply: String? = null   // lowercase app key like "whatsapp"

    private fun currentLanguage(): LanguageOption {
        val themePrefs = getSharedPreferences("theme_prefs", MODE_PRIVATE)
        return loadLanguage(themePrefs)
    }

    // Broadcast receiver for notification announcements
    private val notificationVoiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val appName = intent?.getStringExtra("appName") ?: "an app"
            val lang = currentLanguage()
            speak(elenePhrase("notif_from_app", lang, appName))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        tts = TextToSpeech(this, this)

        val pm: PackageManager = packageManager
        allAppsState = loadAllApps(pm)

        ContextCompat.registerReceiver(
            this,
            notificationVoiceReceiver,
            IntentFilter("com.example.scifilauncher.NEW_NOTIFICATION_VOICE"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            SciFiLauncherTheme {
                val context = LocalContext.current

                val lockPrefs = getSharedPreferences("lock_prefs", MODE_PRIVATE)
                val batteryPrefs = getSharedPreferences("battery_prefs", MODE_PRIVATE)
                val fontPrefs = getSharedPreferences("font_prefs", MODE_PRIVATE)
                val lastOpenedPrefs = getSharedPreferences("last_opened_prefs", MODE_PRIVATE)
                val themePrefs = getSharedPreferences("theme_prefs", MODE_PRIVATE)

                var batteryMode by remember { mutableStateOf(loadBatterySaverMode(batteryPrefs)) }
                var fontSizeOption by remember { mutableStateOf(loadFontSize(fontPrefs)) }
                var darkModeOption by remember { mutableStateOf(loadDarkMode(themePrefs)) }
                val lockTimeoutMinutes = loadLockTimeoutMinutes(lockPrefs) ?: 5
                val hideLockedNotifications = loadHideLockedNotifications(lockPrefs)
                val lockedAppsSet = loadLockedApps(lockPrefs)

                val isDark = (darkModeOption == DarkModeOption.DARK)

                val allApps by remember { derivedStateOf { allAppsState } }

                // Explicit type so compiler is happy
                val visibleApps by remember(batteryMode, allApps) {
                    mutableStateOf<List<AppItem>>(
                        filterAppsForBatteryMode(allApps, batteryMode, batteryPrefs)
                    )
                }

                // THEME STATE
                var themeIndex by rememberSaveable {
                    mutableStateOf(themePrefs.getInt("theme_index", 0))
                }
                var cycleTimerMinutes by rememberSaveable {
                    mutableStateOf(5)
                }
                var cyclePhase by rememberSaveable { mutableStateOf(0) }

                val activeTheme = CedalThemes[themeIndex % CedalThemes.size]
                val themeColor = if (cyclePhase == 0) activeTheme.primary else activeTheme.secondary

                LaunchedEffect(cycleTimerMinutes) {
                    while (true) {
                        kotlinx.coroutines.delay(cycleTimerMinutes * 60_000L)
                        cyclePhase = (cyclePhase + 1) % 2
                    }
                }

                var showWelcome by rememberSaveable { mutableStateOf(true) }
                var showApps by rememberSaveable { mutableStateOf(false) }
                var showRecents by rememberSaveable { mutableStateOf(false) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var showSecurity by rememberSaveable { mutableStateOf(false) }
                var showLockedApps by rememberSaveable { mutableStateOf(false) }
                var showHiddenApps by rememberSaveable { mutableStateOf(false) }
                var showFavoriteApps by rememberSaveable { mutableStateOf(false) }
                var showBatteryAllowedApps by rememberSaveable { mutableStateOf(false) }
                var isPageMode by rememberSaveable { mutableStateOf(false) }

                var recentApps by remember { mutableStateOf(listOf<AppItem>()) }
                val maxRecents = 10

                var appPin by rememberSaveable { mutableStateOf<String?>(null) }
                var hiddenApps by rememberSaveable { mutableStateOf(setOf<String>()) }
                var favoriteAppsPkgs by rememberSaveable { mutableStateOf(setOf<String>()) }

                var pendingLaunchPkg by remember { mutableStateOf<String?>(null) }
                var askForPinForLaunch by remember { mutableStateOf(false) }

                // Elene bubble + chat ui state
                var bubbleX by rememberSaveable { mutableStateOf(40f) }
                var bubbleY by rememberSaveable { mutableStateOf(200f) }
                var isEleneChatVisible by rememberSaveable { mutableStateOf(false) }
                var eleneText by remember { mutableStateOf(TextFieldValue("")) }

                LaunchedEffect(Unit) {
                    speakWelcome(isRed = false)
                }

                val favoriteApps = visibleApps
                    .filter { it.packageName in favoriteAppsPkgs }
                    .take(6)

                Box(modifier = Modifier.fillMaxSize()) {

                    // MAIN SCREENS
                    when {
                        showWelcome -> {
                            WelcomeScreen(
                                modifier = Modifier.fillMaxSize(),
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                onContinue = { showWelcome = false },
                                onColorChange = { _ -> /* no longer using isRed */ }
                            )
                        }

                        showApps -> {
                            AppsScreen(
                                modifier = Modifier.fillMaxSize(),
                                themeColor = themeColor,
                                apps = visibleApps,
                                isPageMode = isPageMode,
                                lockedApps = loadLockedApps(lockPrefs),
                                hiddenApps = hiddenApps,
                                fontSizeOption = fontSizeOption,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                onToggleLayout = { isPageMode = !isPageMode },
                                onBackToDashboard = {
                                    showApps = false
                                    showRecents = false
                                    showWelcome = false
                                    showSettings = false
                                    showSecurity = false
                                    showLockedApps = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                onOpenRecents = {
                                    showApps = false
                                    showRecents = true
                                    showSettings = false
                                    showSecurity = false
                                    showLockedApps = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                onAppClick = { pkg ->
                                    val lockedSet = loadLockedApps(lockPrefs)
                                    val isLockedApp = lockedSet.contains(pkg)
                                    val lockTimeoutMinutes = loadLockTimeoutMinutes(lockPrefs)

                                    if (isLockedApp && appPin != null) {
                                        if (shouldRequireUnlock(lockPrefs, lockTimeoutMinutes)) {
                                            pendingLaunchPkg = pkg
                                            askForPinForLaunch = true
                                        } else {
                                            launchApp(
                                                pkg,
                                                visibleApps,
                                                pm,
                                                maxRecents,
                                                lastOpenedPrefs
                                            ) { updated -> recentApps = updated }
                                        }
                                    } else {
                                        launchApp(
                                            pkg,
                                            visibleApps,
                                            pm,
                                            maxRecents,
                                            lastOpenedPrefs
                                        ) { updated -> recentApps = updated }
                                    }
                                },
                                onUninstall = { pkg ->
                                    val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                                        data = Uri.parse("package:$pkg")
                                    }
                                    startActivity(intent)
                                },
                                onAppInfo = { pkg ->
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:$pkg")
                                    }
                                    startActivity(intent)
                                },
                                getLastOpenedText = { pkg ->
                                    getLastOpenedText(lastOpenedPrefs, pkg)
                                }
                            )
                        }

                        showRecents -> {
                            val filteredRecents = recentApps.filter { app ->
                                visibleApps.any { it.packageName == app.packageName }
                            }
                            RecentsScreen(
                                modifier = Modifier.fillMaxSize(),
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                recentApps = filteredRecents,
                                onBackToDashboard = {
                                    showRecents = false
                                    showWelcome = false
                                    showSettings = false
                                    showSecurity = false
                                    showLockedApps = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                onOpenAppsFromRecents = {
                                    showRecents = false
                                    showApps = true
                                    showSettings = false
                                    showSecurity = false
                                    showLockedApps = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                onAppClick = { pkg ->
                                    if (visibleApps.any { it.packageName == pkg }) {
                                        val launchIntent = pm.getLaunchIntentForPackage(pkg)
                                        if (launchIntent != null) {
                                            startActivity(launchIntent)
                                            recordLastOpened(lastOpenedPrefs, pkg)
                                        }
                                    }
                                }
                            )
                        }

                        showSettings -> {
                            SettingsScreen(
                                modifier = Modifier.fillMaxSize(),
                                themeColor = themeColor,
                                batteryMode = batteryMode,
                                currentThemeIndex = themeIndex,
                                currentCycleMinutes = cycleTimerMinutes,
                                onThemeChange = { idx -> themeIndex = idx },
                                onCycleMinutesChange = { minutes -> cycleTimerMinutes = minutes },
                                onBackToDashboard = {
                                    batteryMode = loadBatterySaverMode(batteryPrefs)
                                    fontSizeOption = loadFontSize(fontPrefs)
                                    darkModeOption = loadDarkMode(themePrefs)
                                    showSettings = false
                                    showWelcome = false
                                    showApps = false
                                    showRecents = false
                                    showSecurity = false
                                    showLockedApps = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                onOpenBatteryAllowedApps = {
                                    showSettings = false
                                    showBatteryAllowedApps = true
                                }
                            )
                        }

                        showHiddenApps -> {
                            HiddenAppsScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                apps = visibleApps,
                                hiddenApps = hiddenApps,
                                onHiddenAppsChange = { hiddenApps = it },
                                onBack = {
                                    showHiddenApps = false
                                    showSettings = false
                                    showSecurity = false
                                }
                            )
                        }

                        showLockedApps -> {
                            LockedAppsScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                apps = visibleApps,
                                lockPrefs = lockPrefs,
                                lockedApps = lockedAppsSet,
                                lockTimeoutMinutes = lockTimeoutMinutes,
                                hideLockedNotifications = hideLockedNotifications,
                                onHideLockedNotificationsChange = { newValue ->
                                    saveHideLockedNotifications(lockPrefs, newValue)
                                },
                                onLockTimeoutChange = { minutes ->
                                    saveLockTimeoutMinutes(lockPrefs, minutes)
                                },
                                onLockedAppsChange = { newSet ->
                                    saveLockedApps(lockPrefs, newSet)
                                },
                                onBack = {
                                    showLockedApps = false
                                    showSettings = false
                                    showSecurity = false
                                }
                            )
                        }

                        showFavoriteApps -> {
                            FavoriteAppsScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,   // add this line
                                apps = visibleApps,
                                favoriteApps = favoriteAppsPkgs,
                                onFavoriteAppsChange = { favoriteAppsPkgs = it.take(6).toSet() },
                                onBack = {
                                    showFavoriteApps = false
                                    showSettings = false
                                }
                            )
                        }

                        showBatteryAllowedApps -> {
                            BatteryAllowedAppsRoot(
                                themeColor = themeColor,
                                isDark = isDark,
                                allApps = allApps,
                                batteryMode = batteryMode,
                                onBack = {
                                    showBatteryAllowedApps = false
                                    showSettings = true
                                }
                            )
                        }

                        showSecurity -> {
                            SecurityScreen(
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                onBackToDashboard = {
                                    showSecurity = false
                                },
                                onSetAppPin = { pin, _ ->
                                    appPin = pin
                                },
                                onSetPhonePin = { _, _ -> },
                                onOpenLockedApps = {
                                    showSecurity = false
                                    showLockedApps = true
                                },
                                onOpenHiddenApps = {
                                    showSecurity = false
                                    showHiddenApps = true
                                },
                                onOpenFavoriteApps = {
                                    showSecurity = false
                                    showFavoriteApps = true
                                }
                            )
                        }

                        else -> {
                            DashboardScreen(
                                modifier = Modifier.fillMaxSize(),
                                themeColor = themeColor,
                                isDark = isDark,
                                batteryMode = batteryMode,
                                currentThemeIndex = themeIndex,
                                onBackToWelcome = { showWelcome = true },
                                onOpenApps = {
                                    showApps = true
                                    showWelcome = false
                                    showRecents = false
                                    showSettings = false
                                    showSecurity = false
                                    showLockedApps = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                onResetLauncher = {
                                    showWelcome = true
                                    showApps = false
                                    showRecents = false
                                    showSettings = false
                                    showSecurity = false
                                    showLockedApps = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                    isPageMode = false
                                    recentApps = emptyList()
                                    appPin = null
                                    hiddenApps = emptySet()
                                    favoriteAppsPkgs = emptySet()
                                },
                                onOpenSettings = {
                                    showSettings = true
                                    showApps = false
                                    showRecents = false
                                    showWelcome = false
                                    showSecurity = false
                                    showLockedApps = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                onOpenSecurity = {
                                    showSecurity = true
                                    showApps = false
                                    showRecents = false
                                    showWelcome = false
                                    showSettings = false
                                    showLockedApps = false
                                    showHiddenApps = false
                                    showFavoriteApps = false
                                    showBatteryAllowedApps = false
                                },
                                favoriteApps = favoriteApps
                            )
                        }
                    }

                    // PIN CHECK DIALOG FOR LOCKED APPS
                    if (askForPinForLaunch && pendingLaunchPkg != null) {
                        PinCheckDialog(
                            themeColor = themeColor,
                            onDismiss = {
                                askForPinForLaunch = false
                                pendingLaunchPkg = null
                            },
                            onSuccess = { enteredPin ->
                                if (enteredPin == appPin) {
                                    val pkg = pendingLaunchPkg
                                    askForPinForLaunch = false
                                    pendingLaunchPkg = null
                                    if (pkg != null) {
                                        val pmLocal: PackageManager = packageManager
                                        recordUnlock(lockPrefs)
                                        val launchIntent = pmLocal.getLaunchIntentForPackage(pkg)
                                        if (launchIntent != null) {
                                            startActivity(launchIntent)
                                            recordLastOpened(lastOpenedPrefs, pkg)
                                        }
                                    }
                                }
                            }
                        )
                    }

                    // ELENE FLOATING CIRCLE + CHAT
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .offset(bubbleX.dp, bubbleY.dp)
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF101010).copy(alpha = 0.8f))
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        bubbleX += dragAmount.x / resources.displayMetrics.density
                                        bubbleY += dragAmount.y / resources.displayMetrics.density
                                    }
                                }
                                .clickable {
                                    isEleneChatVisible = !isEleneChatVisible
                                    if (isEleneChatVisible) {
                                        speak("I'm here.")
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawCircle(
                                    color = themeColor,
                                    style = Stroke(width = 4.dp.toPx())
                                )
                                drawCircle(color = Color.Transparent)
                            }
                        }

                        if (isEleneChatVisible) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color(0xFF101010).copy(alpha = 0.9f))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = "Elene chat",
                                        color = themeColor
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextField(
                                            value = eleneText,
                                            onValueChange = { eleneText = it },
                                            modifier = Modifier.weight(1f),
                                            placeholder = { Text("Talk to Elene...") }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Button(onClick = {
                                            val text = eleneText.text.trim()
                                            if (text.isNotBlank()) {
                                                onUserText(text)
                                                eleneText = TextFieldValue("")
                                            }
                                        }) {
                                            Text("Send")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val pm: PackageManager = packageManager
        allAppsState = loadAllApps(pm)
    }

    private fun loadAllApps(pm: PackageManager): List<AppItem> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        return resolveInfos
            .map {
                AppItem(
                    label = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    iconBitmap = it.activityInfo.loadIcon(pm).toBitmap()
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun launchApp(
        pkg: String,
        apps: List<AppItem>,
        pm: PackageManager,
        maxRecents: Int,
        lastOpenedPrefs: SharedPreferences,
        updateRecents: (List<AppItem>) -> Unit
    ) {
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            startActivity(launchIntent)
            recordLastOpened(lastOpenedPrefs, pkg)

            val clickedApp = apps.firstOrNull { it.packageName == pkg }
            if (clickedApp != null) {
                val recents = listOf(clickedApp)
                updateRecents(recents.take(maxRecents))
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val themePrefs = getSharedPreferences("theme_prefs", MODE_PRIVATE)
            val lang = loadLanguage(themePrefs)
            val locale = when (lang) {
                LanguageOption.ENGLISH -> Locale.UK
                LanguageOption.YORUBA -> Locale("yo")
                LanguageOption.MANDARIN -> Locale.SIMPLIFIED_CHINESE
                LanguageOption.KOREAN -> Locale.KOREAN
                LanguageOption.FRENCH -> Locale.FRENCH
                LanguageOption.SPANISH -> Locale("es")
                LanguageOption.GERMAN -> Locale.GERMAN
            }
            tts?.language = locale
            tts?.setSpeechRate(1.3f)
            tts?.setPitch(1.1f)
        }
    }

    private fun speakWelcome(isRed: Boolean) {
        val lang = currentLanguage()
        val text = if (isRed) {
            elenePhrase("welcome_red", lang)
        } else {
            elenePhrase("welcome_normal", lang)
        }
        speak(text)
    }
    private fun speak(text: String) {
        // Read current battery mode
        val batteryPrefs = getSharedPreferences("battery_prefs", MODE_PRIVATE)
        val mode = loadBatterySaverMode(batteryPrefs)

        // Balanced + Aggressive → no voice
        if (mode != BatterySaverMode.OFF) return

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VOICE_ID")
    }

    override fun onDestroy() {
        unregisterReceiver(notificationVoiceReceiver)
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // =====================
    //  ELENE ENTRY POINT
    // =====================

    fun onUserText(text: String) {
        val normalized = text.trim()
        val lower = normalized.lowercase(Locale.getDefault())

        if (lower == "hey elene") {
            val lang = currentLanguage()
            speak(elenePhrase("hey_elene_prompt", lang))
            return
        }

        if (lower.startsWith("elene")) {
            val afterName = normalized.drop(5).trim()
            val command = if (afterName.isBlank()) "let's talk" else afterName
            handleAssistantCommand(command)
        } else {
            handleAssistantCommand(normalized)
        }
    }

    fun handleAssistantCommand(input: String) {
        handleAssistantCommandLocal(input)
    }

    private fun handleAssistantCommandLocal(input: String) {
        val normalized = input.lowercase(Locale.getDefault())
        val missed = XenosNotificationListener.missedNotifications
        val msg = XenosNotificationListener.lastMessageInfo

        // DEV reset
        if (normalized == "reset notifications") {
            XenosNotificationListener.missedNotifications.clear()
            XenosNotificationListener.lastMessageInfo = null
            XenosNotificationListener.hasUnreadMessage = false
            speak("I cleared your missed notifications history.")
            return
        }

        // waiting for reply text → send direct reply
        if (waitingForReplyText &&
            replyTargetKey != null &&
            replyTargetAppName != null &&
            replyTargetTitle != null
        ) {
            val key = replyTargetKey!!
            val service = XenosNotificationListener.instance
            val ok = service?.sendDirectReply(key, input) ?: false
            if (ok) {
                speak("Okay. I replied to $replyTargetTitle on $replyTargetAppName.")
            } else {
                speak("I tried to reply, but this notification does not support direct reply or the listener is not connected.")
            }
            waitingForReplyText = false
            replyTargetKey = null
            replyTargetTitle = null
            replyTargetAppName = null
            return
        }

        // yes/no after "hey elene"
        if (normalized == "yes" || normalized == "yeah" || normalized == "yep") {
            if (!readingAllMissed && missed.isNotEmpty()) {
                readingAllMissed = true
                currentReadIndex = 0
                speak("Okay. I will read your missed notifications.")
                readNextMissed()
                return
            }
        } else if (normalized == "no" || normalized == "nah") {
            if (!readingAllMissed) {
                speak("Okay. I won't check missed notifications right now.")
                return
            }
        }

        // read again
        if (normalized.contains("read again") && missed.isNotEmpty()) {
            readingAllMissed = true
            currentReadIndex = 0
            speak("Restarting the summary of your missed notifications.")
            readNextMissed()
            return
        }

        // summary
        if (normalized.contains("give summary") ||
            normalized.contains("notification summary") ||
            normalized.contains("summary of notifications")
        ) {
            if (missed.isEmpty()) {
                speak("You don't have any missed notifications right now.")
                return
            }

            val byApp = missed.groupBy { it.appName }
            val parts = byApp.map { (app, list) -> "${list.size} from $app" }
            val summary = parts.joinToString(", ")
            speak("You have ${missed.size} missed notifications: $summary.")
            return
        }

        // "reply" command
        if (normalized == "reply" || normalized.startsWith("reply ")) {
            if (missed.isEmpty()) {
                speak("There is no recent notification to reply to.")
                return
            }
            askingWhichToReply = true
            askingWhichPerson = false
            waitingForReplyText = false
            replyTargetKey = null
            replyTargetTitle = null
            replyTargetAppName = null
            pendingAppForReply = null

            val words = normalized.split(" ")
            val possibleApp = words.lastOrNull()
            if (possibleApp != null && possibleApp != "reply" && possibleApp != "to") {
                pendingAppForReply = possibleApp
            }

            val byApp = missed.groupBy { it.appName }
            val appNames = byApp.keys.joinToString(", ")
            val lang = currentLanguage()
            if (pendingAppForReply == null) {
                speak(elenePhrase("ask_which_app_general", lang, appNames))
            } else {
                speak(elenePhrase("ask_which_app_retry", lang))
            }
            return
        }

        // app selection
        if (askingWhichToReply) {
            val selection = if (pendingAppForReply != null) pendingAppForReply!! else normalized
            val selectionLower = selection.lowercase(Locale.ROOT)

            val replyables = XenosNotificationListener.replyableMap.values
            val forThisApp = replyables.filter {
                it.appName.lowercase(Locale.ROOT).contains(selectionLower) ||
                        it.packageName.lowercase(Locale.ROOT).contains(selectionLower)
            }

            if (forThisApp.isEmpty()) {
                val lang = currentLanguage()
                speak(elenePhrase("no_replyable_for_app", lang))
                pendingAppForReply = null
                askingWhichToReply = false
                return
            }

            askingWhichToReply = false
            pendingAppForReply = selectionLower

            if (forThisApp.size == 1) {
                val chosen = forThisApp.first()
                replyTargetKey = chosen.key
                replyTargetTitle = chosen.title.ifBlank { "the chat" }
                replyTargetAppName = chosen.appName
                waitingForReplyText = true
                val lang = currentLanguage()
                speak(elenePhrase("ask_message_text", lang, replyTargetTitle ?: "the chat", chosen.appName))
            } else {
                askingWhichPerson = true
                val names = forThisApp.map { it.title.ifBlank { "a chat" } }.distinct()
                val listNames = names.joinToString(", ")
                val lang = currentLanguage()
                speak(elenePhrase("multiple_people_prompt", lang, listNames))
            }
            return
        }

        // person selection
        if (askingWhichPerson && pendingAppForReply != null) {
            val selectionLower = normalized.lowercase(Locale.ROOT)

            val replyables = XenosNotificationListener.replyableMap.values
            val forThisApp = replyables.filter {
                it.appName.lowercase(Locale.ROOT).contains(pendingAppForReply!!) ||
                        it.packageName.lowercase(Locale.ROOT).contains(pendingAppForReply!!)
            }

            if (forThisApp.isEmpty()) {
                val lang = currentLanguage()
                speak(elenePhrase("lost_notifications_for_app", lang))
                askingWhichPerson = false
                pendingAppForReply = null
                return
            }

            val candidates = forThisApp.filter {
                it.title.lowercase(Locale.ROOT).contains(selectionLower)
            }

            if (candidates.isEmpty()) {
                val names = forThisApp.map { it.title.ifBlank { "a chat" } }.distinct()
                val listNames = names.joinToString(", ")
                val lang = currentLanguage()
                speak(elenePhrase("person_not_found", lang, listNames))
                return
            }

            val chosen = candidates.last()
            askingWhichPerson = false
            waitingForReplyText = true
            replyTargetKey = chosen.key
            replyTargetTitle = chosen.title.ifBlank { "the chat" }
            replyTargetAppName = chosen.appName
            speak("What do you want me to reply to $replyTargetTitle on ${chosen.appName}?")
            return
        }

        // no reply flow → normal notification logic
        if (msg == null || !XenosNotificationListener.hasUnreadMessage) {
            val lang = currentLanguage()
            speak(elenePhrase("im_here", lang))
            return
        }

        val lowerText = msg.text.lowercase(Locale.getDefault())
        val isCallNotification = lowerText.contains("calling") ||
                lowerText.contains("incoming call") ||
                lowerText.contains("is calling you") ||
                lowerText.contains("missed call")

        if (isCallNotification) {
            speak("You had a call from ${msg.title} on ${msg.appName}.")
        } else {
            speak("From ${msg.title} on ${msg.appName}: ${msg.text}")
        }

        XenosNotificationListener.hasUnreadMessage = false
    }

    private fun readNextMissed() {
        val missed = XenosNotificationListener.missedNotifications
        if (currentReadIndex >= missed.size) {
            readingAllMissed = false
            currentReadIndex = 0
            XenosNotificationListener.hasUnreadMessage = false
            val lang = currentLanguage()
            speak(elenePhrase("all_missed_done", lang))
            return
        }
        val item = missed[currentReadIndex]
        currentReadIndex++
        val from = item.title.ifBlank { "someone" }
        val text = item.text.ifBlank { "no content" }
        speak("From $from on ${item.appName}: $text.")
        if (readingAllMissed) {
            readNextMissed()
        }
    }
}
