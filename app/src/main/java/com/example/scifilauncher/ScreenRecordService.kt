package com.example.scifilauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AnnotationTool { NONE, PENCIL, BLUR }

/** MEDIA (other apps' playback audio) and MIC_AND_MEDIA aren't implemented yet - they need a
 * raw AudioPlaybackCaptureConfiguration + MediaCodec + MediaMuxer pipeline instead of
 * MediaRecorder's built-in audio source, since MediaRecorder has no playback-capture source.
 * Only NONE/MIC are wired up and exposed in the setup UI for now. */
enum class RecordAudioMode { NONE, MIC }

/** Real screen recording via MediaProjection - Android forces a system consent dialog every
 * time this is requested (no app, Device Owner included, can bypass that), so the permission
 * flow here is the launcher just triggering that real system prompt, not skipping it. */
class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var annotationView: AnnotationOverlayView? = null
    private var annotationParams: WindowManager.LayoutParams? = null
    private var toolbarView: RecordToolbarView? = null
    private var toolbarParams: WindowManager.LayoutParams? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var cropRect: android.graphics.Rect? = null

    // Recording clock used to timestamp blur marks - excludes paused time so a mark's stored
    // startMs lines up with the actual video timeline MediaRecorder produces (pause/resume
    // seamlessly omits the paused interval from the encoded output).
    private var recordingClockBase = 0L
    private var pausedAccumMs = 0L
    private var pauseStartedAt = 0L
    @Volatile private var isPaused = false

    private fun nowRecordingMs(): Long {
        val activeElapsed = if (isPaused) pauseStartedAt else android.os.SystemClock.elapsedRealtime()
        return activeElapsed - recordingClockBase - pausedAccumMs
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_AND_FINISH) {
            stopRecordingAndFinish()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        if (intent == null || data == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        val audioMode = runCatching {
            RecordAudioMode.valueOf(intent.getStringExtra("audioMode") ?: RecordAudioMode.NONE.name)
        }.getOrDefault(RecordAudioMode.NONE)
        @Suppress("DEPRECATION")
        cropRect = intent.getParcelableExtra("cropRect") as? android.graphics.Rect

        startForeground(NOTIFICATION_ID, buildNotification())
        // A screen recording that stops the moment the screen would normally dim/sleep isn't
        // useful - held only while actively recording, released in onDestroy.
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SciFiLauncher:ScreenRecord")
        runCatching { wakeLock?.acquire(30 * 60 * 1000L) }

        startRecording(resultCode, data, audioMode)
        return START_STICKY
    }

    // Every single MediaProjection/MediaRecorder/VirtualDisplay call here used to be
    // unguarded - if any of them threw (and this API surface is notoriously finicky across
    // OEM/Android version combos), the exception was uncaught and crashed this whole process.
    // Since this service ran in the SAME process as MainActivity, that crash took the
    // launcher down with it ("SciFiLauncher keeps stopping"), on the phone's own home app.
    // Wrapping this doesn't just avoid a log message - it's the difference between "the
    // recording didn't start" and "the phone has no home screen until this is fixed".
    private fun startRecording(resultCode: Int, data: Intent, audioMode: RecordAudioMode) {
        val ok = runCatching {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
            videoWidth = metrics.widthPixels
            videoHeight = metrics.heightPixels

            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SciFiLauncher")
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "screen_$stamp.mp4")
            outputFile = file

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            // Audio source must be set before setOutputFormat(); its encoder is set after,
            // alongside the video encoder - MediaRecorder handles muxing both internally.
            if (audioMode == RecordAudioMode.MIC) {
                runCatching { recorder.setAudioSource(MediaRecorder.AudioSource.MIC) }
                    .onFailure { android.util.Log.e("ScreenRecord", "Mic audio source unavailable, recording video-only", it) }
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (audioMode == RecordAudioMode.MIC) {
                runCatching {
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    recorder.setAudioEncodingBitRate(128_000)
                    recorder.setAudioSamplingRate(44100)
                }
            }
            recorder.setVideoSize(metrics.widthPixels, metrics.heightPixels)
            recorder.setVideoEncodingBitRate(8_000_000)
            recorder.setVideoFrameRate(30)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            mediaRecorder = recorder

            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data)
            mediaProjection = projection

            // Newer Android requires a registered callback before createVirtualDisplay() -
            // without one it throws IllegalStateException immediately (confirmed via an
            // actual on-device crash log: "Must register a callback before starting capture").
            // onStop() also covers the case where the SYSTEM revokes the projection out from
            // under us (e.g. user stops it from the system's own recording indicator) - without
            // this, the service would keep trying to write to a dead virtual display.
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))

            virtualDisplay = projection.createVirtualDisplay(
                "SciFiLauncherScreenRecord",
                metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface, null, null
            )

            recorder.start()
            recordingClockBase = android.os.SystemClock.elapsedRealtime()
            pausedAccumMs = 0L
            isPaused = false
        }
        if (ok.isFailure) {
            android.util.Log.e("ScreenRecord", "Failed to start recording", ok.exceptionOrNull())
            stopSelf()
            return
        }
        // Annotation/redaction overlay is a bonus on top of a working recording, not a
        // prerequisite for one - if this fails for any reason, the recording itself must keep
        // running rather than getting torn down over a toolbar that couldn't show.
        runCatching { showRecordingOverlays() }
            .onFailure { android.util.Log.e("ScreenRecord", "Failed to show annotation overlay", it) }
    }

    /** A small floating toolbar (pencil to circle/highlight things, blur to redact a region)
     * shown only while actively recording. Both draw directly onto a real screen overlay, so
     * MediaProjection's mirror of the display captures them exactly as seen - the blur tool
     * isn't a video edit, it's a live hashed panel drawn over whatever region you mark, so
     * the actual content underneath is never in the recorded frames at all. */
    private fun showRecordingOverlays() {
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density

        val annotation = AnnotationOverlayView(this, nowMs = { nowRecordingMs() })
        val annotationLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(annotation, annotationLp)
        annotationView = annotation
        annotationParams = annotationLp

        val toolbar = RecordToolbarView(
            this,
            onPencilTap = { setAnnotationTool(if (annotation.tool == AnnotationTool.PENCIL) AnnotationTool.NONE else AnnotationTool.PENCIL) },
            onBlurTap = { setAnnotationTool(if (annotation.tool == AnnotationTool.BLUR) AnnotationTool.NONE else AnnotationTool.BLUR) },
            onUndoTap = { annotation.undoPencil() },
            onRedoTap = { annotation.redoPencil() },
            onClearTap = { annotation.clearAll() },
            onPauseResumeTap = { togglePauseResume() },
            onStopTap = { stopRecordingAndFinish() },
            onDragBy = { dx, dy -> dragToolbarBy(dx, dy) }
        )
        // A compact estimate just for the initial resting spot (bottom-center-ish) - not exact,
        // doesn't need to be since it's freely draggable the instant it appears.
        val estW = (210 * density).toInt()
        val estH = (56 * density).toInt()
        val toolbarLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((videoWidth - estW) / 2).coerceAtLeast(0)
            y = (videoHeight - estH - (48 * density).toInt()).coerceAtLeast(0)
        }
        wm.addView(toolbar, toolbarLp)
        toolbarView = toolbar
        toolbarParams = toolbarLp
    }

    /** Drag-to-move for the toolbar - same TOP|START, params.x/y += dx/dy pattern as the Elene
     * bubble overlay, so a finger-follow drag behaves identically across both overlays. */
    private fun dragToolbarBy(dx: Int, dy: Int) {
        val params = toolbarParams ?: return
        val view = toolbarView ?: return
        params.x += dx
        params.y += dy
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { wm.updateViewLayout(view, params) }
    }

    /** The annotation layer is FLAG_NOT_TOUCHABLE (pass-through) whenever no tool is active,
     * so it never gets in the way of actually using your apps on camera - it only starts
     * intercepting touches (to draw/mark a region) while pencil or blur is switched on. */
    private fun setAnnotationTool(tool: AnnotationTool) {
        val view = annotationView
        val params = annotationParams
        android.util.Log.d("ScreenRecord", "setAnnotationTool($tool) view=${view != null} params=${params != null}")
        if (view == null || params == null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        view.tool = tool
        toolbarView?.setActive(tool)
        params.flags = if (tool == AnnotationTool.NONE) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { wm.updateViewLayout(view, params) }
            .onFailure { android.util.Log.e("ScreenRecord", "updateViewLayout failed for annotation tool switch", it) }
    }

    private fun hideRecordingOverlays() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        toolbarView?.let { runCatching { wm.removeView(it) } }
        toolbarView = null
        annotationView?.let { runCatching { wm.removeView(it) } }
        annotationView = null
    }

    /** MediaRecorder.pause()/resume() are real APIs since API 24 (this app's minSdk) - the
     * paused interval is seamlessly excluded from the encoded video's own timeline, which is
     * why nowRecordingMs() tracks paused time separately rather than using wall-clock time. */
    private fun togglePauseResume() {
        val recorder = mediaRecorder ?: return
        runCatching {
            if (isPaused) {
                recorder.resume()
                pausedAccumMs += android.os.SystemClock.elapsedRealtime() - pauseStartedAt
                isPaused = false
            } else {
                recorder.pause()
                pauseStartedAt = android.os.SystemClock.elapsedRealtime()
                isPaused = true
            }
            toolbarView?.setPaused(isPaused)
            updateNotification(if (isPaused) "Recording paused" else "Recording your screen")
        }.onFailure { android.util.Log.e("ScreenRecord", "Pause/resume failed", it) }
    }

    /** The single exit path for ending a recording - used by both the in-overlay STOP button
     * and the Quick Settings toggle (routed here via ACTION_STOP_AND_FINISH rather than a raw
     * stopService(), which would jump straight to onDestroy() with no chance to bake blur marks
     * first). Finalizes the raw capture, then - only if any blur marks were actually drawn -
     * runs the redaction pass on a background thread before the service actually stops, so the
     * process stays alive long enough to finish writing the redacted file. */
    private fun stopRecordingAndFinish() {
        val marks = annotationView?.getBlurMarks() ?: emptyList()
        val crop = cropRect
        runCatching { hideRecordingOverlays() }
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        val file = outputFile
        if (file != null && (marks.isNotEmpty() || crop != null)) {
            updateNotification("Processing recording…")
            Thread {
                val ok = runCatching { VideoRedactor.bake(file, marks, videoWidth, videoHeight, crop) }.getOrElse { false }
                if (!ok) android.util.Log.e("ScreenRecord", "Blur/crop baking failed - keeping the original recording")
                runCatching {
                    android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
                }
                stopSelf()
            }.start()
        } else {
            file?.let { f ->
                runCatching { android.media.MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), null, null) }
            }
            stopSelf()
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(text)
            .setContentText(if (text.startsWith("Processing")) "Applying blur, please wait" else "Saving to Movies/SciFiLauncher")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen Recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording your screen")
            .setContentText("Saving to Movies/SciFiLauncher")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        runCatching { hideRecordingOverlays() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.release() }
        virtualDisplay?.release()
        mediaProjection?.stop()
        outputFile?.let { file ->
            runCatching {
                android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "screen_record"
        private const val NOTIFICATION_ID = 4822
        const val ACTION_STOP_AND_FINISH = "com.example.scifilauncher.action.STOP_RECORDING"
    }
}

/** Full-screen drawing surface for the pencil and blur tools. Pass-through (not touchable) by
 * default so recording doesn't block using your apps - only intercepts touches while a tool
 * is actively selected (toggled by the service via WindowManager flags, not by this view).
 *
 * Pencil marks are drawn live and captured directly (this overlay is a real on-screen window,
 * so MediaProjection's screen mirror picks it up exactly like any other on-screen content) -
 * that's intentional, you're meant to see and record your own highlighting in real time.
 *
 * Blur is different on purpose: nothing is ever drawn opaque on the real screen for it, live or
 * committed, so marking a region never blocks your own view of what's underneath while you're
 * still recording. A blur mark is only pixel data in memory (a rect + the recording timestamp
 * it was placed at) until the service bakes it into the finished file after you stop - which is
 * also why clearing marks before you stop removes them completely, nothing was ever burned in. */
private class AnnotationOverlayView(
    context: android.content.Context,
    private val nowMs: () -> Long
) : View(context) {
    var tool: AnnotationTool = AnnotationTool.NONE

    private val pencilPaths = mutableListOf<Path>()
    private val pencilRedoStack = mutableListOf<Path>()
    private var currentPath: Path? = null
    private val pencilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 205, 0)
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val blurMarks = mutableListOf<BlurMark>()
    private var currentBlurRect: RectF? = null
    private var blurStart: PointF? = null
    // Thin outline only, while actively dragging - just enough to see what you're marking out.
    // No fill, and it disappears the instant you lift your finger; nothing about a committed
    // blur mark is ever rendered on the real screen.
    private val blurOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 205, 0)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    fun clearAll() {
        pencilPaths.clear()
        pencilRedoStack.clear()
        blurMarks.clear()
        currentBlurRect = null
        invalidate()
    }

    /** Removes only the most recently drawn pencil stroke (not blur - blur marks are invisible
     * live anyway, so there's nothing to visually "rewind" for those; CLEAR already covers
     * discarding a blur mark you didn't mean to draw). A new stroke drawn after an undo
     * discards the redo stack, same as any normal undo/redo. */
    fun undoPencil() {
        val last = pencilPaths.removeLastOrNull() ?: return
        pencilRedoStack.add(last)
        invalidate()
    }

    fun redoPencil() {
        val last = pencilRedoStack.removeLastOrNull() ?: return
        pencilPaths.add(last)
        invalidate()
    }

    fun getBlurMarks(): List<BlurMark> = blurMarks.toList()

    override fun onDraw(canvas: Canvas) {
        currentBlurRect?.let { canvas.drawRect(it, blurOutlinePaint) }
        pencilPaths.forEach { canvas.drawPath(it, pencilPaint) }
        currentPath?.let { canvas.drawPath(it, pencilPaint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            android.util.Log.d("ScreenRecord", "AnnotationOverlayView ACTION_DOWN, tool=$tool at (${event.x}, ${event.y})")
        }
        when (tool) {
            AnnotationTool.PENCIL -> {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        currentPath = Path().apply { moveTo(event.x, event.y) }
                        pencilRedoStack.clear()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        currentPath?.lineTo(event.x, event.y)
                        invalidate()
                    }
                    MotionEvent.ACTION_UP -> {
                        currentPath?.let { pencilPaths.add(it) }
                        currentPath = null
                        invalidate()
                    }
                }
                return true
            }
            AnnotationTool.BLUR -> {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        blurStart = PointF(event.x, event.y)
                        currentBlurRect = RectF(event.x, event.y, event.x, event.y)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        blurStart?.let { start ->
                            currentBlurRect = RectF(
                                kotlin.math.min(start.x, event.x), kotlin.math.min(start.y, event.y),
                                kotlin.math.max(start.x, event.x), kotlin.math.max(start.y, event.y)
                            )
                        }
                        invalidate()
                    }
                    MotionEvent.ACTION_UP -> {
                        currentBlurRect?.let {
                            val mark = BlurMark(RectF(it), nowMs())
                            blurMarks.add(mark)
                            android.util.Log.d("ScreenRecord", "Blur mark committed: $mark, total=${blurMarks.size}")
                        }
                        currentBlurRect = null
                        blurStart = null
                        invalidate()
                    }
                }
                return true
            }
            AnnotationTool.NONE -> return false
        }
    }
}

/** Freely draggable (grab the "≡" handle) and collapsible down to a small tab - the same
 * drag-to-move + tap-to-toggle interaction the Elene bubble uses elsewhere in this app, applied
 * here so the toolbar never has to sit somewhere inconvenient or block the exact thing you're
 * trying to record. Compact icon-only buttons throughout: pencil/undo/redo/blur/clear on top,
 * pause and stop below. */
private class RecordToolbarView(
    context: android.content.Context,
    onPencilTap: () -> Unit,
    onBlurTap: () -> Unit,
    onUndoTap: () -> Unit,
    onRedoTap: () -> Unit,
    onClearTap: () -> Unit,
    onPauseResumeTap: () -> Unit,
    onStopTap: () -> Unit,
    onDragBy: (dx: Int, dy: Int) -> Unit
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val pencilBtn: TextView
    private val blurBtn: TextView
    private val pauseBtn: TextView
    private val expandedContent: LinearLayout
    private val collapsedChip: TextView
    private var collapsed = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.argb(210, 12, 12, 12))

        val rowParams = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT) }

        val handle = TextView(context).apply {
            text = "≡"
            textSize = 15f
            setTextColor(Color.GRAY)
            val padH = (10 * density).toInt()
            setPadding(padH, (2 * density).toInt(), padH, (2 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnTouchListener(DragTapTouchListener(onDrag = onDragBy, onTap = {}))
        }
        val collapseBtn = makeIconButton("﹀") { setCollapsed(true) }
        val handleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
            addView(handle)
            addView(collapseBtn)
        }

        pencilBtn = makeIconButton("✎") { onPencilTap() }
        blurBtn = makeIconButton("▦") { onBlurTap() }
        val undoBtn = makeIconButton("⟲") { onUndoTap() }
        val redoBtn = makeIconButton("⟳") { onRedoTap() }
        val clearBtn = makeIconButton("✕") { onClearTap() }
        val toolsRow = LinearLayout(context).apply { orientation = HORIZONTAL; layoutParams = rowParams() }
        listOf(pencilBtn, undoBtn, redoBtn, blurBtn, clearBtn).forEach { toolsRow.addView(it) }

        pauseBtn = makeIconButton("⏸") { onPauseResumeTap() }
        val stopBtn = makeIconButton("⏹") { onStopTap() }.apply { setTextColor(Color.rgb(255, 90, 90)) }
        val controlRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = rowParams().apply { topMargin = (4 * density).toInt() }
            addView(pauseBtn)
            addView(stopBtn)
        }

        expandedContent = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = rowParams()
            addView(handleRow)
            addView(toolsRow)
            addView(controlRow)
        }
        collapsedChip = TextView(context).apply {
            text = "⏺"
            textSize = 16f
            setTextColor(Color.rgb(255, 90, 90))
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            visibility = GONE
            // Same VERTICAL-parent-defaults-to-MATCH_PARENT trap as before - without this
            // explicit WRAP_CONTENT, collapsing would shrink the whole window to zero width
            // the moment expandedContent (the only other, non-MATCH_PARENT child) goes GONE.
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnTouchListener(DragTapTouchListener(onDrag = onDragBy, onTap = { setCollapsed(false) }))
        }
        addView(expandedContent)
        addView(collapsedChip)
    }

    private fun makeIconButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            val padH = (10 * density).toInt()
            val padV = (6 * density).toInt()
            setPadding(padH, padV, padH, padV)
            setOnClickListener { onClick() }
        }
    }

    fun setCollapsed(c: Boolean) {
        collapsed = c
        expandedContent.visibility = if (c) GONE else VISIBLE
        collapsedChip.visibility = if (c) VISIBLE else GONE
    }

    fun setPaused(paused: Boolean) {
        pauseBtn.text = if (paused) "▶" else "⏸"
    }

    fun setActive(tool: AnnotationTool) {
        pencilBtn.setBackgroundColor(if (tool == AnnotationTool.PENCIL) Color.rgb(255, 205, 0) else Color.TRANSPARENT)
        pencilBtn.setTextColor(if (tool == AnnotationTool.PENCIL) Color.BLACK else Color.WHITE)
        blurBtn.setBackgroundColor(if (tool == AnnotationTool.BLUR) Color.rgb(255, 205, 0) else Color.TRANSPARENT)
        blurBtn.setTextColor(if (tool == AnnotationTool.BLUR) Color.BLACK else Color.WHITE)
    }
}

/** Drag-to-move + tap-to-activate, the same interaction model used by the Elene bubble
 * (ScifiAccessibilityService.kt) - duplicated here in miniature rather than shared since that
 * one is a private class in a different file. */
private class DragTapTouchListener(
    private val onDrag: (dx: Int, dy: Int) -> Unit,
    private val onTap: () -> Unit
) : View.OnTouchListener {
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var moved = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = downRawX
                lastRawY = downRawY
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastRawX).toInt()
                val dy = (event.rawY - lastRawY).toInt()
                if (kotlin.math.abs(event.rawX - downRawX) > 12 || kotlin.math.abs(event.rawY - downRawY) > 12) {
                    moved = true
                }
                lastRawX = event.rawX
                lastRawY = event.rawY
                onDrag(dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) onTap()
            }
        }
        return true
    }
}
