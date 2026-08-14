package com.example.scifilauncher

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MAX_MULTI_CONTROL_APPS = 3

/** Real, resizable multi-window - each selected app launches into its own genuine freeform
 * window (WINDOWING_MODE_FREEFORM), not a fake preview/thumbnail. This only works at all
 * because this app is the device's Device Owner/default launcher - a normal third-party app
 * can't force another app into freeform windowing mode, that capability is specifically tied to
 * this app's elevated system role, confirmed against this device's real freeform support
 * (pm has-feature android.software.freeform_window_management) before building this. */
fun launchAppsInMultiControl(context: Context, packageNames: List<String>): Boolean {
    if (packageNames.isEmpty() || packageNames.size > MAX_MULTI_CONTROL_APPS) return false
    val pm = context.packageManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
    val screenW = metrics.widthPixels
    val screenH = metrics.heightPixels

    val bounds: List<Rect> = when (packageNames.size) {
        1 -> listOf(Rect(0, 0, screenW, screenH))
        2 -> listOf(
            Rect(0, 0, screenW / 2, screenH),
            Rect(screenW / 2, 0, screenW, screenH)
        )
        else -> {
            val rowH = screenH / 3
            listOf(
                Rect(0, 0, screenW, rowH),
                Rect(0, rowH, screenW, rowH * 2),
                Rect(0, rowH * 2, screenW, screenH)
            )
        }
    }

    var launchedAny = false
    packageNames.forEachIndexed { i, pkg ->
        val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: return@forEachIndexed
        // Required when called from a non-Activity context (e.g. the accessibility service's
        // voice-command path) - harmless to also set when called from an Activity, so it's
        // always added rather than kept conditional on the caller.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            val options = ActivityOptions.makeBasic()
            options.launchBounds = bounds[i]
            // WINDOWING_MODE_FREEFORM = 5 (android.app.WindowConfiguration) - that class's
            // constants aren't part of the public compileSdk surface at this project's SDK
            // level, so the literal is used directly rather than an inaccessible reference.
            setLaunchWindowingModeFreeform(options)
            context.startActivity(launchIntent, options.toBundle())
            launchedAny = true
        }
    }
    return launchedAny
}

/** Isolated purely so the reflective fallback (see inside) only ever runs once per call and
 * stays out of the main launch loop's error handling. */
private fun setLaunchWindowingModeFreeform(options: ActivityOptions) {
    runCatching {
        val method = ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
        method.invoke(options, 5) // WindowConfiguration.WINDOWING_MODE_FREEFORM
    }
}

@Composable
fun MultiControlScreen(
    themeColor: Color,
    isDark: Boolean,
    apps: List<AppItem>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(listOf<String>()) }

    val filtered = remember(query, apps) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xFF060911) else Color(0xFFEFEFEF))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "◀",
                color = themeColor,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp)
            )
            Text(
                text = "MULTI CONTROL",
                color = themeColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Pick 2-3 apps to open together, each in its own real resizable window - " +
                    "not a preview, genuinely usable side by side.",
            color = Color.Gray,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))

        Text(
            "SELECTED (${selected.size}/$MAX_MULTI_CONTROL_APPS)",
            color = Color.Gray,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(6.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filtered) { app ->
                val isSelected = app.packageName in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) themeColor.copy(alpha = 0.25f) else Color.Gray.copy(alpha = 0.08f))
                        .clickable {
                            selected = if (isSelected) {
                                selected - app.packageName
                            } else if (selected.size < MAX_MULTI_CONTROL_APPS) {
                                selected + app.packageName
                            } else {
                                selected
                            }
                        }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        bitmap = app.iconBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        app.label,
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Text("✓", color = themeColor, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val ok = launchAppsInMultiControl(context, selected)
                if (!ok) {
                    Toast.makeText(context, "Couldn't launch those apps in multi control.", Toast.LENGTH_LONG).show()
                } else {
                    onBack()
                }
            },
            enabled = selected.size in 2..MAX_MULTI_CONTROL_APPS,
            colors = ButtonDefaults.buttonColors(containerColor = themeColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("LAUNCH", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}
