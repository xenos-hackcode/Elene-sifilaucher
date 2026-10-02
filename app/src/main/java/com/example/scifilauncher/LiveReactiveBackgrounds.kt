package com.example.scifilauncher

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

private data class TiltDust(val baseX: Float, val baseY: Float, val depth: Float)

/** The same Starfield look/particles as Shake and the VIDEO Starfield entry, but instead of
 * exploding on a jolt, the dust just drifts to follow whichever way the phone is actually tilted
 * - a real continuous parallax recalculated on every sensor reading (deeper/closer particles
 * drift further, same depth trick the Video Starfield uses), not a canned back-and-forth. */
@Composable
fun TiltReactiveBackground(themeColor: Color) {
    val context = LocalContext.current
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    val dust = remember {
        List(120) { TiltDust(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 0.8f + 0.2f) }
    }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // X/Y accelerometer readings while roughly upright double as a tilt reading -
                // clamped since raw values can exceed +/-9.8 during real movement, not just tilt.
                tiltX = (event.values[0] / 9.8f).coerceIn(-1f, 1f)
                tiltY = (event.values[1] / 9.8f).coerceIn(-1f, 1f)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensorManager?.unregisterListener(listener) }
    }

    Canvas(modifier = Modifier.fillMaxSize().background(Color(0xFF020202))) {
        dust.forEach { d ->
            // Deeper (higher-depth) particles drift further per unit of tilt - the parallax that
            // makes it read as dust actually floating in 3D space, not a flat sheet moving as one.
            val x = d.baseX * size.width + tiltX * size.width * 0.25f * d.depth
            val y = d.baseY * size.height - tiltY * size.height * 0.25f * d.depth
            val glyphSize = 2f + d.depth * 5f
            drawRect(
                color = themeColor.copy(alpha = 0.2f + d.depth * 0.6f),
                topLeft = Offset(x - glyphSize / 2f, y - glyphSize / 2f),
                size = androidx.compose.ui.geometry.Size(glyphSize, glyphSize)
            )
        }
        drawHudCornerBrackets(themeColor)
    }
}

private data class Ripple(val origin: Offset, val startedAtMs: Long)

/** TOUCH - reacts to real taps on the Dashboard itself: each tap spawns a genuine expanding
 * ripple at that exact point, not a fixed animation playing regardless of where (or whether) you
 * touch. No sensors/camera at all - the safest and cheapest Live source to run continuously. */
@Composable
fun TouchRippleBackground(themeColor: Color) {
    val ripples = remember { mutableStateListOf<Ripple>() }
    val now = remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16L)
            now.value = System.currentTimeMillis()
            ripples.removeAll { now.value - it.startedAtMs > 1200L }
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020202))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset -> ripples.add(Ripple(offset, System.currentTimeMillis())) })
            }
    ) {
        ripples.forEach { ripple ->
            val age = ((now.value - ripple.startedAtMs) / 1200f).coerceIn(0f, 1f)
            drawCircle(
                color = themeColor.copy(alpha = (1f - age) * 0.6f),
                radius = age * min(size.width, size.height) * 0.5f,
                center = ripple.origin,
                style = Stroke(width = 3f)
            )
        }
        if (ripples.isEmpty()) {
            drawCircle(color = themeColor.copy(alpha = 0.15f), radius = min(size.width, size.height) * 0.1f, center = Offset(size.width / 2f, size.height / 2f))
        }
        drawHudCornerBrackets(themeColor)
    }
}

/** VOICE - the loudness of whatever's around the phone drives a pulsing glow, real mic amplitude
 * sampled directly (not this app's own speech-recognition pipeline, which needs an active
 * listening session - this just reads raw input level). Only runs if RECORD_AUDIO is already
 * granted (it always is in this app, for Xenos) - never requests it itself, and falls back to a
 * plain still glow if it somehow isn't. */
@Composable
fun VoiceReactiveBackground(themeColor: Color) {
    val context = LocalContext.current
    var level by remember { mutableFloatStateOf(0f) }
    // A short rolling history, not just the instantaneous level - lets the bars read as a real
    // equalizer sweeping left to right instead of one bar pulsing in place.
    val barCount = 24
    val history = remember { mutableStateListOf(*FloatArray(barCount) { 0f }.toTypedArray()) }
    LaunchedEffect(level) {
        history.removeAt(0)
        history.add(level)
    }
    val hasPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    if (hasPermission) {
        LaunchedEffect(Unit) {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return@LaunchedEffect
            val recorder = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
            }.getOrNull() ?: return@LaunchedEffect
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@LaunchedEffect
            }
            val buffer = ShortArray(minBuf)
            try {
                recorder.startRecording()
                while (true) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sumSquares = 0.0
                        for (i in 0 until read) sumSquares += (buffer[i] * buffer[i]).toDouble()
                        val rms = kotlin.math.sqrt(sumSquares / read)
                        // Real speech/room noise peaks well under Short.MAX_VALUE - 4000 keeps
                        // normal talking volume visibly reactive instead of needing a shout.
                        level = (rms / 4000.0).toFloat().coerceIn(0f, 1f)
                    }
                    delay(50L)
                }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }
        }
    }

    Canvas(modifier = Modifier.fillMaxSize().background(Color(0xFF020202))) {
        val cy = size.height / 2f
        val barWidth = size.width / barCount
        history.forEachIndexed { i, h ->
            val barHeight = size.height * 0.08f + h * size.height * 0.35f
            val x = i * barWidth + barWidth * 0.2f
            drawLine(
                color = themeColor.copy(alpha = 0.3f + h * 0.6f),
                start = Offset(x, cy - barHeight),
                end = Offset(x, cy + barHeight),
                strokeWidth = barWidth * 0.5f
            )
        }
        drawHudCornerBrackets(themeColor)
    }
}
