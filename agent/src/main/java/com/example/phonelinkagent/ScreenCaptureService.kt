package com.example.phonelinkagent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

/**
 * Continuous screen capture for the phone-link agent - trimmed from SciFiLauncher's
 * ScreenPerceptionService (same real MediaProjection/VirtualDisplay/ImageReader mechanics and
 * the same idempotent-teardown fix for MediaProjection.stop() re-triggering its own onStop()
 * callback), but this app only ever needs one continuous streaming mode - no single-shot
 * describe, no AI game loop, so those branches don't exist here at all.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0

    private val handler = Handler(Looper.getMainLooper())

    // The request/available-frame handshake is event-driven, not polled - acquireLatestImage()
    // was found (via real on-device timing evidence, not assumed) to consistently return null
    // for ~400-500ms after each request on a slower/Unisoc-chipset device, and a poll-with-sleep
    // retry loop was burning that whole delay on every single frame (measured ~1.19s per frame
    // instead of the intended ~150ms). Registering OnImageAvailableListener lets Android tell us
    // the instant a frame actually exists instead of guessing with sleeps.
    private var pendingRequestId: String? = null
    private val timeoutRunnable = Runnable {
        val id = pendingRequestId
        if (id != null) {
            pendingRequestId = null
            android.util.Log.w("ScreenCaptureService", "captureFrame($id): timed out waiting for a frame")
            broadcastError(id)
        }
    }

    private val requestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val requestId = intent?.getStringExtra("requestId") ?: return
            captureFrame(requestId)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_CAPTURE) {
            teardown()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (intent == null || data == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        startCapture(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val ok = runCatching {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
            captureWidth = metrics.widthPixels
            captureHeight = metrics.heightPixels

            val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            reader.setOnImageAvailableListener({ r ->
                val id = pendingRequestId
                if (id == null) {
                    // No outstanding request right now - drain and discard so the buffer queue
                    // doesn't fill up and stall future acquires.
                    runCatching { r.acquireLatestImage()?.close() }
                    return@setOnImageAvailableListener
                }
                pendingRequestId = null
                handler.removeCallbacks(timeoutRunnable)
                val image = runCatching { r.acquireLatestImage() }.getOrNull()
                if (image == null) {
                    android.util.Log.w("ScreenCaptureService", "captureFrame($id): listener fired but acquireLatestImage still null")
                    broadcastError(id)
                } else {
                    processImage(id, image)
                }
            }, handler)

            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data)
            mediaProjection = projection

            // Must register before createVirtualDisplay() on newer Android or it throws
            // IllegalStateException. Also covers the system revoking the projection out from
            // under us (user taps the system's own screen-capture status-bar chip).
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // Android itself revokes the projection when the screen turns off (a real,
                    // deliberate platform privacy boundary - confirmed live 2026-08-10, not
                    // something any in-app fix can prevent). Real, separate crash also found
                    // live: an earlier version of this tried to keep THIS service alive in a
                    // "paused" foreground state with an updated notification - but Android
                    // refuses startForeground() re-affirming the mediaProjection type without an
                    // actively valid projection backing it (a SecurityException, by design - the
                    // same class of platform enforcement as the onStop() behavior itself, not a
                    // bug to route around). So this service just fully tears down on projection
                    // loss like any other stop; PhoneLinkAccessibilityService (not tied to any
                    // foreground-service-type restriction) owns the "paused, tap to resume"
                    // notification and the wake lock across the pause, since those don't depend
                    // on this specific service instance surviving.
                    runCatching { sendBroadcast(Intent(ACTION_PROJECTION_STOPPED).setPackage(packageName)) }
                    teardown()
                }
            }, handler)

            virtualDisplay = projection.createVirtualDisplay(
                "PhoneLinkAgentCapture",
                captureWidth, captureHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(requestReceiver, IntentFilter(ACTION_REQUEST_FRAME), RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(requestReceiver, IntentFilter(ACTION_REQUEST_FRAME))
            }
            android.util.Log.d("ScreenCaptureService", "Capture ready: ${captureWidth}x$captureHeight, receiver registered")
        }
        if (ok.isFailure) {
            android.util.Log.e("ScreenCaptureService", "Failed to start capture", ok.exceptionOrNull())
            stopSelf()
        }
    }

    /** Tries an immediate grab first (a frame may already be buffered), otherwise registers
     * this requestId as pending and waits for OnImageAvailableListener to fire - with a safety
     * timeout in case a frame genuinely never arrives (e.g. display stopped rendering). */
    private fun captureFrame(requestId: String) {
        val reader = imageReader
        if (reader == null) {
            android.util.Log.w("ScreenCaptureService", "captureFrame($requestId): imageReader null")
            broadcastError(requestId)
            return
        }
        val image = runCatching { reader.acquireLatestImage() }.getOrNull()
        if (image != null) {
            processImage(requestId, image)
            return
        }
        pendingRequestId = requestId
        handler.postDelayed(timeoutRunnable, 1500L)
    }

    private fun processImage(requestId: String, image: android.media.Image) {
        val path = runCatching {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * captureWidth
            val rawBitmap = Bitmap.createBitmap(
                captureWidth + rowPadding / pixelStride, captureHeight, Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)
            val bitmap = if (rowPadding == 0) rawBitmap else Bitmap.createBitmap(rawBitmap, 0, 0, captureWidth, captureHeight)

            val longEdge = maxOf(bitmap.width, bitmap.height)
            val scale = if (longEdge > MAX_LONG_EDGE) MAX_LONG_EDGE.toFloat() / longEdge else 1f
            val outBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else bitmap

            val file = File(cacheDir, "link_frame.jpg")
            FileOutputStream(file).use { out -> outBitmap.compress(Bitmap.CompressFormat.JPEG, 60, out) }
            file.absolutePath
        }.getOrNull()
        image.close()

        if (path == null) {
            android.util.Log.w("ScreenCaptureService", "captureFrame($requestId): JPEG encode failed")
            broadcastError(requestId)
            return
        }
        android.util.Log.d("ScreenCaptureService", "captureFrame($requestId): ok, wrote $path")
        val ready = Intent(ACTION_FRAME_READY).setPackage(packageName).apply {
            putExtra("requestId", requestId)
            putExtra("path", path)
        }
        sendBroadcast(ready)
    }

    private fun broadcastError(requestId: String) {
        sendBroadcast(Intent(ACTION_FRAME_ERROR).setPackage(packageName).putExtra("requestId", requestId))
    }

    @Volatile private var tornDown = false

    private fun teardown() {
        if (tornDown) return
        tornDown = true
        runCatching { unregisterReceiver(requestReceiver) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        stopSelf()
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Phone Link", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Linked device access is active")
            .setContentText("A linked device can see and control this phone")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "phone_link_capture"
        private const val NOTIFICATION_ID = 4824
        private const val MAX_LONG_EDGE = 1000

        const val ACTION_STOP_CAPTURE = "com.example.phonelinkagent.action.STOP_CAPTURE"
        const val ACTION_REQUEST_FRAME = "com.example.phonelinkagent.REQUEST_FRAME"
        const val ACTION_FRAME_READY = "com.example.phonelinkagent.FRAME_READY"
        const val ACTION_FRAME_ERROR = "com.example.phonelinkagent.FRAME_ERROR"
        const val ACTION_PROJECTION_STOPPED = "com.example.phonelinkagent.PROJECTION_STOPPED"

        // Fired by the paused notification's STOP action - a real, explicit way to end the
        // whole session (not just capture), listened for by PhoneLinkAccessibilityService's own
        // receiver so it can disconnect the websocket too, not just this service.
        const val ACTION_STOP_SESSION_REQUESTED = "com.example.phonelinkagent.STOP_SESSION_REQUESTED"
    }
}
