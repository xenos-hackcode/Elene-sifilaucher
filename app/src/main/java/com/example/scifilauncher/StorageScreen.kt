package com.example.scifilauncher

import android.content.SharedPreferences
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun formatShortTime(millis: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}

/** Every genuine failed-fingerprint attempt on a device-owner confirmation - photo, location,
 * and when, captured silently the moment a non-matching scan happens (see
 * BiometricAuthActivity.onAuthenticationFailed / IntruderCaptureLog). */
@Composable
fun StorageScreen(
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    lockPrefs: SharedPreferences,
    logs: List<IntruderCapture>,
    onBack: () -> Unit
) {
    val bg = if (isDark) Color(0xFF050505) else Color(0xFFF5F5F5)
    val textColor = if (isDark) Color.White else Color.Black

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
                    text = "No failed fingerprint attempts yet.",
                    color = if (isDark) Color.LightGray else Color.DarkGray,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs) { entry ->
                        IntruderCaptureRow(entry, themeColor, textColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun IntruderCaptureRow(entry: IntruderCapture, themeColor: Color, textColor: Color) {
    var expanded by remember { mutableStateOf(false) }
    val photoBitmap = remember(entry.photoPath) {
        entry.photoPath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (photoBitmap != null) {
                Image(
                    bitmap = photoBitmap.asImageBitmap(),
                    contentDescription = "Captured photo",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.width(10.dp))
            }
            Column {
                Text(
                    text = entry.reason,
                    color = textColor,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = formatShortTime(entry.timestamp),
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (photoBitmap == null) {
                    Text(
                        text = "No photo (camera permission wasn't available)",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        if (expanded && entry.lat != null && entry.lng != null) {
            Text(
                text = "Location: https://maps.google.com/?q=${entry.lat},${entry.lng}",
                color = themeColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else if (expanded) {
            Text(
                text = "Location unavailable",
                color = Color.Gray,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
