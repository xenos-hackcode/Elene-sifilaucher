package com.example.scifilauncher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings gallery. Browsing never applies a wallpaper or starts a sensor. */
@Composable
fun WallpaperScreen(themeColor: Color, isDark: Boolean, batteryMode: BatterySaverMode, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var appliedId by remember { mutableStateOf(WallpaperStore.selectedId(context)) }
    var previewId by rememberSaveable { mutableStateOf(appliedId) }
    val option = WALLPAPER_CATALOG.firstOrNull { it.id == previewId } ?: WALLPAPER_CATALOG.first()
    var category by rememberSaveable { mutableStateOf(option.category) }
    var playing by remember(previewId) { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var permissionFor by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && permissionFor == previewId) playing = true
        else if (!granted) Toast.makeText(context, "Permission is needed for this interactive preview.", Toast.LENGTH_SHORT).show()
        permissionFor = null
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, previewId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playing = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val entries = WALLPAPER_CATALOG.filter { it.category == category }
    val isVideo = option.id in WallpaperExporter.ANIMATED_IDS
    val exportable = WallpaperExporter.canExport(option.id) && Build.VERSION.SDK_INT >= 29

    TerminalScaffold(themeColor, "VISUAL SYSTEM", "Wallpaper",
        "Source code, circuit traces and motion. Your launcher, your signature.",
        "<  SETTINGS", onBack) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WallpaperCategory.values().forEach { tab ->
                val active = category == tab
                Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (active) themeColor.copy(alpha = 0.16f) else TerminalStyle.cardBackground())
                    .border(1.dp, if (active) themeColor else TerminalStyle.buttonBorder, RoundedCornerShape(10.dp))
                    .selectable(active, role = Role.Tab) {
                        category = tab
                        previewId = WALLPAPER_CATALOG.first { it.category == tab }.id
                        playing = false
                    }.heightIn(min = 56.dp).padding(8.dp), contentAlignment = Alignment.Center) {
                    Text(tab.name, color = if (active) themeColor else TerminalStyle.muted,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
        TerminalCard(label = "01 / PREVIEW", themeColor = themeColor) {
            Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF03090F))
                .border(1.dp, themeColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxHeight().aspectRatio(9f / 16f)) {
                    key(previewId) {
                        if (playing) WallpaperRenderer(option.id, themeColor, isDark, batteryMode)
                        else WallpaperThumbnailRenderer(option.id, themeColor, isDark, batteryMode)
                    }
                }
                Text(if (playing) "PREVIEW / ACTIVE" else "STILL / PREVIEW",
                    Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.8f)).padding(10.dp),
                    color = themeColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(16.dp))
            Text(option.label, color = TerminalStyle.ink, fontSize = 20.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            val detail = when {
                option.id == "voice" -> "Microphone-reactive. Start preview to enable audio input."
                option.id in listOf("welcome_eye", "xenos_face") -> "Camera-reactive. Start preview to enable the camera."
                option.id.startsWith("globe") -> "Opens the globe viewer. The gallery shows a placeholder."
                option.category == WallpaperCategory.LIVE -> "Interactive launcher background. XENOS stays visible as the scene responds."
                option.category == WallpaperCategory.VIDEO -> "Animated launcher background with a persistent XENOS signature."
                else -> "A still design with code-inspired detail and the XENOS signature."
            }
            Text(detail, color = TerminalStyle.muted, fontSize = 12.sp)
            if (option.category != WallpaperCategory.IMAGE || option.id.startsWith("globe")) {
                OutlinedButton(onClick = {
                    if (playing) playing = false
                    else {
                        val required = when (option.id) {
                            "voice" -> Manifest.permission.RECORD_AUDIO
                            "welcome_eye", "xenos_face" -> Manifest.permission.CAMERA
                            else -> null
                        }
                        if (required != null && ContextCompat.checkSelfPermission(context, required) != PackageManager.PERMISSION_GRANTED) {
                            permissionFor = previewId
                            permission.launch(required)
                        } else playing = true
                    }
                }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp)) {
                    Text(if (playing) "STOP PREVIEW" else "START PREVIEW", color = themeColor)
                }
            }
            Button(onClick = {
                WallpaperStore.setSelected(context, option.id)
                appliedId = option.id
                Toast.makeText(context, "Wallpaper applied to your launcher", Toast.LENGTH_SHORT).show()
            }, enabled = option.id != appliedId,
                colors = ButtonDefaults.buttonColors(containerColor = themeColor, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) {
                Text(if (option.id == appliedId) "APPLIED TO LAUNCHER" else "APPLY TO LAUNCHER")
            }
            if (exportable) {
                OutlinedButton(onClick = {
                    val chosen = option
                    exporting = true
                    scope.launch {
                        try {
                            val ok = withContext(Dispatchers.IO) {
                                if (isVideo) WallpaperVideoExporter.exportMp4(context, chosen.id, chosen.label, themeColor.toArgb())
                                else WallpaperExporter.exportPng(context, chosen.id, chosen.label, themeColor.toArgb())
                            }
                            Toast.makeText(context, if (ok) "Saved to " +
                                (if (isVideo) "Movies" else "Pictures") + "/SciFiLauncher Wallpapers"
                                else "Could not save this wallpaper. Please try again.", Toast.LENGTH_LONG).show()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Toast.makeText(context, "Could not save this wallpaper.", Toast.LENGTH_LONG).show()
                        } finally { exporting = false }
                    }
                }, enabled = !exporting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (exporting) "SAVING..." else if (isVideo) "SAVE MP4" else "SAVE PNG", color = themeColor)
                }
            } else {
                Text(if (Build.VERSION.SDK_INT < 29) "Saving files requires Android 10 or later."
                    else "This interactive scene is available in the launcher; file export is unavailable.",
                    Modifier.padding(top = 10.dp), color = TerminalStyle.muted, fontSize = 12.sp)
            }
        }
        TerminalCard(label = "02 / " + category.name + " COLLECTION", themeColor = themeColor) {
            entries.forEach { item ->
                val focused = item.id == previewId
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (focused) themeColor.copy(alpha = 0.08f) else Color.Transparent)
                    .border(1.dp, if (focused) themeColor.copy(alpha = 0.65f) else TerminalStyle.buttonBorder, RoundedCornerShape(10.dp))
                    .selectable(focused, role = Role.RadioButton) { previewId = item.id }
                    .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(54.dp, 90.dp).clip(RoundedCornerShape(6.dp))) {
                        WallpaperThumbnailRenderer(item.id, themeColor, isDark, batteryMode)
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(item.label, color = TerminalStyle.ink, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Text(if (item.id == appliedId) "APPLIED" else if (focused) "PREVIEWING" else "TAP TO PREVIEW",
                            Modifier.padding(top = 8.dp), color = if (focused || item.id == appliedId) themeColor else TerminalStyle.muted,
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Text("Backgrounds apply inside this launcher. Saved PNG and MP4 files include the XENOS signature.",
            color = TerminalStyle.muted, fontSize = 12.sp)
    }
}

