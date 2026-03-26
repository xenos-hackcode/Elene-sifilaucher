package com.example.scifilauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

@Composable
fun GlitchNavLetter(
    letter: String,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        GlitchTitle(
            text = letter,
            color = color,
            fontSize = 20f
        )
    }
}

@Composable
fun GlitchTitle(
    text: String,
    color: Color,
    fontSize: Float = 32f
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

    val baseStyle = TextStyle(
        color = color,
        fontSize = fontSize.sp,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center
    )

    Box(contentAlignment = Alignment.Center) {
        // base text
        androidx.compose.material3.Text(
            text = text,
            style = baseStyle
        )

        // red channel – EXACT same pattern as your old one
        androidx.compose.material3.Text(
            text = text,
            style = baseStyle.copy(color = Color.Red.copy(alpha = 0.7f)),
            modifier = Modifier.offset {
                val o = randOffset(10)
                IntOffset(
                    (o.x * phase.value).toInt(),
                    (o.y * phase.value).toInt()
                )
            }
        )

        // green channel – EXACT same pattern as your old one
        androidx.compose.material3.Text(
            text = text,
            style = baseStyle.copy(color = Color.Green.copy(alpha = 0.7f)),
            modifier = Modifier.offset {
                val o = randOffset(12)
                IntOffset(
                    (-o.x * phase.value).toInt(),
                    (o.y * phase.value).toInt()
                )
            }
        )
    }
}
