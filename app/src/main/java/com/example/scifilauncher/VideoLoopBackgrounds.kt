package com.example.scifilauncher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlin.random.Random

/** VIDEO category renderers - looping animations, no interaction (that's what makes them Video
 * and not Live). Each takes [speed] as a plain multiplier so one real implementation can back
 * two catalog entries ("Calm"/"Fast") without duplicating the drawing code - real, visually
 * distinct variants, not two labels pointing at the same static thing. */

@Composable
fun StarfieldBackground(themeColor: Color, speed: Float = 1f) {
    val stars = remember {
        List(140) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 0.8f + 0.2f) }
    }
    val transition = rememberInfiniteTransition(label = "starfield")
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween((12000 / speed).toInt(), easing = LinearEasing)),
        label = "starfieldT"
    )
    Canvas(modifier = Modifier.fillMaxSize().background(Color(0xFF020202))) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        stars.forEach { (baseX, baseY, depth) ->
            // Stars drift outward from center over the loop, wrapping back to the middle -
            // a real "flying through space" parallax, not a static star field.
            val progress = (t + depth) % 1f
            val x = cx + (baseX - 0.5f) * size.width * progress * 2f
            val y = cy + (baseY - 0.5f) * size.height * progress * 2f
            // Small squares instead of circles - reads as drifting terminal glyphs/pixels rather
            // than generic space dust, matching the rest of this catalog's hacker look.
            val glyphSize = (2f + progress * 5f) * depth
            drawRect(
                color = themeColor.copy(alpha = (1f - progress).coerceIn(0.15f, 1f)),
                topLeft = Offset(x - glyphSize / 2f, y - glyphSize / 2f),
                size = androidx.compose.ui.geometry.Size(glyphSize, glyphSize)
            )
        }
        drawHudCornerBrackets(themeColor)
    }
}

@Composable
fun ScanlinesBackground(themeColor: Color, speed: Float = 1f) {
    val transition = rememberInfiniteTransition(label = "scanlines")
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween((2400 / speed).toInt(), easing = LinearEasing)),
        label = "scanlineT"
    )
    Canvas(modifier = Modifier.fillMaxSize().background(Color(0xFF020202))) {
        val lineSpacing = 6.dp.toPx()
        var y = -lineSpacing
        while (y < size.height) {
            drawLine(
                color = themeColor.copy(alpha = 0.06f),
                start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f
            )
            y += lineSpacing
        }
        // The bright sweeping band that gives this its "scanning" feel.
        val sweepY = t * (size.height + 200f) - 100f
        drawLine(
            color = themeColor.copy(alpha = 0.5f),
            start = Offset(0f, sweepY), end = Offset(size.width, sweepY), strokeWidth = 3f
        )
        drawHudCornerBrackets(themeColor)
    }
}

@Composable
fun PulseBackground(themeColor: Color, speed: Float = 1f) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween((3000 / speed).toInt(), easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "pulseT"
    )
    Canvas(modifier = Modifier.fillMaxSize().background(Color(0xFF020202))) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxRadius = kotlin.math.min(size.width, size.height) * 0.4f
        for (i in 0..2) {
            val ringT = ((t + i / 3f) % 1f)
            drawCircle(
                color = themeColor.copy(alpha = (1f - ringT) * 0.4f),
                radius = maxRadius * ringT,
                center = Offset(cx, cy),
                style = Stroke(width = 2f)
            )
        }
        drawCircle(color = themeColor.copy(alpha = 0.5f + t * 0.3f), radius = maxRadius * 0.15f, center = Offset(cx, cy))
        drawHudCornerBrackets(themeColor)
    }
}

@Composable
fun GridTunnelBackground(themeColor: Color, speed: Float = 1f) {
    val transition = rememberInfiniteTransition(label = "grid")
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween((2000 / speed).toInt(), easing = LinearEasing)),
        label = "gridT"
    )
    Canvas(modifier = Modifier.fillMaxSize().background(Color(0xFF020202))) {
        val horizon = size.height * 0.4f
        val cellSize = 40f
        // Vertical lines converge toward a center vanishing point on the horizon.
        val vanishX = size.width / 2f
        var x = -size.width
        while (x < size.width * 2) {
            drawLine(
                color = themeColor.copy(alpha = 0.25f),
                start = Offset(vanishX, horizon), end = Offset(x, size.height), strokeWidth = 1f
            )
            x += cellSize
        }
        // Horizontal lines scroll toward the viewer, looping - the actual "moving" part.
        var rowT = t
        while (rowT < 4f) {
            val y = horizon + (size.height - horizon) * (1f - 1f / (1f + rowT))
            if (y in horizon..size.height) {
                drawLine(color = themeColor.copy(alpha = 0.3f), start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f)
            }
            rowT += 0.3f
        }
        drawHudCornerBrackets(themeColor)
    }
}

@Composable
fun CodeRainBackground(themeColor: Color, speed: Float = 1f) {
    // Deliberately distinct from MatrixBackground's own rain (denser, thinner columns, no
    // per-character glyph swap) rather than a re-skin of the exact same effect under a new name.
    val columnCount = 28
    val columns = remember { List(columnCount) { Random.nextFloat() } }
    val transition = rememberInfiniteTransition(label = "coderain")
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween((4000 / speed).toInt(), easing = LinearEasing)),
        label = "coderainT"
    )
    Canvas(modifier = Modifier.fillMaxSize().background(Color(0xFF020202))) {
        val colWidth = size.width / columnCount
        columns.forEachIndexed { i, offset ->
            val progress = (t + offset) % 1f
            val yHead = progress * size.height * 2f - size.height * 0.5f
            val tailLength = size.height * 0.35f
            drawLine(
                color = themeColor.copy(alpha = 0.35f),
                start = Offset(i * colWidth + colWidth / 2f, yHead - tailLength),
                end = Offset(i * colWidth + colWidth / 2f, yHead),
                strokeWidth = 2f
            )
            drawCircle(color = themeColor, radius = 2.5f, center = Offset(i * colWidth + colWidth / 2f, yHead))
        }
        drawHudCornerBrackets(themeColor)
    }
}
