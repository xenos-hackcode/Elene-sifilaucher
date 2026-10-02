package com.example.scifilauncher

import android.content.SharedPreferences
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
    TerminalScaffold(
        themeColor = themeColor,
        code = "STORAGE",
        title = "Intruder attempts",
        subtitle = "Every failed fingerprint scan on a device-owner confirmation, with photo and location if available.",
        backLabel = "‹  SECURITY",
        onBack = onBack
    ) {
        TerminalCard(label = "CAPTURED ATTEMPTS (${logs.size})", themeColor = themeColor) {
            if (logs.isEmpty()) {
                Text(
                    text = "No failed fingerprint attempts yet.",
                    color = TerminalStyle.muted,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                logs.forEach { entry ->
                    IntruderCaptureRow(entry, themeColor, TerminalStyle.ink)
                }
            }
        }
    }
}

@Composable
private fun IntruderCaptureRow(entry: IntruderCapture, themeColor: Color, textColor: Color) {
    var expanded by remember { mutableStateOf(false) }
    var fullscreenPhoto by remember { mutableStateOf(false) }
    val photoBitmap = remember(entry.photoPath) {
        entry.photoPath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (photoBitmap != null) {
                Image(
                    bitmap = photoBitmap.asImageBitmap(),
                    contentDescription = "Captured photo - tap to view full size",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { fullscreenPhoto = true }
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.clickable { expanded = !expanded }) {
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

    if (fullscreenPhoto && photoBitmap != null) {
        Dialog(
            onDismissRequest = { fullscreenPhoto = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { fullscreenPhoto = false },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = photoBitmap.asImageBitmap(),
                    contentDescription = "Captured photo, full size",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
