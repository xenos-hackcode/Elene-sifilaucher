package com.example.scifilauncher

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** One dot of the "XENOS" dot-matrix wordmark, position normalized 0..1 within its own text box. */
private data class NameDot(val nx: Float, val ny: Float, val upperHalf: Boolean)

/** One spark of the touch-triggered stardust burst - real position/velocity, not a canned sprite. */
private class Stardust(var x: Float, var y: Float, var vx: Float, var vy: Float, val bornAtMs: Long, val lifeMs: Long, val size: Float)

/** Renders "XENOS" to a small offscreen bitmap once and samples the lit pixels into dot
 * positions - a real dot-matrix of the actual letterforms, not a hand-placed approximation. */
private fun sampleNameDots(): List<NameDot> {
    val w = 480
    val h = 160
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        textSize = h * 0.72f
        color = android.graphics.Color.WHITE
    }
    val metrics = paint.fontMetrics
    val baselineY = h / 2f - (metrics.ascent + metrics.descent) / 2f
    canvas.drawText("XENOS", w / 2f, baselineY, paint)

    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val dots = mutableListOf<NameDot>()
    val stride = 4
    var minY = h.toFloat(); var maxY = 0f
    for (y in 0 until h step stride) {
        for (x in 0 until w step stride) {
            val alpha = (pixels[y * w + x] ushr 24) and 0xFF
            if (alpha > 90) {
                if (y < minY) minY = y.toFloat()
                if (y > maxY) maxY = y.toFloat()
            }
        }
    }
    val midY = (minY + maxY) / 2f
    for (y in 0 until h step stride) {
        for (x in 0 until w step stride) {
            val alpha = (pixels[y * w + x] ushr 24) and 0xFF
            if (alpha > 90) {
                dots += NameDot(x.toFloat() / w, y.toFloat() / h, y <= midY)
            }
        }
    }
    bitmap.recycle()
    return dots
}

/** The Horizon Line look (same wireframe ground grid as the IMAGE entry), but alive: "XENOS" sits
 * dead center of the screen as a dot-matrix wordmark (user: "the xenos comes like micro dots
 * forming it"), formed by two scan beams - one dropping from the top edge, one rising from the
 * bottom edge - both converging on the middle together. Each beam reveals its own half of the
 * dots top-down / bottom-up as it passes them (not a uniform fade), and the instant they meet in
 * the middle the whole cycle snaps back to the top/bottom edges and starts over from scratch
 * (user: "they both goe sback from scratch"). Touching the screen additionally scatters a real
 * stardust particle burst from wherever you touch, independent of the ambient beam cycle. */
@Composable
fun HorizonTouchBackground(themeColor: Color) {
    val nameDots = remember { sampleNameDots() }
    val stardust = remember { mutableListOf<Stardust>() }
    var nowMs by remember { mutableLongStateOf(0L) }
    var pointerPos by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(Unit) {
        val t0 = withFrameMillis { it }
        while (true) {
            withFrameMillis { frameMs ->
                nowMs = frameMs - t0
                val p = pointerPos
                if (p != null) {
                    repeat(2) {
                        val angle = Random.nextFloat() * (2f * Math.PI).toFloat()
                        val speed = 40f + Random.nextFloat() * 140f
                        stardust += Stardust(
                            x = p.x, y = p.y,
                            vx = kotlin.math.cos(angle) * speed,
                            vy = kotlin.math.sin(angle) * speed,
                            bornAtMs = nowMs,
                            lifeMs = 500L + Random.nextInt(500),
                            size = 1.5f + Random.nextFloat() * 2.5f
                        )
                    }
                }
                stardust.removeAll { nowMs - it.bornAtMs > it.lifeMs }
            }
        }
    }

    // Full cycle: beams converge over CONVERGE_MS, hold fully formed for HOLD_MS, then the
    // enclosing frame clock's own modulo below snaps the next frame straight back to t=0 - a real
    // instant reset, not an eased return.
    val convergeMs = 2600L
    val holdMs = 900L
    val cycleMs = convergeMs + holdMs

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020202))
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        pointerPos = change.position
                        if (!change.pressed) {
                            pointerPos = null
                            break
                        }
                    }
                }
            }
    ) {
        val horizon = size.height * 0.62f
        // Mirrors [horizon] around the screen's vertical center - the "ceiling" the top grid
        // recedes toward, same distance from the top edge as the floor's horizon is from the
        // bottom edge, for real top/bottom symmetry.
        val ceiling = size.height - horizon
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(themeColor.copy(alpha = 0.08f), Color(0xFF020202), themeColor.copy(alpha = 0.08f)), startY = ceiling, endY = horizon),
            topLeft = Offset(0f, ceiling),
            size = androidx.compose.ui.geometry.Size(size.width, horizon - ceiling)
        )
        drawLine(color = themeColor.copy(alpha = 0.6f), start = Offset(0f, horizon), end = Offset(size.width, horizon), strokeWidth = 2f)
        drawLine(color = themeColor.copy(alpha = 0.6f), start = Offset(0f, ceiling), end = Offset(size.width, ceiling), strokeWidth = 2f)

        // Dead center of the whole screen.
        val textSize = size.width * 0.13f
        val nameY = size.height / 2f
        val boxWidth = textSize * 3.6f
        val boxHeight = textSize * 1.05f
        val boxLeft = size.width / 2f - boxWidth / 2f
        val boxTop = nameY - boxHeight / 2f

        val cyclePos = nowMs % cycleMs
        val convergeT = min(1f, cyclePos.toFloat() / convergeMs.toFloat())
        // Fast travel across the empty grid, then most of the cycle is spent slowly crossing the
        // (small) name box itself - a constant-speed beam over the whole screen height only
        // dwelt on the name for its last ~9% of travel, too brief to actually see dots enter.
        val edgePhase = 0.35f
        val boxBottom = boxTop + boxHeight
        val topLineY: Float
        val bottomLineY: Float
        if (convergeT < edgePhase) {
            val p = convergeT / edgePhase
            topLineY = boxTop * p
            bottomLineY = size.height - (size.height - boxBottom) * p
        } else {
            val p = (convergeT - edgePhase) / (1f - edgePhase)
            topLineY = boxTop + (nameY - boxTop) * p
            bottomLineY = boxBottom - (boxBottom - nameY) * p
        }

        // The ground grid itself IS the bottom scanner - its floor recedes from the screen's
        // bottom edge up toward the middle in lockstep with [bottomLineY] (same piecewise pacing
        // as the dot reveal below), rather than a separate flat line riding on top of a static
        // grid. User: "make the static bottom move to the middle instead of the bottom scanner
        // so the static does what the bottom scanner ids doing".
        val vanishX = size.width / 2f
        var gx = -size.width
        while (gx < size.width * 2) {
            drawLine(themeColor.copy(alpha = 0.2f), Offset(vanishX, horizon), Offset(gx, bottomLineY), 1f)
            gx += 60f
        }
        var gy = horizon; var step = 20f
        while (gy < bottomLineY) {
            drawLine(themeColor.copy(alpha = 0.2f), Offset(0f, gy), Offset(size.width, gy), 1f)
            gy += step; step *= 1.35f
        }

        // The ceiling grid is the same treatment flipped vertically - it's the top scanner now,
        // its own edge receding from the screen's top edge down toward the middle in lockstep
        // with [topLineY]. User: "make the top look like the bottom".
        var gxTop = -size.width
        while (gxTop < size.width * 2) {
            drawLine(themeColor.copy(alpha = 0.2f), Offset(vanishX, ceiling), Offset(gxTop, topLineY), 1f)
            gxTop += 60f
        }
        var gyTop = ceiling; var stepTop = 20f
        while (gyTop > topLineY) {
            drawLine(themeColor.copy(alpha = 0.2f), Offset(0f, gyTop), Offset(size.width, gyTop), 1f)
            gyTop -= stepTop; stepTop *= 1.35f
        }

        val dotRadius = max(1.2f, boxWidth / 480f * 2.2f)
        // How far behind its own beam a dot's fade/grow-in trails, in px - each dot doesn't just
        // pop to full brightness the instant the beam crosses it, it visibly grows in over this
        // short distance right behind the line, so the dots read as entering, not switching on.
        val entryTrailPx = boxHeight * 0.35f
        for (dot in nameDots) {
            val dy = boxTop + dot.ny * boxHeight
            val distancePastBeam = if (dot.upperHalf) topLineY - dy else dy - bottomLineY
            if (distancePastBeam > 0f) {
                val enterProgress = min(1f, distancePastBeam / entryTrailPx)
                drawCircle(
                    color = themeColor.copy(alpha = 0.25f + enterProgress * 0.75f),
                    radius = dotRadius * (0.35f + enterProgress * 0.65f),
                    center = Offset(boxLeft + dot.nx * boxWidth, dy),
                    style = Fill
                )
            }
        }

        // Touch-triggered stardust - independent of the beam cycle above.
        for (spark in stardust) {
            val age = (nowMs - spark.bornAtMs).coerceAtLeast(0L)
            val lifeFrac = (age.toFloat() / spark.lifeMs.toFloat()).coerceIn(0f, 1f)
            val ageSeconds = age / 1000f
            val sx = spark.x + spark.vx * ageSeconds
            val sy = spark.y + spark.vy * ageSeconds
            drawCircle(
                color = themeColor.copy(alpha = (1f - lifeFrac) * 0.9f),
                radius = spark.size * (1f - lifeFrac * 0.4f),
                center = Offset(sx, sy),
                style = Fill
            )
        }

        drawHudCornerBrackets(themeColor)
    }
}
