package com.example.scifilauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.random.Random

@Composable
fun GlitchIcon(
    bitmap: ImageBitmap,
    contentDescription: String?,
    sizeDp: Int = 28
) {
    val phase = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            phase.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 80,
                    easing = LinearEasing
                )
            )
            phase.snapTo(0f)
        }
    }

    val rnd = remember { Random(System.currentTimeMillis()) }

    fun randOffset(max: Int): IntOffset {
        val dx = rnd.nextInt(-max, max + 1)
        val dy = rnd.nextInt(-max, max + 1)
        return IntOffset(dx, dy)
    }

    Box(contentAlignment = Alignment.Center) {
        // base = ORIGINAL icon, no tint
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = Modifier.size(sizeDp.dp)
        )

        // red glitch copy
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(sizeDp.dp)
                .offset {
                    val o = randOffset(10)
                    IntOffset(
                        (o.x * phase.value).toInt(),
                        (o.y * phase.value).toInt()
                    )
                },
            colorFilter = ColorFilter.tint(Color.Red.copy(alpha = 0.7f))
        )

        // green glitch copy
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(sizeDp.dp)
                .offset {
                    val o = randOffset(12)
                    IntOffset(
                        (-o.x * phase.value).toInt(),
                        (o.y * phase.value).toInt()
                    )
                },
            colorFilter = ColorFilter.tint(Color.Green.copy(alpha = 0.7f))
        )
    }
}
