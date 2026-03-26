package com.example.scifilauncher

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LockedAppsScreen(
    themeColor: Color,
    isDark: Boolean,
    apps: List<AppItem>,
    lockPrefs: SharedPreferences,
    lockedApps: Set<String>,
    batteryMode: BatterySaverMode,
    lockTimeoutMinutes: Int,
    hideLockedNotifications: Boolean,
    onHideLockedNotificationsChange: (Boolean) -> Unit,
    onLockTimeoutChange: (Int) -> Unit,
    onLockedAppsChange: (Set<String>) -> Unit,
    onBack: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        MatrixBackground(
            themeColor = themeColor,
            isDark = isDark,
            batteryMode = batteryMode
        )

        var timerText by remember { mutableStateOf(lockTimeoutMinutes.toString()) }
        var timerError by remember { mutableStateOf<String?>(null) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "< SETTINGS",
                color = themeColor,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .clickable { onBack() }
            )

            Text(
                text = "LOCKED APPS",
                color = themeColor,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "Set lock timer in minutes. Max 1440 minutes (24h).",
                color = Color.LightGray,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // Timer controls row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "LOCK TIMER (MIN)",
                        color = themeColor,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Column(horizontalAlignment = Alignment.End) {
                        OutlinedTextField(
                            value = timerText,
                            onValueChange = { new ->
                                if (new.all { it.isDigit() } && new.length <= 4) {
                                    timerText = new
                                    timerError = null
                                    val raw = new.toIntOrNull()
                                    if (raw == null) {
                                        timerError = "Invalid number"
                                    } else {
                                        val clamped = raw.coerceIn(1, 1440)
                                        if (raw != clamped) {
                                            timerError = "Max 1440 minutes (24h)"
                                        }
                                        onLockTimeoutChange(clamped)
                                    }
                                }
                            },
                            label = { Text("Minutes (max 1440)") },
                            singleLine = true
                        )

                        if (timerError != null) {
                            Text(
                                text = timerError!!,
                                color = Color.Red,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // Hide notifications toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "HIDE NOTIFICATIONS WHILE LOCKED",
                        color = themeColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Switch(
                        checked = hideLockedNotifications,
                        onCheckedChange = { enabled ->
                            onHideLockedNotificationsChange(enabled)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Locked apps list
                apps.forEach { app ->
                    val isLocked = lockedApps.contains(app.packageName)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = app.label,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Switch(
                            checked = isLocked,
                            onCheckedChange = { checked ->
                                val newSet = lockedApps.toMutableSet()
                                if (checked) newSet.add(app.packageName)
                                else newSet.remove(app.packageName)
                                onLockedAppsChange(newSet)
                            }
                        )
                    }
                }
            }
        }
    }
}
