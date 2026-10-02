package com.example.scifilauncher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** IMAGE category's first real entry - a plain static gradient using the current theme color, no
 * animation, no sensors. Deliberately the simplest possible wallpaper: a genuine still image, not
 * a slowed-down video or paused frame of one of the Video entries. HUD corner brackets + faint
 * scanlines keep it reading as terminal/HUD rather than a plain generic glow. */
@Composable
fun SolidGlowBackground(themeColor: Color) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(themeColor.copy(alpha = 0.25f), Color(0xFF020202)),
                    radius = 1200f
                )
            )
    ) {
        drawHudCornerBrackets(themeColor)
        drawFaintScanlines(themeColor)
    }
}
