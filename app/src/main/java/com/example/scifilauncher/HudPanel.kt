package com.example.scifilauncher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    panelWidth: Dp = 200.dp,
    panelHeight: Dp = 40.dp,
    themeColor: Color
) {
    Box(
        modifier = modifier
            .width(panelWidth)
            .height(panelHeight)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.height / 2f
            drawRoundRect(
                color = themeColor.copy(alpha = 0.35f),
                cornerRadius = CornerRadius(radius, radius)
            )
            drawRoundRect(
                color = themeColor,
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = 3f)
            )
        }
    }
}
