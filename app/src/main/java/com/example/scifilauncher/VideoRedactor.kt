package com.example.scifilauncher

import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** A screen region marked for redaction, in effect from [startMs] (elapsed recording time,
 * pause-excluded) to the end of the clip. */
data class BlurMark(val rect: RectF, val startMs: Long)

/** Bakes [BlurMark]s into a finished recording as a separate pass that only runs after the
 * recording stops - nothing is drawn on the real screen while recording (so marking a region
 * never blocks your own view of it), and clearing a mark before you stop removes it completely
 * since no pixel is actually redacted until this runs. Implemented as a raw MediaCodec
 * decode -> GLES composite -> MediaCodec encode pipeline (the same shape Android's own Grafika
 * sample uses for edited re-encodes) so no third-party video library is needed. Every call site
 * wraps this in runCatching: on any failure the original, unredacted recording is left in place
 * rather than lost. */
object VideoRedactor {
    private const val TAG = "VideoRedactor"

    /** [cropRect] (in the ORIGINAL, uncropped frame's pixel coordinates) and [marks] (also in
     * those same original coordinates) are baked in a single pass - marks are translated into
     * the cropped frame automatically, and any mark entirely outside the crop is dropped since
     * it would never have been visible anyway. Pass null/empty for whichever wasn't used; if
     * neither was, this is a no-op (the raw capture is already the final file). */
    fun bake(source: File, marks: List<BlurMark>, videoWidth: Int, videoHeight: Int, cropRect: Rect? = null): Boolean {
        if (marks.isEmpty() && cropRect == null) return true
        val dest = File(source.parentFile, source.nameWithoutExtension + "_tmp_redacted.mp4")
        val ok = runCatching {
            transcode(source, dest, marks, videoWidth, videoHeight, cropRect)
        }.onFailure { Log.e(TAG, "Redaction/crop transcode failed", it) }.isSuccess
        if (!ok) {
            runCatching { dest.delete() }
            return false
        }
        return runCatching {
            if (!source.delete()) throw IllegalStateException("could not delete original recording")
            if (!dest.renameTo(source)) throw IllegalStateException("could not rename redacted output")
            true
        }.getOrElse {
            Log.e(TAG, "Failed to swap in redacted file", it)
            runCatching { dest.delete() }
            false
        }
    }

    private fun transcode(source: File, dest: File, marks: List<BlurMark>, videoWidth: Int, videoHeight: Int, cropRect: Rect?) {
        val outputWidth = cropRect?.width() ?: videoWidth
        val outputHeight = cropRect?.height() ?: videoHeight
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)
        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                trackIndex = i
                inputFormat = fmt
                break
            }
        }
        if (trackIndex < 0 || inputFormat == null) throw IllegalStateException("no video track in recording")
        extractor.selectTrack(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

        val outputFormat = MediaFormat.createVideoFormat(mime, outputWidth, outputHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(mime)
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = EncoderInputSurface(encoder.createInputSurface())
        inputSurface.makeCurrent()
        encoder.start()

        val textureRender = RedactTextureRender()
        textureRender.setup()
        val surfaceTexture = SurfaceTexture(textureRender.textureId)
        val decoderSurface = Surface(surfaceTexture)

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFormat, decoderSurface, null, 0)
        decoder.start()

        var muxer: MediaMuxer? = null
        var muxerVideoTrack = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var encoderDone = false
        val frameAvailable = java.util.concurrent.atomic.AtomicBoolean(false)
        surfaceTexture.setOnFrameAvailableListener { frameAvailable.set(true) }

        try {
            while (!encoderDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outIndex >= 0) {
                        val render = bufferInfo.size > 0
                        val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val presentUs = bufferInfo.presentationTimeUs
                        decoder.releaseOutputBuffer(outIndex, render)
                        if (render) {
                            var waited = 0
                            while (!frameAvailable.get() && waited < 1000) { Thread.sleep(5); waited += 5 }
                            frameAvailable.set(false)
                            surfaceTexture.updateTexImage()
                            val presentMs = presentUs / 1000
                            val activeMarks = marks.filter { it.startMs <= presentMs }
                            textureRender.drawFrame(surfaceTexture, activeMarks, videoWidth, videoHeight, cropRect, outputWidth, outputHeight)
                            inputSurface.setPresentationTime(presentUs * 1000)
                            inputSurface.swapBuffers()
                        }
                        if (eos) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                var encoderOutputAvailable = true
                while (encoderOutputAvailable) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> encoderOutputAvailable = false
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                            muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIndex >= 0 -> {
                            val encodedData = encoder.getOutputBuffer(outIndex)!!
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0 && muxerStarted) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer!!.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoderDone = true
                                encoderOutputAvailable = false
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { decoderSurface.release() }
            runCatching { surfaceTexture.release() }
            runCatching { inputSurface.release() }
            runCatching { extractor.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
        if (!muxerStarted) throw IllegalStateException("muxer never started - no frames were encoded")
    }
}

/** Wraps the encoder's input Surface with its own EGL context so GLES draw calls land directly
 * in what the encoder consumes as source frames. */
private class EncoderInputSurface(private val surface: Surface) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("unable to get EGL14 display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) throw RuntimeException("unable to initialize EGL14")

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttribs, 0)
    }

    fun makeCurrent() {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    fun swapBuffers() {
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        surface.release()
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

/** Draws the decoded frame (an external OES texture) full-screen, then re-draws a blurred
 * (not blacked-out) version of the video itself over each region active by that frame's
 * timestamp, for every [BlurMark]. */
private class RedactTextureRender {
    var textureId = -1
        private set

    private var oesProgram = 0
    private var oesPosHandle = 0
    private var oesTexHandle = 0
    private var oesMvpHandle = 0
    private var oesTexMatrixHandle = 0

    private var blurProgram = 0
    private var blurPosHandle = 0
    private var blurTexHandle = 0
    private var blurMvpHandle = 0
    private var blurTexMatrixHandle = 0
    private var blurTexelSizeHandle = 0

    private val mvpMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val texMatrix = FloatArray(16)

    fun setup() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        oesProgram = buildProgram(OES_VERTEX_SHADER, OES_FRAGMENT_SHADER)
        oesPosHandle = GLES20.glGetAttribLocation(oesProgram, "aPosition")
        oesTexHandle = GLES20.glGetAttribLocation(oesProgram, "aTexCoord")
        oesMvpHandle = GLES20.glGetUniformLocation(oesProgram, "uMVPMatrix")
        oesTexMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix")

        blurProgram = buildProgram(BLUR_VERTEX_SHADER, BLUR_FRAGMENT_SHADER)
        blurPosHandle = GLES20.glGetAttribLocation(blurProgram, "aPosition")
        blurTexHandle = GLES20.glGetAttribLocation(blurProgram, "aTexCoord")
        blurMvpHandle = GLES20.glGetUniformLocation(blurProgram, "uMVPMatrix")
        blurTexMatrixHandle = GLES20.glGetUniformLocation(blurProgram, "uTexMatrix")
        blurTexelSizeHandle = GLES20.glGetUniformLocation(blurProgram, "uTexelSize")
    }

    /** [cropRect] is in the ORIGINAL (uncropped) source frame's pixel coordinates - when
     * present, only that sub-rectangle of the decoded texture is sampled, scaled to fill the
     * whole (smaller) output viewport, and [marks] are translated into the cropped frame before
     * being drawn so a blur region lands in the same place relative to the cropped picture. */
    fun drawFrame(
        surfaceTexture: SurfaceTexture,
        marks: List<BlurMark>,
        sourceWidth: Int,
        sourceHeight: Int,
        cropRect: Rect?,
        outputWidth: Int,
        outputHeight: Int
    ) {
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val u0 = (cropRect?.left ?: 0) / sourceWidth.toFloat()
        val v0 = (cropRect?.top ?: 0) / sourceHeight.toFloat()
        val u1 = (cropRect?.right ?: sourceWidth) / sourceWidth.toFloat()
        val v1 = (cropRect?.bottom ?: sourceHeight) / sourceHeight.toFloat()
        val quadVertices = floatBufferOf(floatArrayOf(
            // x, y, z, u, v
            -1f, -1f, 0f, u0, v0,
             1f, -1f, 0f, u1, v0,
            -1f,  1f, 0f, u0, v1,
             1f,  1f, 0f, u1, v1
        ))

        surfaceTexture.getTransformMatrix(texMatrix)
        GLES20.glUseProgram(oesProgram)
        quadVertices.position(0)
        GLES20.glVertexAttribPointer(oesPosHandle, 3, GLES20.GL_FLOAT, false, 20, quadVertices)
        GLES20.glEnableVertexAttribArray(oesPosHandle)
        quadVertices.position(3)
        GLES20.glVertexAttribPointer(oesTexHandle, 2, GLES20.GL_FLOAT, false, 20, quadVertices)
        GLES20.glEnableVertexAttribArray(oesTexHandle)
        GLES20.glUniformMatrix4fv(oesMvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(oesTexMatrixHandle, 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(oesPosHandle)
        GLES20.glDisableVertexAttribArray(oesTexHandle)

        val visibleMarks = marks.mapNotNull { mark ->
            if (cropRect != null) {
                if (!RectF(cropRect).intersects(mark.rect.left, mark.rect.top, mark.rect.right, mark.rect.bottom)) return@mapNotNull null
                RectF(mark.rect).apply { offset(-cropRect.left.toFloat(), -cropRect.top.toFloat()) }
            } else {
                mark.rect
            }
        }
        if (visibleMarks.isNotEmpty()) {
            GLES20.glUseProgram(blurProgram)
            visibleMarks.forEach { rect ->
                drawBlurRect(rect, outputWidth, outputHeight, sourceWidth, sourceHeight, cropRect)
            }
        }
    }

    /** Redaction is a real multi-tap blur sampled from the source video itself (a 9x9 grid,
     * ~5px apart in source-video pixels) - not a flat color fill - so the region reads as
     * genuinely out-of-focus/frosted rather than a solid blackout box. [rect] is in the
     * CROPPED/output frame's pixel space (for where the quad is positioned on screen);
     * its texture coordinates are derived by mapping back into the ORIGINAL source frame (for
     * what gets sampled), undoing the same crop offset applied when the mark was translated. */
    private fun drawBlurRect(rect: RectF, outputWidth: Int, outputHeight: Int, sourceWidth: Int, sourceHeight: Int, cropRect: Rect?) {
        val left = (rect.left / outputWidth) * 2f - 1f
        val right = (rect.right / outputWidth) * 2f - 1f
        val top = 1f - (rect.top / outputHeight) * 2f
        val bottom = 1f - (rect.bottom / outputHeight) * 2f

        val offsetX = cropRect?.left?.toFloat() ?: 0f
        val offsetY = cropRect?.top?.toFloat() ?: 0f
        val u0 = (rect.left + offsetX) / sourceWidth
        val u1 = (rect.right + offsetX) / sourceWidth
        val v0 = (rect.top + offsetY) / sourceHeight
        val v1 = (rect.bottom + offsetY) / sourceHeight

        val verts = floatBufferOf(floatArrayOf(
            // x, y, z, u, v
            left, bottom, 0f, u0, v1,
            right, bottom, 0f, u1, v1,
            left, top, 0f, u0, v0,
            right, top, 0f, u1, v0
        ))
        verts.position(0)
        GLES20.glVertexAttribPointer(blurPosHandle, 3, GLES20.GL_FLOAT, false, 20, verts)
        GLES20.glEnableVertexAttribArray(blurPosHandle)
        verts.position(3)
        GLES20.glVertexAttribPointer(blurTexHandle, 2, GLES20.GL_FLOAT, false, 20, verts)
        GLES20.glEnableVertexAttribArray(blurTexHandle)
        GLES20.glUniformMatrix4fv(blurMvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(blurTexMatrixHandle, 1, false, texMatrix, 0)
        GLES20.glUniform2f(blurTexelSizeHandle, BLUR_STEP_PX / sourceWidth, BLUR_STEP_PX / sourceHeight)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(blurPosHandle)
        GLES20.glDisableVertexAttribArray(blurTexHandle)
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("program link failed: $log")
        }
        return program
    }

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("shader compile failed: $log")
        }
        return shader
    }

    companion object {
        private const val OES_VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """
        private const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
        private const val BLUR_VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """
        // 9x9 box-sample blur (81 taps) around each pixel, uTexelSize being one sample step in
        // texture UV units - a single-pass approximation of a real gaussian blur. Runs during
        // the offline bake pass (not live), so the extra sampling cost per pixel is fine.
        private const val BLUR_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform vec2 uTexelSize;
            void main() {
                vec4 sum = vec4(0.0);
                for (int x = -4; x <= 4; x++) {
                    for (int y = -4; y <= 4; y++) {
                        sum += texture2D(sTexture, vTexCoord + vec2(float(x), float(y)) * uTexelSize);
                    }
                }
                gl_FragColor = sum / 81.0;
            }
        """
    }
}

private const val BLUR_STEP_PX = 5f

private fun floatBufferOf(data: FloatArray): FloatBuffer {
    return ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(data)
        position(0)
    }
}
