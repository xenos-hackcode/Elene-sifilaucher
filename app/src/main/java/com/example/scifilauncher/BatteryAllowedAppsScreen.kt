package com.example.scifilauncher

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BatteryAllowedAppsRoot(
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    allApps: List<AppItem>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("battery_prefs", Context.MODE_PRIVATE)
    }

    var allowedApps by rememberSaveable {
        mutableStateOf(loadBatteryAllowedApps(prefs))
    }

    BatteryAllowedAppsScreen(
        themeColor = themeColor,
        isDark = isDark,
        apps = allApps,
        allowedApps = allowedApps,
        batteryMode = batteryMode,
        onAllowedAppsChange = { newSet ->
            allowedApps = newSet
            saveBatteryAllowedApps(prefs, newSet)
        },
        onBack = onBack
    )
}

@Composable
fun BatteryAllowedAppsScreen(
    themeColor: Color,
    isDark: Boolean,
    apps: List<AppItem>,
    allowedApps: Set<String>,
    batteryMode: BatterySaverMode,
    onAllowedAppsChange: (Set<String>) -> Unit,
    onBack: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        MatrixBackground(
            themeColor = themeColor,
            isDark = isDark,
            batteryMode = batteryMode
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 40.dp),
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
                text = "BATTERY SAVER ALLOWED APPS",
                color = themeColor,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "When Battery Saver is AGGRESSIVE, only these apps stay visible and usable.",
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
                apps.forEach { app ->
                    val isAllowed = allowedApps.contains(app.packageName)
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
                            checked = isAllowed,
                            onCheckedChange = { checked ->
                                val newSet = allowedApps.toMutableSet()
                                if (checked) newSet.add(app.packageName)
                                else newSet.remove(app.packageName)
                                onAllowedAppsChange(newSet)
                            }
                        )
                    }
                }
            }
        }
    }
}
