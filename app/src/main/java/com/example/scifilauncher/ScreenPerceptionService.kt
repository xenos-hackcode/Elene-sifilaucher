package com.example.scifilauncher

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

/** Elene's visual perception surface - a "dumb" isolated capture box, deliberately holding no
 * decision logic of its own. Runs in the same isolated `:recorder` process as
 * ScreenRecordService and for the same reason (a MediaProjection/native crash here must not
 * take down the whole home-screen launcher process). All orchestration - deciding when to
 * capture, calling the backend, dispatching gestures - lives in ScifiAccessibilityService,
 * which is a DIFFERENT process and can't reach this one directly, so frames are handed over via
 * broadcast + a fixed cache file, the same shape already used for TTS mp3 bytes
 * (playOverlayAudioBytes in ScifiAccessibilityService.kt).
 *
 * mode == "single": stops itself right after delivering the first frame.
 * mode == "loop": stays alive across many REQUEST_FRAME cycles until ACTION_STOP_CAPTURE. */
class ScreenPerceptionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var singleShot = false
    private var frameDelivered = false

    private val handler = Handler(Looper.getMainLooper())

    private val requestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val requestId = intent?.getStringExtra("requestId") ?: return
            captureFrame(requestId, retriesLeft = 1)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_CAPTURE) {
            teardown()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        if (intent == null || data == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        singleShot = intent.getStringExtra("mode") != "loop"

        startForeground(NOTIFICATION_ID, buildNotification())
        startCapture(resultCode, data)
        return START_NOT_STICKY
    }

    // Same defensive shape as ScreenRecordService.startRecording - every MediaProjection/
    // VirtualDisplay call here is notoriously finicky across OEM/Android combos, and an
    // uncaught exception in this process would still surface as "SciFiLauncher keeps stopping"
    // to the user even though it's isolated from the main launcher process.
    private fun startCapture(resultCode: Int, data: Intent) {
        val ok = runCatching {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
            captureWidth = metrics.widthPixels
            captureHeight = metrics.heightPixels

            val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data)
            mediaProjection = projection

            // Must register before createVirtualDisplay() on newer Android or it throws
            // IllegalStateException - same real, confirmed gotcha as ScreenRecordService.
            // Also covers the system revoking the projection out from under us (user taps the
            // system's own screen-capture status-bar chip) - without this a live game loop
            // would keep requesting frames from a dead pipe instead of aborting cleanly.
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // Confirmed real bug, found live: teardown()'s own mediaProjection?.stop()
                    // call re-triggers this SAME callback re-entrantly (calling .stop() on a
                    // MediaProjection invokes its own registered Callback.onStop(), even for a
                    // self-initiated stop, not just a system-initiated one) - and an unguarded
                    // sendBroadcast() throwing here would have skipped teardown() entirely,
                    // leaving the service (and its foreground notification) stuck alive forever
                    // even after the OS had already torn down the real projection underneath it
                    // (confirmed via a live device left in exactly that state - process and
                    // notification still alive minutes later, MediaProjection/BufferQueue logs
                    // showing the OS side was long gone). teardown() below is now idempotent and
                    // this whole body is defensive, so neither an exception nor re-entrancy can
                    // leave the service stuck again.
                    runCatching { sendBroadcast(Intent(ACTION_PROJECTION_STOPPED).setPackage(packageName)) }
                    teardown()
                }
            }, handler)

            virtualDisplay = projection.createVirtualDisplay(
                "SciFiLauncherPerception",
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
        }
        if (ok.isFailure) {
            android.util.Log.e("ScreenPerception", "Failed to start capture", ok.exceptionOrNull())
            stopSelf()
        }
    }

    /** acquireLatestImage() can genuinely return null on the very first request, right after
     * createVirtualDisplay() - the display hasn't produced a frame yet. One retry after a short
     * delay covers this real race without silently failing the whole capture. */
    private fun captureFrame(requestId: String, retriesLeft: Int) {
        val reader = imageReader
        if (reader == null) {
            broadcastError(requestId)
            return
        }
        val image = runCatching { reader.acquireLatestImage() }.getOrNull()
        if (image == null) {
            if (retriesLeft > 0) {
                handler.postDelayed({ captureFrame(requestId, retriesLeft - 1) }, 200L)
            } else {
                broadcastError(requestId)
            }
            return
        }
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

            val file = File(cacheDir, "perception_frame.jpg")
            FileOutputStream(file).use { out -> outBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out) }
            Triple(file.absolutePath, outBitmap.width, outBitmap.height)
        }.getOrNull()
        image.close()

        if (path == null) {
            broadcastError(requestId)
            return
        }
        val (filePath, frameWidth, frameHeight) = path
        val ready = Intent(ACTION_FRAME_READY).setPackage(packageName).apply {
            putExtra("requestId", requestId)
            putExtra("path", filePath)
            putExtra("frameWidth", frameWidth)
            putExtra("frameHeight", frameHeight)
            putExtra("fullWidth", captureWidth)
            putExtra("fullHeight", captureHeight)
        }
        sendBroadcast(ready)
        frameDelivered = true
        if (singleShot) teardown()
    }

    private fun broadcastError(requestId: String) {
        sendBroadcast(Intent(ACTION_FRAME_ERROR).setPackage(packageName).putExtra("requestId", requestId))
        if (singleShot) teardown()
    }

    // Idempotent - can be safely re-entered (mediaProjection?.stop() below re-triggers this
    // same service's own onStop() callback synchronously, see the comment there) without
    // double-releasing anything or calling stopSelf() from a bad state.
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
                NotificationChannel(CHANNEL_ID, "Screen Perception", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Xenos is looking at your screen")
            .setContentText(if (singleShot) "One-time screen description" else "Watching to help play")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "screen_perception"
        private const val NOTIFICATION_ID = 4823
        private const val MAX_LONG_EDGE = 1280

        const val ACTION_STOP_CAPTURE = "com.example.scifilauncher.action.STOP_PERCEPTION"
        const val ACTION_REQUEST_FRAME = "com.example.scifilauncher.PERCEPTION_REQUEST_FRAME"
        const val ACTION_FRAME_READY = "com.example.scifilauncher.PERCEPTION_FRAME_READY"
        const val ACTION_FRAME_ERROR = "com.example.scifilauncher.PERCEPTION_FRAME_ERROR"
        const val ACTION_PROJECTION_STOPPED = "com.example.scifilauncher.PERCEPTION_PROJECTION_STOPPED"
    }
}
