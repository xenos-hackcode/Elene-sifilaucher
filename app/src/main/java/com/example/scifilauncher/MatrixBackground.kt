package com.example.scifilauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

val MatrixGreen = Color(0xFF09C20C)

/**
 * Dark mode:
 *  - base: nearly black with slight theme tint
 *  - rain: themeColor tail, white head
 *
 * Light mode:
 *  - base: light theme tint
 *  - rain: black tail, dark head
 */
@Composable
fun MatrixBackground(
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode
) {
    val baseBackground = if (isDark) {
        // close to old MatrixBackground, but a bit tinted by theme
        Color(0xFF020202)
    } else {
        // light mode: very light theme tint
        themeColor.copy(alpha = 0.08f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(baseBackground)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(themeColor.copy(alpha = 0.25f), Color.Transparent),
                        center = Offset(0.5f, 0.3f),
                        radius = 900f
                    )
                )
        )

        MatrixRain(
            modifier = Modifier.fillMaxSize(),
            columnCount = 30,
            speed = 80f,
            isDark = isDark,
            themeColor = themeColor,
            batteryMode = batteryMode
        )
    }
}

@Composable
fun MatrixRain(
    modifier: Modifier = Modifier,
    columnCount: Int = 30,
    speed: Float = 80f,
    isDark: Boolean,
    themeColor: Color,
    batteryMode: BatterySaverMode
) {
    val anim = remember { Animatable(0f) }

    LaunchedEffect(batteryMode) {
        if (batteryMode == BatterySaverMode.AGGRESSIVE) {
            // do nothing → rain stays frozen
            return@LaunchedEffect
        }
        while (true) {
            anim.animateTo(
                targetValue = anim.value + 1f,
                animationSpec = tween(durationMillis = 80, easing = LinearEasing)
            )
        }
    }

    val columnSpeeds = remember {
        List(columnCount) { 40f + Random.nextFloat() * 80f }
    }
    val randomSeed = remember { Random.nextInt() }

    Canvas(modifier = modifier) {
        val widthPerColumn = size.width / columnCount
        val charHeight = 18f
        val rows = (size.height / charHeight).toInt() + 10
        val rnd = Random(randomSeed)

        val tailColorBase = if (isDark) themeColor else Color.Black
        val headColor = if (isDark) Color.White else Color(0xFF111111)

        for (col in 0 until columnCount) {
            val baseX = col * widthPerColumn + widthPerColumn / 2f
            val colSpeed = columnSpeeds[col]
            val columnOffset = (anim.value * colSpeed) + rnd.nextFloat() * rows * charHeight
            val baseY = columnOffset % (rows * charHeight)

            for (row in 0 until rows) {
                val y = baseY + row * charHeight
                if (y < -charHeight || y > size.height + charHeight) continue

                val tailFactor = 0.7f
                val alpha = (1f - row.toFloat() / (rows * tailFactor)).coerceIn(0f, 1f)
                if (alpha <= 0f) continue

                val isHead = row == 0
                val color = if (isHead) headColor else tailColorBase.copy(alpha = alpha)

                drawCircle(
                    color = color,
                    radius = if (isHead) 4f else 3f,
                    center = Offset(baseX, y)
                )
            }
        }
    }
}