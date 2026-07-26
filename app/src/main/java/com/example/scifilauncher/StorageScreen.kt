package com.example.scifilauncher

import android.content.SharedPreferences
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// simple time formatter (you can move to a utils file if you want)
fun formatShortTime(millis: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}

@Composable
fun StorageScreen(
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    lockPrefs: SharedPreferences,
    logs: List<MainActivity.IntruderLog>,
    allApps: List<AppItem>,
    onBack: () -> Unit
){
    val bg = if (isDark) Color(0xFF050505) else Color(0xFFF5F5F5)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        MatrixBackground(
            themeColor = themeColor,
            isDark = isDark,
            batteryMode = batteryMode
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 40.dp)
        ) {
            // Top bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onBack) {
                    Text("< BACK")
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Intruder attempts",
                    color = themeColor,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(16.dp))

            if (logs.isEmpty()) {
                Text(
                    text = "No intruder attempts yet.",
                    color = if (isDark) Color.LightGray else Color.DarkGray
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(logs) { log ->
                        IntruderLogRow(
                            log = log,
                            allApps = allApps,
                            isDark = isDark
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IntruderLogRow(
    log: MainActivity.IntruderLog,
    allApps: List<AppItem>,
    isDark: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    val appItem = remember(log.packageName, allApps) {
        allApps.firstOrNull { it.packageName == log.packageName }
    }
    val iconBitmap = appItem?.iconBitmap

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap.asImageBitmap(),
                    contentDescription = log.appName,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(8.dp))
            }

            Column {
                Text(
                    text = log.appName,
                    color = if (isDark) Color.White else Color.Black,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Clicked ${log.count} times",
                    color = if (isDark) Color.LightGray else Color.DarkGray,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Last: ${formatShortTime(log.lastTime)}",
                    color = if (isDark) Color.LightGray else Color.DarkGray,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            log.allTimes.forEach { t ->
                Text(
                    text = "• ${formatShortTime(t)}",
                    color = if (isDark) Color.Gray else Color.DarkGray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}