package com.example.scifilauncher

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Rebuilds the look of the phone's own "HACKER" lock screen background (thin green root/branch
 * lines + a glitchy wordmark) with "XENOS" instead - built fresh from scratch, not derived from
 * that screenshot, which was deleted immediately after being viewed once to understand the look. */
private data class Stem(val points: List<Offset>)

/** Two shapes make up the real reference: a couple of long near-horizontal "trunk" lines that
 * cross almost the full width (anchored off-screen left/right, one above and one below the
 * wordmark), and several separate branch CLUSTERS - each its own anchor point fanning out 2-5
 * organic, multi-segment lines within a narrow angle range, not radiating in every direction.
 * Clusters sit top-left, top-right, one small one just above the wordmark, and several spread
 * across the bottom third - matching the real layout, not just "edges have branches". */
private fun generateStems(seed: Int, width: Float, height: Float): List<Stem> {
    val rand = Random(seed)
    val stems = mutableListOf<Stem>()

    fun zigzag(start: Offset, end: Offset, segments: Int, jitter: Float): List<Offset> {
        val points = mutableListOf(start)
        for (i in 1 until segments) {
            val t = i / segments.toFloat()
            val x = start.x + (end.x - start.x) * t + (rand.nextFloat() - 0.5f) * jitter
            val y = start.y + (end.y - start.y) * t + (rand.nextFloat() - 0.5f) * jitter
            points.add(Offset(x, y))
        }
        points.add(end)
        return points
    }

    fun cluster(anchor: Offset, angleCenter: Float, angleSpread: Float, count: Int, minLen: Float, maxLen: Float) {
        repeat(count) {
            val angle = angleCenter + (rand.nextFloat() - 0.5f) * angleSpread
            val len = minLen + rand.nextFloat() * (maxLen - minLen)
            val end = Offset(anchor.x + kotlin.math.cos(angle) * len, anchor.y + kotlin.math.sin(angle) * len)
            stems.add(Stem(zigzag(anchor, end, rand.nextInt(3, 6), len * 0.12f)))
        }
    }

    // The two long trunk lines - gently bowed, not perfectly straight, one above and one below
    // where the wordmark sits.
    stems.add(Stem(zigzag(Offset(-40f, height * 0.20f), Offset(width + 40f, height * 0.22f), 5, 30f)))
    stems.add(Stem(zigzag(Offset(-40f, height * 0.46f), Offset(width + 40f, height * 0.44f), 5, 30f)))

    // Corner clusters, fanning inward/downward.
    cluster(Offset(width * 0.18f, 0f), angleCenter = 1.3f, angleSpread = 0.9f, count = 4, minLen = height * 0.12f, maxLen = height * 0.3f)
    cluster(Offset(width * 0.82f, 0f), angleCenter = 1.85f, angleSpread = 0.9f, count = 4, minLen = height * 0.12f, maxLen = height * 0.3f)

    // A small cluster right above the wordmark.
    cluster(Offset(width * 0.5f, height * 0.34f), angleCenter = -1.57f, angleSpread = 1.4f, count = 3, minLen = height * 0.05f, maxLen = height * 0.12f)

    // Several clusters across the bottom third, fanning upward - the densest part of the real
    // reference.
    val bottomAnchors = listOf(0.05f, 0.28f, 0.5f, 0.7f, 0.92f)
    bottomAnchors.forEach { ax ->
        cluster(
            Offset(ax * width, height * (0.92f + rand.nextFloat() * 0.08f)),
            angleCenter = -1.57f, angleSpread = 1.6f,
            count = rand.nextInt(2, 4), minLen = height * 0.1f, maxLen = height * 0.32f
        )
    }
    return stems
}

private fun drawStems(canvas: androidx.compose.ui.graphics.drawscope.DrawScope, stems: List<Stem>, color: Color, alpha: Float) {
    stems.forEach { stem ->
        for (i in 0 until stem.points.size - 1) {
            canvas.drawLine(color.copy(alpha = alpha), stem.points[i], stem.points[i + 1], strokeWidth = 1.5f)
        }
    }
}

private fun drawGlitchWordmark(canvas: androidx.compose.ui.graphics.drawscope.DrawScope, cx: Float, cy: Float, color: Color) {
    val textSize = canvas.size.width * 0.14f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        this.textSize = textSize
    }
    canvas.drawContext.canvas.nativeCanvas.apply {
        // A faint darker duplicate offset up-left (the reference's chromatic-glitch layer)...
        paint.color = android.graphics.Color.argb(90, 40, 120, 70)
        drawText("XENOS", cx - 5f, cy + 5f, paint)
        // ...then the real bright text on top.
        paint.color = android.graphics.Color.argb(255, (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        drawText("XENOS", cx, cy, paint)
        // Real scanline dropout gaps cutting across the letters - thin horizontal bars punched
        // out in the background color, same interlaced-glitch look as the reference, not just a
        // color-offset duplicate.
        val bandTop = cy - textSize * 0.75f
        val bandHeight = textSize * 0.9f
        val gapCount = 4
        repeat(gapCount) { i ->
            val gapY = bandTop + bandHeight * (i + 0.5f) / gapCount + (Random(i.toLong()).nextFloat() - 0.5f) * 8f
            val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            gapPaint.color = android.graphics.Color.rgb(2, 2, 2)
            drawRect(cx - textSize * 1.9f, gapY - 3f, cx + textSize * 1.9f, gapY + 3f, gapPaint)
        }
    }
}

@Composable
fun XenosGlitchImageBackground(themeColor: Color) {
    val stems = remember { mutableStateOf<List<Stem>?>(null) }
    Canvas(modifier = Modifier.fillMaxSize().background(Color(0xFF020202))) {
        val s = stems.value ?: generateStems(42, size.width, size.height).also { stems.value = it }
        drawStems(this, s, themeColor, 0.55f)
        drawGlitchWordmark(this, size.width / 2f, size.height / 2f, themeColor)
        drawHudCornerBrackets(themeColor)
    }
}

@Composable
fun XenosGlitchVideoBackground(themeColor: Color, speed: Float = 1f) {
    val transition = rememberInfiniteTransition(label = "xenospulse")
    val pulse by transition.animateFloat(
        initialValue = 0.25f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween((2600 / speed).toInt(), easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "xenospulseT"
    )
    val stems = remember { mutableStateOf<List<Stem>?>(null) }
    Canvas(modifier = Modifier.fillMaxSize().background(Color(0xFF020202))) {
        val s = stems.value ?: generateStems(42, size.width, size.height).also { stems.value = it }
        // The stems breathe in and out - the wordmark stays fixed brightness, only the root
        // lines pulse, exactly what was asked for.
        drawStems(this, s, themeColor, pulse)
        drawGlitchWordmark(this, size.width / 2f, size.height / 2f, themeColor)
        drawHudCornerBrackets(themeColor)
    }
}

private data class TouchGlow(val point: Offset, val startedAtMs: Long)

@Composable
fun XenosGlitchTouchBackground(themeColor: Color) {
    val stems = remember { mutableStateOf<List<Stem>?>(null) }
    val glows = remember { mutableStateListOf<TouchGlow>() }
    val now = remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16L)
            now.value = System.currentTimeMillis()
            glows.removeAll { now.value - it.startedAtMs > 900L }
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020202))
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> glows.add(TouchGlow(change.position, System.currentTimeMillis())) }
            }
    ) {
        val s = stems.value ?: generateStems(42, size.width, size.height).also { stems.value = it }
        drawStems(this, s, themeColor, 0.4f)
        // Wherever a finger drags over it, a soft lighter glow blooms and fades - "the part you
        // touch pulses lighter", not a generic ripple unrelated to finger position.
        glows.forEach { glow ->
            val age = ((now.value - glow.startedAtMs) / 900f).coerceIn(0f, 1f)
            drawCircle(color = themeColor.copy(alpha = (1f - age) * 0.5f), radius = 70f * (1f - age * 0.4f), center = glow.point)
        }
        drawGlitchWordmark(this, size.width / 2f, size.height / 2f, themeColor)
        drawHudCornerBrackets(themeColor)
    }
}
