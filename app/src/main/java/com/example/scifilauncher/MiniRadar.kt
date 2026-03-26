package com.example.scifilauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun MiniRadar(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    themeColor: Color
) {
    val rotation = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = tween(durationMillis = 4000, easing = LinearEasing)
            )
        }
    }

    Box(
        modifier = modifier.size(size)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val radius = min(this.size.width, this.size.height) / 2f * 0.9f
            val center = this.center

            drawCircle(
                color = themeColor.copy(alpha = 0.4f),
                radius = radius,
                style = Stroke(width = 4f)
            )
            drawCircle(
                color = themeColor.copy(alpha = 0.3f),
                radius = radius * 0.7f,
                style = Stroke(width = 3f)
            )
            drawCircle(
                color = themeColor.copy(alpha = 0.15f),
                radius = radius * 0.35f,
                style = Stroke(width = 2f)
            )

            val angleRad = Math.toRadians(rotation.value.toDouble())
            val endX = center.x + radius * cos(angleRad).toFloat()
            val endY = center.y + radius * sin(angleRad).toFloat()

            drawLine(
                color = themeColor,
                start = center,
                end = Offset(endX, endY),
                strokeWidth = 6f
            )
        }
    }
}
