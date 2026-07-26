package com.example.scifilauncher

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun HudCircle(
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,
    themeColor: Color
) {
    val context = LocalContext.current

    // Read battery level fresh
    val bm = context.getSystemService(BatteryManager::class.java)
    val batteryLevel = (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0)
        .coerceIn(0, 100)

    // Read charging state fresh
    val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    val statusIntent: Intent? = context.registerReceiver(null, ifilter)
    val status = statusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val isCharging =
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

    val ringColor = batteryLevelColor(batteryLevel)

    Box(
        modifier = modifier.size(size)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val radius = this.size.minDimension / 2f * 0.9f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)

            drawCircle(
                color = themeColor.copy(alpha = 0.4f),
                radius = radius,
                style = Stroke(width = 8f)
            )

            val sweep = 360f * (batteryLevel / 100f.toFloat())
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                style = Stroke(width = 10f, cap = StrokeCap.Round)
            )

            val innerRadius = radius * 0.6f
            drawCircle(
                color = themeColor.copy(alpha = 0.2f),
                radius = innerRadius,
                style = Stroke(width = 4f)
            )

            if (isCharging) {
                val rect = Rect(
                    center.x - innerRadius,
                    center.y - innerRadius,
                    center.x + innerRadius,
                    center.y + innerRadius
                )

                val level = batteryLevel.coerceIn(0, 100) / 100f
                drawArc(
                    color = Color(0xFF00FF00).copy(alpha = 0.35f),
                    startAngle = 90f,
                    sweepAngle = 360f * level,
                    useCenter = true,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height)
                )
            }
        }

        Box(
            modifier = Modifier.align(Alignment.Center)
        ) {
            GlitchTitle(
                text = "$batteryLevel%",
                color = if (isCharging) Color(0xFF00FF00) else ringColor,
                fontSize = 24f
            )
        }
    }
}
