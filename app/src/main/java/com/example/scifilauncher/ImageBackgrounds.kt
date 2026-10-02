package com.example.scifilauncher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

/** IMAGE category renderers - genuinely still, no animation, no sensors. Distinct from a Video
 * entry paused on one frame: these were designed to be static from the start (a single draw
 * pass, nothing ever recomposes them on a timer). */

@Composable
fun VignetteBackground(themeColor: Color) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF020202), themeColor.copy(alpha = 0.12f), Color.Black),
                    radius = 1600f
                )
            )
    ) {
        drawHudCornerBrackets(themeColor)
        drawFaintScanlines(themeColor)
    }
}

@Composable
fun NoiseStaticBackground(themeColor: Color) {
    // A fixed, one-time-generated speckle field - remember{} means this never regenerates or
    // animates, a real still image rather than TV static that happens to be paused.
    val dots = remember { List(500) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    Canvas(modifier = Modifier.fillMaxSize().background(Color(0xFF020202))) {
        dots.forEach { (x, y, alpha) ->
            drawCircle(
                color = themeColor.copy(alpha = alpha * 0.4f),
                radius = 1.2f,
                center = Offset(x * size.width, y * size.height)
            )
        }
        drawHudCornerBrackets(themeColor)
    }
}

@Composable
fun HorizonLineBackground(themeColor: Color) {
    Canvas(modifier = Modifier.fillMaxSize().background(Color(0xFF020202))) {
        val horizon = size.height * 0.62f
        drawRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF020202), themeColor.copy(alpha = 0.08f)), endY = horizon),
            size = androidx.compose.ui.geometry.Size(size.width, horizon)
        )
        drawLine(color = themeColor.copy(alpha = 0.6f), start = Offset(0f, horizon), end = Offset(size.width, horizon), strokeWidth = 2f)
        // A fixed wireframe floor grid below the line - a static single draw, not the moving
        // GridTunnel Video entry - reads as a terminal/HUD ground plane, not a plain gradient.
        val vanishX = size.width / 2f
        var gx = -size.width
        while (gx < size.width * 2) {
            drawLine(themeColor.copy(alpha = 0.2f), Offset(vanishX, horizon), Offset(gx, size.height), 1f)
            gx += 60f
        }
        var gy = horizon
        var step = 20f
        while (gy < size.height) {
            drawLine(themeColor.copy(alpha = 0.2f), Offset(0f, gy), Offset(size.width, gy), 1f)
            gy += step
            step *= 1.35f
        }
        drawHudCornerBrackets(themeColor)
    }
}
