package com.example.scifilauncher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small always-visible tab that opens a pull-down panel by dragging down (or a tap, as a
 * fallback) - stands in for the system status bar's two independent pull zones (notifications
 * on the left, quick settings on the right) while Kiosk mode has the real ones blocked. */
@Composable
fun PullTab(
    themeColor: Color,
    label: String,
    modifier: Modifier = Modifier,
    corner: RoundedCornerShape,
    onOpen: () -> Unit
) {
    Box(
        modifier = modifier
            .size(width = 68.dp, height = 24.dp)
            .clip(corner)
            .background(themeColor.copy(alpha = 0.35f))
            .clickable(onClick = onOpen)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount > 6f) onOpen()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = themeColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun NotificationBarTab(
    themeColor: Color,
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    PullTab(
        themeColor = themeColor,
        label = if (unreadCount > 0) "▤ $unreadCount" else "▤",
        modifier = modifier,
        corner = RoundedCornerShape(bottomEnd = 12.dp),
        onOpen = onClick
    )
}

@Composable
fun QuickSettingsTab(
    themeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    PullTab(
        themeColor = themeColor,
        label = "⚙",
        modifier = modifier,
        corner = RoundedCornerShape(bottomStart = 12.dp),
        onOpen = onClick
    )
}

data class QuickToggleState(
    val airplaneModeOn: Boolean,
    val flashlightOn: Boolean,
    val bluetoothOn: Boolean
)

enum class VolumeModeOption { RING, VIBRATE, SILENT }

data class ExtraQuickToggleState(
    val volumeMode: VolumeModeOption,
    val locationOn: Boolean,
    val dndOn: Boolean,
    val eyeComfortOn: Boolean,
    val screenRecording: Boolean,
    val brightnessPercent: Int
)

/** Bundled callbacks for the extra controls grid - kept as one data class rather than 16
 * separate QuickSettingsPanel parameters. Most of these open real Android settings screens
 * (Android has no public API for them - same reasoning as airplane mode elsewhere in this
 * file: a fake toggle that claims to work is worse than a deep link that's honest about it).
 * Real, working toggles: volume mode, location, DND, eye comfort, screen record, brightness. */
data class ExtraControlActions(
    val onCycleVolume: () -> Unit,
    val onOpenMobileData: () -> Unit,
    val onOpenHotspot: () -> Unit,
    val onOpenPowerSaving: () -> Unit,
    val onToggleLocation: () -> Unit,
    val onOpenLaptopLink: () -> Unit,
    val onToggleScreenRecord: () -> Unit,
    val onToggleEyeComfort: () -> Unit,
    val onToggleDnd: () -> Unit,
    val onOpenWifiCalling: () -> Unit,
    val onScanQr: () -> Unit,
    val onOpenMultiControl: () -> Unit,
    val onOpenSecureFolder: () -> Unit,
    val onOpenAudioBroadcast: () -> Unit,
    val onBrightnessChange: (Int) -> Unit,
    val onOpenNearbyDevices: () -> Unit
)

/** Shared drag-to-close + scrim wrapper - drag up beyond the threshold, or tap the scrim,
 * closes the panel. No explicit close button; the gesture is the whole point. */
@Composable
private fun PullDownPanel(
    isDark: Boolean,
    align: Alignment,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onClose)
    ) {
        Column(
            modifier = Modifier
                .align(align)
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                .background(if (isDark) Color(0xFF060911) else Color(0xFFEFEFEF))
                .clickable(enabled = false) {}
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount < -6f) onClose()
                    }
                }
                .systemBarsPadding()
                .padding(18.dp),
            content = content
        )
    }
}

@Composable
private fun DragHandle(themeColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(themeColor.copy(alpha = 0.4f))
        )
    }
}

@Composable
fun NotificationsPanel(
    themeColor: Color,
    isDark: Boolean,
    listenerEnabled: Boolean,
    onEnableListener: () -> Unit,
    notifications: List<LastMessageInfo>,
    onOpen: (LastMessageInfo) -> Unit,
    onDismiss: (LastMessageInfo) -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit
) {
    PullDownPanel(isDark = isDark, align = Alignment.TopStart, onClose = onClose) {
        DragHandle(themeColor)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "NOTIFICATIONS",
                color = themeColor,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (notifications.isNotEmpty()) {
                Text(
                    text = "CLEAR ALL",
                    color = themeColor.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable(onClick = onClearAll)
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        if (!listenerEnabled) {
            NotificationAccessPrompt(themeColor = themeColor, isDark = isDark, onEnableListener = onEnableListener)
        } else if (notifications.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                ScanningLoadingLine(themeColor = themeColor)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "No notifications.",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            // Same-app notifications collapse into one group (latest on top) instead of
            // flooding the list one-by-one - "SEE ALL" expands it, "SEE LESS" collapses it
            // back, and dismissing a collapsed group's row clears everything in it at once.
            val groups = remember(notifications) {
                notifications.groupBy { it.packageName }.map { (pkg, items) ->
                    NotificationGroup(appName = items.first().appName, packageName = pkg, items = items)
                }
            }
            val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(groups, key = { it.packageName }) { group ->
                    val expanded = group.items.size == 1 || expandedGroups[group.packageName] == true
                    if (expanded) {
                        group.items.forEach { item ->
                            NotificationRow(
                                item = item,
                                themeColor = themeColor,
                                isDark = isDark,
                                onOpen = { onOpen(item) },
                                onDismiss = { onDismiss(item) }
                            )
                        }
                        if (group.items.size > 1) {
                            GroupToggleLink("SEE LESS", themeColor) {
                                expandedGroups[group.packageName] = false
                            }
                        }
                    } else {
                        NotificationRow(
                            item = group.items.first(),
                            themeColor = themeColor,
                            isDark = isDark,
                            onOpen = { onOpen(group.items.first()) },
                            onDismiss = { group.items.forEach { onDismiss(it) } }
                        )
                        GroupToggleLink("SEE ALL (${group.items.size})", themeColor) {
                            expandedGroups[group.packageName] = true
                        }
                    }
                }
            }
        }
    }
}

private data class NotificationGroup(
    val appName: String,
    val packageName: String,
    val items: List<LastMessageInfo>
)

@Composable
private fun GroupToggleLink(label: String, themeColor: Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = themeColor.copy(alpha = 0.75f),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp)
    )
}

/** A row of block characters with a bright segment sweeping across on a loop - a terminal-
 * style "scanning, nothing found yet" animation instead of a static decorative line. */
@Composable
private fun ScanningLoadingLine(themeColor: Color) {
    val transition = rememberInfiniteTransition(label = "scan-line")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan-line-phase"
    )

    val totalChars = 22
    val brightWidth = 5
    val brightCenter = phase * (totalChars + brightWidth) - brightWidth

    Row {
        for (i in 0 until totalChars) {
            val distance = kotlin.math.abs(i - brightCenter)
            val alpha = (1f - (distance / brightWidth)).coerceIn(0.12f, 1f)
            Text(
                text = "░",
                color = themeColor.copy(alpha = alpha),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun NotificationAccessPrompt(themeColor: Color, isDark: Boolean, onEnableListener: () -> Unit) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = "◤ ACCESS REQUIRED",
            color = Color(0xFFFFC107),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "This panel can't see your phone's notifications until Notification " +
                    "Access is granted in Android's own settings - that permission can only " +
                    "be turned on there, not from inside any app.",
            color = if (isDark) Color.White else Color.Black,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onEnableListener,
            colors = ButtonDefaults.buttonColors(containerColor = themeColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("ENABLE NOTIFICATION ACCESS", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun QuickSettingsPanel(
    themeColor: Color,
    isDark: Boolean,
    batteryPercent: Int,
    quickToggles: QuickToggleState,
    extraToggles: ExtraQuickToggleState,
    extraActions: ExtraControlActions,
    onOpenAirplaneModeSettings: () -> Unit,
    onToggleFlashlight: (Boolean) -> Unit,
    onToggleBluetooth: () -> Unit,
    onClose: () -> Unit
) {
    PullDownPanel(isDark = isDark, align = Alignment.TopEnd, onClose = onClose) {
        DragHandle(themeColor)
        Text(
            text = "SETTINGS",
            color = themeColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(20.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
        ) {
        QuickSettingsPanelBody(
            themeColor = themeColor,
            isDark = isDark,
            batteryPercent = batteryPercent,
            quickToggles = quickToggles,
            extraToggles = extraToggles,
            extraActions = extraActions,
            onOpenAirplaneModeSettings = onOpenAirplaneModeSettings,
            onToggleFlashlight = onToggleFlashlight,
            onToggleBluetooth = onToggleBluetooth
        )
        }
    }
}

@Composable
private fun QuickSettingsPanelBody(
    themeColor: Color,
    isDark: Boolean,
    batteryPercent: Int,
    quickToggles: QuickToggleState,
    extraToggles: ExtraQuickToggleState,
    extraActions: ExtraControlActions,
    onOpenAirplaneModeSettings: () -> Unit,
    onToggleFlashlight: (Boolean) -> Unit,
    onToggleBluetooth: () -> Unit
) {
    Column {
        // Radial battery gauge - system status at a glance, not just a number.
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadialGauge(
                percent = batteryPercent,
                color = batteryLevelColor(batteryPercent),
                size = 64.dp
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = "BATTERY",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "$batteryPercent%",
                    color = batteryLevelColor(batteryPercent),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "QUICK TOGGLES",
            color = Color.Gray,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            RingToggle(
                glyph = "✈",
                label = "AIRPLANE",
                active = quickToggles.airplaneModeOn,
                themeColor = themeColor,
                isDark = isDark,
                onClick = onOpenAirplaneModeSettings
            )
            RingToggle(
                glyph = "⚡",
                label = "TORCH",
                active = quickToggles.flashlightOn,
                themeColor = themeColor,
                isDark = isDark,
                onClick = { onToggleFlashlight(!quickToggles.flashlightOn) }
            )
            RingToggle(
                glyph = "◈",
                label = "BLUETOOTH",
                active = quickToggles.bluetoothOn,
                themeColor = themeColor,
                isDark = isDark,
                onClick = onToggleBluetooth
            )
        }

        Spacer(Modifier.height(26.dp))
        BrightnessSliderRow(
            themeColor = themeColor,
            percent = extraToggles.brightnessPercent,
            onChange = extraActions.onBrightnessChange
        )

        Spacer(Modifier.height(26.dp))
        Text(
            text = "MORE CONTROLS",
            color = Color.Gray,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(10.dp))

        val volumeGlyph = when (extraToggles.volumeMode) {
            VolumeModeOption.RING -> "♪"
            VolumeModeOption.VIBRATE -> "≋"
            VolumeModeOption.SILENT -> "✕"
        }
        val volumeLabel = when (extraToggles.volumeMode) {
            VolumeModeOption.RING -> "RING"
            VolumeModeOption.VIBRATE -> "VIBRATE"
            VolumeModeOption.SILENT -> "SILENT"
        }

        val tiles = listOf(
            ControlTileSpec(volumeGlyph, volumeLabel, extraToggles.volumeMode != VolumeModeOption.RING, extraActions.onCycleVolume),
            ControlTileSpec("4G", "MOBILE DATA", false, extraActions.onOpenMobileData),
            ControlTileSpec("((•))", "HOTSPOT", false, extraActions.onOpenHotspot),
            ControlTileSpec("⛶", "POWER SAVING", false, extraActions.onOpenPowerSaving),
            ControlTileSpec("⊙", "LOCATION", extraToggles.locationOn, extraActions.onToggleLocation),
            ControlTileSpec("☾", "DND", extraToggles.dndOn, extraActions.onToggleDnd),
            ControlTileSpec("◐", "EYE COMFORT", extraToggles.eyeComfortOn, extraActions.onToggleEyeComfort),
            ControlTileSpec("⏺", "SCREEN RECORD", extraToggles.screenRecording, extraActions.onToggleScreenRecord),
            ControlTileSpec("▦", "SCAN QR", false, extraActions.onScanQr),
            ControlTileSpec("⌖", "NEARBY DEVICES", false, extraActions.onOpenNearbyDevices),
            ControlTileSpec("PC", "LINK TO LAPTOP", false, extraActions.onOpenLaptopLink),
            ControlTileSpec("⧉", "MULTI CONTROL", false, extraActions.onOpenMultiControl),
            ControlTileSpec("🔒", "SECURE FOLDER", false, extraActions.onOpenSecureFolder),
            ControlTileSpec(")))", "AUDIO BROADCAST", false, extraActions.onOpenAudioBroadcast),
            ControlTileSpec("WFC", "WIFI CALLING", false, extraActions.onOpenWifiCalling)
        )

        tiles.chunked(4).forEach { rowTiles ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowTiles.forEach { spec ->
                    IconTile(
                        glyph = spec.glyph,
                        label = spec.label,
                        active = spec.active,
                        themeColor = themeColor,
                        isDark = isDark,
                        onClick = spec.onClick,
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(4 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

private data class ControlTileSpec(
    val glyph: String,
    val label: String,
    val active: Boolean,
    val onClick: () -> Unit
)

/** Square-tile take on the same toggle idea as RingToggle - a distinct shape for the
 * "more controls" grid so it doesn't just look like a longer row of the same circles. */
@Composable
private fun IconTile(
    glyph: String,
    label: String,
    active: Boolean,
    themeColor: Color,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) themeColor.copy(alpha = 0.22f) else Color.Gray.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (active) themeColor else Color.Gray.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = glyph,
                color = if (active) Color.Black else if (isDark) Color.White else Color.Black,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = if (active) themeColor else Color.Gray,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
private fun BrightnessSliderRow(themeColor: Color, percent: Int, onChange: (Int) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("☀ BRIGHTNESS", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("$percent%", color = themeColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 1f..100f,
            colors = SliderDefaults.colors(
                thumbColor = themeColor,
                activeTrackColor = themeColor,
                inactiveTrackColor = Color.Gray.copy(alpha = 0.25f)
            )
        )
    }
}

/** A small circular gauge (like a mini pie/radial chart) rather than a flat bar - used for
 * the battery readout so the settings panel actually looks designed. */
@Composable
private fun RadialGauge(percent: Int, color: Color, size: androidx.compose.ui.unit.Dp) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = Stroke(width = size.toPx() * 0.14f, cap = StrokeCap.Round)
            drawArc(
                color = color.copy(alpha = 0.18f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
                size = Size(size.toPx() - stroke.width, size.toPx() - stroke.width),
                topLeft = androidx.compose.ui.geometry.Offset(stroke.width / 2, stroke.width / 2)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * (percent / 100f),
                useCenter = false,
                style = stroke,
                size = Size(size.toPx() - stroke.width, size.toPx() - stroke.width),
                topLeft = androidx.compose.ui.geometry.Offset(stroke.width / 2, stroke.width / 2)
            )
        }
        Text(
            text = "$percent",
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** Toggle rendered as a ring rather than a flat chip - fills in when active. */
@Composable
private fun RingToggle(
    glyph: String,
    label: String,
    active: Boolean,
    themeColor: Color,
    isDark: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (active) themeColor else Color.Gray.copy(alpha = 0.15f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = glyph,
                color = if (active) Color.Black else if (isDark) Color.White else Color.Black,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = if (active) themeColor else Color.Gray,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun NotificationRow(
    item: LastMessageInfo,
    themeColor: Color,
    isDark: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${item.appName}${if (item.title.isNotBlank()) " - ${item.title}" else ""}",
                color = themeColor,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = item.text,
                color = if (isDark) Color.White else Color.Black,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2
            )
        }
        Text(
            text = "✕",
            color = Color.Gray,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clickable(onClick = onDismiss)
                .padding(start = 12.dp)
        )
    }
}
