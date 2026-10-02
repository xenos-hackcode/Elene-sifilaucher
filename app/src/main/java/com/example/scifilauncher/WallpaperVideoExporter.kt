package com.example.scifilauncher

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Encodes branded frames with OpenGL, fixed presentation times and bounded EOS draining. */
object WallpaperVideoExporter {
    private const val WIDTH = 720
    private const val HEIGHT = 1280
    private const val FPS = 24
    private const val FRAME_COUNT = FPS * 6

    suspend fun exportMp4(context: Context, id: String, label: String, themeColorArgb: Int): Boolean {
        if (id !in WallpaperExporter.ANIMATED_IDS || android.os.Build.VERSION.SDK_INT < 29) return false
        var file: File? = null
        var encoder: MediaCodec? = null
        var input: Surface? = null
        var muxer: MediaMuxer? = null
        var started = false
        val renderer = WallpaperEncoderSurface()
        try {
            val cancellation = currentCoroutineContext()
            cancellation.ensureActive()
            val temp = File.createTempFile("xenos_wallpaper_", ".mp4", context.cacheDir)
            file = temp
            val codec = MediaCodec.createEncoderByType("video/avc")
            encoder = codec
            codec.configure(MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            input = surface
            renderer.prepare(surface)
            codec.start()
            val output = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = output
            var track = -1
            val info = MediaCodec.BufferInfo()
            fun drain(eos: Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 15_000L
                while (true) {
                    cancellation.ensureActive()
                    check(SystemClock.elapsedRealtime() < deadline) { "Video encoder timed out" }
                    val index = codec.dequeueOutputBuffer(info, 10_000L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!started)
                            track = output.addTrack(codec.outputFormat)
                            output.start()
                            started = true
                        }
                        index >= 0 -> {
                            try {
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                    check(started)
                                    val data = checkNotNull(codec.getOutputBuffer(index))
                                    data.position(info.offset)
                                    data.limit(info.offset + info.size)
                                    output.writeSampleData(track, data, info)
                                }
                            } finally { codec.releaseOutputBuffer(index, false) }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }
            for (frame in 0 until FRAME_COUNT) {
                cancellation.ensureActive()
                drain(false)
                val bitmap = WallpaperExporter.renderFrame(id, themeColorArgb, WIDTH, HEIGHT, frame / FRAME_COUNT.toFloat())
                try { renderer.draw(bitmap, frame * 1_000_000_000L / FPS) }
                finally { bitmap.recycle() }
                drain(false)
            }
            codec.signalEndOfInputStream()
            drain(true)
            check(started)
            output.stop()
            started = false
            output.release()
            muxer = null
            cancellation.ensureActive()
            return WallpaperMediaStore.save(context, label, true) { destination ->
                temp.inputStream().use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        cancellation.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        destination.write(buffer, 0, count)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        } finally {
            runCatching { renderer.close() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { input?.release() }
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            file?.delete()
        }
    }
}
