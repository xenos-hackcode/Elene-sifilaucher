package com.example.scifilauncher

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Headless single-shot photo capture - no preview surface, nothing shown on screen, since the
 * whole point is capturing whoever's holding the phone without tipping them off. Uses Camera2
 * directly (no existing camera code in this app to reuse - the only prior camera usage,
 * SettingsUtils.setFlashlight, only touches CameraCharacteristics for the torch, never opens a
 * capture session). Every exit path funnels through one finish() that closes the camera device
 * exactly once - camera handles are exclusive per-process, so a leaked one blocks every other
 * app's camera access until this process dies, not just this feature. */
object SilentCameraCapture {

    /** [onResult] is always called exactly once, with the saved file or null on any failure -
     * never leaves the caller hanging. Runs entirely on its own HandlerThread so it doesn't
     * need the caller to already be on a background thread. */
    fun captureFrontFacing(context: Context, outputDir: File, onResult: (File?) -> Unit) {
        val thread = HandlerThread("SilentCameraCapture").apply { start() }
        val handler = Handler(thread.looper)
        var finished = false
        var openDevice: CameraDevice? = null

        fun finish(result: File?) {
            if (finished) return
            finished = true
            runCatching { openDevice?.close() }
            openDevice = null
            onResult(result)
            runCatching { thread.quitSafely() }
        }

        runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: return@runCatching finish(null)

            val chars = manager.getCameraCharacteristics(cameraId)
            val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
            // Smallest reasonable size, not the sensor's max - this is a covert identity
            // snapshot, not a photo the user will ever zoom into, and a huge JPEG is slower to
            // capture and write for no benefit here.
            val size = sizes?.minByOrNull { it.width.toLong() * it.height } ?: return@runCatching finish(null)

            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            reader.setOnImageAvailableListener({ r ->
                val image = runCatching { r.acquireLatestImage() }.getOrNull()
                if (image == null) {
                    finish(null)
                    return@setOnImageAvailableListener
                }
                val savedFile = runCatching {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    if (!outputDir.exists()) outputDir.mkdirs()
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val file = File(outputDir, "intruder_$stamp.jpg")
                    FileOutputStream(file).use { it.write(bytes) }
                    file
                }.getOrNull()
                image.close()
                finish(savedFile)
            }, handler)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    openDevice = camera
                    runCatching {
                        camera.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    runCatching {
                                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(reader.surface)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        }.build()
                                        session.capture(request, null, handler)
                                    }.onFailure { finish(null) }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    finish(null)
                                }
                            },
                            handler
                        )
                    }.onFailure { finish(null) }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    openDevice = camera
                    finish(null)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    openDevice = camera
                    finish(null)
                }
                override fun onClosed(camera: CameraDevice) {}
            }, handler)
        }.onFailure { finish(null) }
    }
}
