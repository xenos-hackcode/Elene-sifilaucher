package com.example.scifilauncher

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private data class DustParticle(val angle: Float, val speedMul: Float, val depth: Float)

/** The Starfield look (same terminal-glyph particles as the VIDEO Starfield entry), but reacting
 * to a real shake instead of animating on a timer - a shake makes the whole field blow up/explode
 * outward from center, then settle back, rather than the old single-orb flare. */
@Composable
fun ShakeReactiveBackground(themeColor: Color) {
    val context = LocalContext.current
    var energy by remember { mutableFloatStateOf(0f) }
    val particles = remember {
        List(120) { DustParticle(Random.nextFloat() * 6.2832f, Random.nextFloat() * 0.7f + 0.5f, Random.nextFloat() * 0.8f + 0.2f) }
    }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var lastX = 0f; var lastY = 0f; var lastZ = 0f
        var lastTimestamp = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                if (lastTimestamp != 0L) {
                    val delta = sqrt(((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ)).toDouble()).toFloat()
                    energy = min(1f, energy + delta / 40f)
                }
                lastX = x; lastY = y; lastZ = z
                lastTimestamp = event.timestamp
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (accelerometer != null) sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensorManager?.unregisterListener(listener) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16L)
            if (energy > 0f) energy = (energy - 0.015f).coerceAtLeast(0f)
        }
    }

    Canvas(modifier = Modifier.fillMaxSize().background(Color(0xFF020202))) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxTravel = min(size.width, size.height) * 0.75f
        particles.forEach { p ->
            // At rest, particles sit close to center; a shake blows them outward along their own
            // fixed angle, fast movers travel further - a real explosion, not a uniform ring.
            val travel = energy * maxTravel * p.speedMul
            val x = cx + cos(p.angle) * (20f + travel)
            val y = cy + sin(p.angle) * (20f + travel)
            val glyphSize = (2f + p.depth * 4f) * (1f - energy * 0.3f)
            drawRect(
                color = themeColor.copy(alpha = (0.3f + energy * 0.7f) * p.depth),
                topLeft = Offset(x - glyphSize / 2f, y - glyphSize / 2f),
                size = androidx.compose.ui.geometry.Size(glyphSize, glyphSize)
            )
        }
        drawHudCornerBrackets(themeColor)
    }
}
