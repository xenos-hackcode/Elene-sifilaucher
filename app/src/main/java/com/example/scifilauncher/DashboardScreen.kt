package com.example.scifilauncher

import android.bluetooth.BluetoothAdapter
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    themeColor: Color,
    isDark: Boolean,                // <-- added
    currentThemeIndex: Int,
    batteryMode: BatterySaverMode,
    onBackToWelcome: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenRecents: () -> Unit,
    onResetLauncher: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSecurity: () -> Unit,
    favoriteApps: List<AppItem>
) {
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }

    // TIME + DATE
    var timeText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }

    val timePrefs = remember {
        context.getSharedPreferences("time_prefs", android.content.Context.MODE_PRIVATE)
    }
    var timeFormatOption by remember { mutableStateOf(loadTimeFormat(timePrefs)) }

    LaunchedEffect(Unit) {
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        while (true) {
            timeFormatOption = loadTimeFormat(timePrefs)

            val pattern =
                if (timeFormatOption == TimeFormatOption.FORMAT_24H) "HH:mm" else "hh:mm a"
            val timeFormat = SimpleDateFormat(pattern, Locale.getDefault())

            val now = Date()
            timeText = runCatching { timeFormat.format(now) }.getOrElse { "--:--" }
            dateText = runCatching { dateFormat.format(now) }.getOrElse { "-- -- ----" }

            delay(60_000L)
        }
    }

    // WEATHER placeholder
    var weatherText by remember { mutableStateOf("--°C CLEAR") }

    // RADIO / SYSTEM STATE
    val wifiManager = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)
    }.getOrNull()
    val isWifiOn = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)

    val btAdapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
    val isBtOn = runCatching { btAdapter?.isEnabled == true }.getOrDefault(false)

    val isAirplaneModeOn = runCatching {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0
        ) == 1
    }.getOrDefault(false)

    val hotspotStatus = "OFF"

    Box(
        modifier = modifier.fillMaxSize()
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // TOP HALF
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // LEFT SECURITY SHORTCUT
                LeftControlPill(
                    themeColor = themeColor,
                    onOpenSecurity = onOpenSecurity
                )

                // RIGHT: HUD + text
                Column(
                    modifier = Modifier
                        .padding(top = 72.dp, end = 8.dp)
                        .width(180.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    HudCircle(
                        themeColor = themeColor,
                        size = 140.dp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = if (timeText.isEmpty()) "--:--" else timeText,
                            color = themeColor,
                            fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "WiFi: " + if (isWifiOn) "ON" else "OFF",
                            color = themeColor.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Bluetooth: " + if (isBtOn) "ON" else "OFF",
                            color = themeColor.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Hotspot: $hotspotStatus",
                            color = themeColor.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Flight mode: " + if (isAirplaneModeOn) "ON" else "OFF",
                            color = themeColor.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Date: " + if (dateText.isEmpty()) "-- -- ----" else dateText,
                            color = themeColor.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Weather: $weatherText",
                            color = themeColor.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // FAVORITES + DOCK AREA
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.BottomCenter
            ) {
                if (favoriteApps.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(bottom = 96.dp)
                            .wrapContentWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        favoriteApps.take(6).forEach { app ->
                            Image(
                                bitmap = app.iconBitmap.asImageBitmap(),
                                contentDescription = app.label,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable {
                                        val launchIntent = context.packageManager
                                            .getLaunchIntentForPackage(app.packageName)
                                        if (launchIntent != null) {
                                            context.startActivity(launchIntent)
                                        }
                                    }
                            )
                        }
                    }
                }

                // DOCK
                Box(
                    modifier = Modifier
                        .padding(bottom = 72.dp)
                        .width(320.dp)
                        .height(40.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val radius = size.height / 2f
                        drawRoundRect(
                            color = themeColor.copy(alpha = 0.35f),
                            cornerRadius = CornerRadius(radius, radius)
                        )
                    }
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val radius = size.height / 2f
                        drawRoundRect(
                            color = themeColor,
                            cornerRadius = CornerRadius(radius, radius),
                            style = Stroke(width = 3f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // apps grid
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clickable { onOpenApps() }
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height
                                val cw = w / 3f
                                val ch = h / 3f
                                for (row in 0..2) {
                                    for (col in 0..2) {
                                        drawCircle(
                                            color = themeColor,
                                            radius = 2.5f,
                                            center = Offset(
                                                cw * (col + 0.5f),
                                                ch * (row + 0.5f)
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // restart
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clickable { showResetDialog = true }
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val radius = size.minDimension / 2.4f
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                drawArc(
                                    color = themeColor,
                                    startAngle = 200f,
                                    sweepAngle = 260f,
                                    useCenter = false,
                                    topLeft = Offset(cx - radius, cy - radius),
                                    size = Size(radius * 2, radius * 2),
                                    style = Stroke(width = 2f)
                                )
                                drawLine(
                                    color = themeColor,
                                    start = Offset(cx + radius * 0.7f, cy - radius * 0.3f),
                                    end = Offset(cx + radius * 0.9f, cy - radius * 0.1f),
                                    strokeWidth = 2f
                                )
                            }
                        }

                        // settings
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clickable { onOpenSettings() }
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val rOuter = size.minDimension / 2.4f
                                val rInner = rOuter * 0.55f

                                drawCircle(
                                    color = themeColor,
                                    radius = rOuter,
                                    style = Stroke(width = 2f)
                                )
                                drawCircle(
                                    color = themeColor,
                                    radius = rInner
                                )

                                val notch = rOuter + 2f
                                drawLine(
                                    themeColor,
                                    Offset(cx, cy - notch),
                                    Offset(cx, cy - notch + 4f),
                                    2f
                                )
                                drawLine(
                                    themeColor,
                                    Offset(cx, cy + notch - 4f),
                                    Offset(cx, cy + notch),
                                    2f
                                )
                                drawLine(
                                    themeColor,
                                    Offset(cx - notch, cy),
                                    Offset(cx - notch + 4f, cy),
                                    2f
                                )
                                drawLine(
                                    themeColor,
                                    Offset(cx + notch - 4f, cy),
                                    Offset(cx + notch, cy),
                                    2f
                                )
                            }
                        }
                    }
                }
            }
        }

        // NOS NAV
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlitchNavLetter(
                letter = "N",
                color = themeColor,
                onClick = { onBackToWelcome() }
            )
            GlitchNavLetter(
                letter = "O",
                color = themeColor,
                onClick = { /* already home */ }
            )
            GlitchNavLetter(
                letter = "S",
                color = themeColor,
                onClick = { onOpenRecents() }
            )
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = {
                    Text(
                        text = "RESET LAUNCHER",
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to reset?\nThis will clear all changes you have made.",
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Text(
                        text = "YES",
                        color = themeColor,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable {
                                showResetDialog = false
                                onResetLauncher()
                            }
                    )
                },
                dismissButton = {
                    Text(
                        text = "NO",
                        color = themeColor.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable {
                                showResetDialog = false
                            }
                    )
                }
            )
        }
    }
}

@Composable
private fun LeftControlPill(
    themeColor: Color,
    onOpenSecurity: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(top = 72.dp)
            .width(56.dp)
            .height(56.dp)
            .clickable { onOpenSecurity() }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val radius = w / 2f

            drawRoundRect(
                color = themeColor.copy(alpha = 0.35f),
                cornerRadius = CornerRadius(radius, radius)
            )
            drawRoundRect(
                color = themeColor,
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = 3f)
            )

            // Shield glyph hinting at the Security shortcut
            val cx = w / 2f
            val cy = h / 2f
            val shieldW = w * 0.34f
            val shieldH = h * 0.4f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, cy - shieldH / 2f)
                lineTo(cx + shieldW / 2f, cy - shieldH / 4f)
                lineTo(cx + shieldW / 2f, cy + shieldH / 6f)
                lineTo(cx, cy + shieldH / 2f)
                lineTo(cx - shieldW / 2f, cy + shieldH / 6f)
                lineTo(cx - shieldW / 2f, cy - shieldH / 4f)
                close()
            }
            drawPath(path, color = themeColor, style = Stroke(width = 2.5f))
        }
    }
}
