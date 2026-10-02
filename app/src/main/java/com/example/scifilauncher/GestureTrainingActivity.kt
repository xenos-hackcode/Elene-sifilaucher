package com.example.scifilauncher

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors

/**
 * Real gesture recording: live front-camera preview + on-device hand tracking (same MediaPipe
 * Hand Landmarker HandGestureService uses for live recognition), capturing actual repetitions of
 * whichever GestureAction was picked in GestureListActivity, checking each new recording against
 * every other already-trained gesture so two gestures that are too easy to confuse for each
 * other get flagged rather than silently accepted.
 */
class GestureTrainingActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ACTION_ID = "com.example.scifilauncher.extra.GESTURE_ACTION_ID"
        private const val TAG = "GestureTraining"
        private const val TARGET_REPS = 5
        private const val TRAJECTORY_CAPTURE_MS = 1200L
        private const val HOLD_CAPTURE_MS = 700L
        // Heuristic thresholds, not measured against real usage data yet - if two genuinely
        // different gestures keep getting flagged as "too similar" (or vice versa, two similar
        // ones sail through unflagged), these are the first things to retune.
        private const val TRAJECTORY_WARN_DISTANCE = 0.35f
        private const val POSE_WARN_DISTANCE = 0.4f
        private const val TRAJECTORY_REPEAT_MAX_DISTANCE = 0.48f
        private const val POSE_REPEAT_MAX_DISTANCE = 0.52f
    }

    private lateinit var action: GestureAction
    private var handLandmarker: HandLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var repCountLabel: TextView? = null
    private var statusLabel: TextView? = null
    private var recordButton: TextView? = null
    private var doneButton: TextView? = null

    private var capturing = false
    private var captureStartMs = 0L
    private val trajectoryBuffer = mutableListOf<Pair<Float, Float>>()
    private val holdPoseBuffer = mutableListOf<FloatArray>()
    private var repCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
        val resolved = actionId?.let { GestureAction.fromId(it) }
        if (resolved == null) {
            finish()
            return
        }
        action = resolved

        if (action == GestureAction.OPEN_APP && GestureTemplateStore.load(this, action)?.openAppPackage == null) {
            showAppPicker()
            return
        }
        showRecordingUi()
    }

    // ---- Step 1 (OPEN_APP only): pick exactly one app before recording the gesture itself ----

    private fun showAppPicker() {
        val pm = packageManager
        val launchable = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val listView = ListView(this).apply {
            adapter = ArrayAdapter(
                this@GestureTrainingActivity,
                android.R.layout.simple_list_item_1,
                launchable.map { it.loadLabel(pm).toString() }
            )
            setOnItemClickListener { _, _, position, _ ->
                GestureTemplateStore.setOpenAppPackage(this@GestureTrainingActivity, launchable[position].packageName)
                showRecordingUi()
            }
        }
        val title = TextView(this).apply {
            text = "Choose the app this gesture opens"
            setTextColor(currentThemeColorArgb())
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(title)
            addView(listView)
        }
        setContentView(container)
    }

    // ---- Step 2: the actual camera-based recording flow ----

    private fun showRecordingUi() {
        val themeColor = currentThemeColorArgb()
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(previewView)

        container.addView(buildBackButton(themeColor))

        val titleLabel = TextView(this).apply {
            text = action.label.uppercase()
            setTextColor(themeColor)
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            background = pillBackground()
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(16) + statusBarInsetPx()
            }
        }
        container.addView(titleLabel)

        val instructions = TextView(this).apply {
            text = when {
                action.kind == GestureKind.HOLD -> "Tap RECORD, then hold the hand shape/symbol in front of the camera."
                action == GestureAction.ZOOM_IN -> "Tap RECORD, then slowly pinch your thumb and index finger together."
                action == GestureAction.ZOOM_OUT -> "Tap RECORD, then slowly spread your thumb and index finger apart."
                else -> "Tap RECORD, then perform the ${action.label.lowercase()} motion with your hand."
            }
            setTextColor(Color.LTGRAY)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(8), dp(24), dp(8))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(64) + statusBarInsetPx()
            }
        }
        container.addView(instructions)

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = dp(24) + navigationBarInsetPx()
            }
        }

        repCountLabel = TextView(this).apply {
            text = "0 / $TARGET_REPS reps"
            setTextColor(themeColor)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        statusLabel = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(6), dp(20), dp(6))
            visibility = View.GONE
        }
        recordButton = TextView(this).apply {
            text = "● RECORD"
            setTextColor(Color.BLACK)
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(themeColor)
                cornerRadius = dp(28).toFloat()
            }
            setPadding(dp(32), dp(14), dp(32), dp(14))
            setOnClickListener { startCapture() }
        }
        doneButton = TextView(this).apply {
            text = "DONE"
            setTextColor(themeColor)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
            setOnClickListener { finish() }
        }

        val recordRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(recordButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(doneButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
        }

        bottomBar.addView(repCountLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        bottomBar.addView(statusLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        bottomBar.addView(recordRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(bottomBar)

        setContentView(container)
        repCount = GestureTemplateStore.load(this, action)?.samples?.size ?: 0
        updateRepLabel()

        releaseGestureControlCameraIfActive()
        runCatching { setUpHandLandmarker() }.onFailure { Log.e(TAG, "HandLandmarker init failed", it) }
        startCamera(previewView)
    }

    /** HandGestureService and this screen can't both hold the front camera at once - binding
     * here would silently evict the service's own binding while leaving its "enabled" state
     * (and the Security toggle backed by the same pref) stuck showing on. Stop it and clear the
     * pref up front instead, so the toggle honestly reflects that it's off and the user has to
     * turn it back on themselves once they're done training. */
    private fun releaseGestureControlCameraIfActive() {
        val prefs = getSharedPreferences("lock_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("gesture_control_enabled", false)) {
            stopService(Intent(this, HandGestureService::class.java))
            prefs.edit().putBoolean("gesture_control_enabled", false).apply()
        }
    }

    private fun updateRepLabel() {
        repCountLabel?.text = "$repCount / $TARGET_REPS reps"
        doneButton?.visibility = if (repCount >= MIN_SAMPLES_TO_BE_TRAINED) View.VISIBLE else View.GONE
    }

    private fun startCapture() {
        if (capturing) return
        capturing = true
        captureStartMs = System.currentTimeMillis()
        trajectoryBuffer.clear()
        holdPoseBuffer.clear()
        recordButton?.text = "● REC..."
        recordButton?.isEnabled = false

        val durationMs = if (action.kind == GestureKind.HOLD) HOLD_CAPTURE_MS else TRAJECTORY_CAPTURE_MS
        mainHandler.postDelayed({ finishCapture() }, durationMs)
    }

    private fun finishCapture() {
        capturing = false
        recordButton?.text = "● RECORD"
        recordButton?.isEnabled = true

        val sample: FloatArray? = when (action.kind) {
            GestureKind.TRAJECTORY -> {
                if (trajectoryBuffer.size < 3) null
                else GestureMatcher.normalizeTrajectory(GestureMatcher.resampleTrajectory(trajectoryBuffer))
            }
            GestureKind.HOLD -> {
                // The frame closest to the middle of the hold window - avoids the hand still
                // settling into position right at the very start of the capture.
                holdPoseBuffer.getOrNull(holdPoseBuffer.size / 2)?.let { GestureMatcher.normalizePose(it) }
            }
        }

        if (sample == null) {
            statusLabel?.text = "No hand detected - try again, closer to the camera."
            statusLabel?.setTextColor(Color.parseColor("#FFB300"))
            statusLabel?.visibility = View.VISIBLE
            return
        }

        val repeatError = checkRepeatConsistency(sample)
        if (repeatError != null) {
            statusLabel?.text = repeatError
            statusLabel?.setTextColor(Color.parseColor("#FFB300"))
            statusLabel?.visibility = View.VISIBLE
            return
        }

        val warning = checkDistinctiveness(sample)
        GestureTemplateStore.addSample(this, action, GestureSample(sample))
        repCount++
        updateRepLabel()

        if (warning != null) {
            statusLabel?.text = "⚠ Similar to \"$warning\" - try to make this more distinct."
            statusLabel?.setTextColor(Color.parseColor("#FFB300"))
        } else {
            statusLabel?.text = "✓ Recorded rep $repCount"
            statusLabel?.setTextColor(Color.parseColor("#4CAF50"))
        }
        statusLabel?.visibility = View.VISIBLE
    }

    /** After the first saved rep for this action, later reps must match that same motion. This
     * prevents one control from quietly collecting multiple different hand effects and becoming
     * impossible to recognize cleanly later. */
    private fun checkRepeatConsistency(newSample: FloatArray): String? {
        val existing = GestureTemplateStore.load(this, action)?.samples.orEmpty()
        if (existing.isEmpty()) return null

        val distance = when (action.kind) {
            GestureKind.TRAJECTORY -> GestureMatcher.bestTrajectoryDistance(newSample, existing.map { it.points })
            GestureKind.HOLD -> GestureMatcher.bestPoseDistance(newSample, existing.map { it.points })
        }
        val maxDistance = if (action.kind == GestureKind.HOLD) POSE_REPEAT_MAX_DISTANCE else TRAJECTORY_REPEAT_MAX_DISTANCE
        return if (distance <= maxDistance) {
            null
        } else {
            "Doesn't match your first ${action.label.lowercase()} motion - not saved. Repeat the same motion."
        }
    }

    /** Compares the new sample against compatible already-trained gestures. Zoom gestures are
     * thumb-index distance signals, while other trajectory gestures are palm XY paths, so treating
     * both as one bucket produced false "similar to everything" warnings during training. */
    private fun checkDistinctiveness(newSample: FloatArray): String? {
        var closestLabel: String? = null
        var closestDistance = Float.POSITIVE_INFINITY
        for (other in GestureAction.entries) {
            if (!shouldCompareDistinctiveness(action, other)) continue
            val data = GestureTemplateStore.load(this, other) ?: continue
            if (data.samples.isEmpty()) continue
            val distance = when (action.kind) {
                GestureKind.TRAJECTORY -> GestureMatcher.bestTrajectoryDistance(newSample, data.samples.map { it.points })
                GestureKind.HOLD -> GestureMatcher.bestPoseDistance(newSample, data.samples.map { it.points })
            }
            if (distance < closestDistance) {
                closestDistance = distance
                closestLabel = other.label
            }
        }
        val threshold = if (action.kind == GestureKind.HOLD) POSE_WARN_DISTANCE else TRAJECTORY_WARN_DISTANCE
        return if (closestDistance < threshold) closestLabel else null
    }

    // Shared with HandGestureService's live recognizer via GestureAction.family (GestureAction.kt)
    // - previously each file re-derived "which gestures are comparable" independently, and those
    // two implementations had already drifted out of sync once this session.
    private fun shouldCompareDistinctiveness(current: GestureAction, other: GestureAction): Boolean {
        if (other == current || other.kind != current.kind) return false
        return current.family == other.family
    }

    // ---- Camera + hand tracking (same MediaPipe setup pattern as HandGestureService, but with
    // a real visible PreviewView here since this screen is meant to be watched while recording) ----

    private fun setUpHandLandmarker() {
        val baseOptions = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setResultListener(::onHandResult)
            .setErrorListener { e -> Log.e(TAG, "HandLandmarker error", e) }
            .build()
        handLandmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun startCamera(previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                // Must match HandGestureService's pinned rotation exactly - training and live
                // recognition need the identical coordinate convention, or a recorded template's
                // shape stops matching live data after either pipeline's rotation handling
                // changes (this Activity has its own window so CameraX could derive a rotation
                // automatically here, unlike the headless service, but "automatic" isn't
                // guaranteed to agree with what's pinned there - explicit beats implicit).
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()
            analysis.setAnalyzer(analysisExecutor) { imageProxy -> analyzeFrame(imageProxy) }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            }.onFailure { Log.e(TAG, "Camera bind failed", it) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val landmarker = handLandmarker
        val mediaImage = imageProxy.image
        if (landmarker == null || mediaImage == null) {
            imageProxy.close()
            return
        }
        runCatching {
            val mpImage = MediaImageBuilder(mediaImage).build()
            val processingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
                .build()
            landmarker.detectAsync(mpImage, processingOptions, System.currentTimeMillis())
        }.onFailure { Log.e(TAG, "detectAsync failed", it) }
        imageProxy.close()
    }

    private fun onHandResult(result: HandLandmarkerResult, @Suppress("UNUSED_PARAMETER") image: com.google.mediapipe.framework.image.MPImage) {
        if (!capturing) return
        val landmarks = result.landmarks().firstOrNull() ?: return

        when (action.kind) {
            GestureKind.TRAJECTORY -> {
                if (action == GestureAction.ZOOM_IN || action == GestureAction.ZOOM_OUT) {
                    // A pinch's signature is thumb-index distance changing over time, not palm
                    // XY position - captured as (distance, 0f) so it reuses the same DTW path
                    // resampling/matching as every other trajectory gesture.
                    val thumb = landmarks[4]
                    val index = landmarks[8]
                    val dx = thumb.x() - index.x()
                    val dy = thumb.y() - index.y()
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                    mainHandler.post { trajectoryBuffer.add(distance to 0f) }
                } else {
                    val refX = (landmarks[0].x() + landmarks[5].x() + landmarks[9].x() + landmarks[13].x() + landmarks[17].x()) / 5f
                    val refY = (landmarks[0].y() + landmarks[5].y() + landmarks[9].y() + landmarks[13].y() + landmarks[17].y()) / 5f
                    mainHandler.post { trajectoryBuffer.add(refX to refY) }
                }
            }
            GestureKind.HOLD -> {
                val pose = FloatArray(GestureMatcher.POSE_LANDMARK_COUNT * 2)
                for (i in 0 until GestureMatcher.POSE_LANDMARK_COUNT) {
                    pose[i * 2] = landmarks[i].x()
                    pose[i * 2 + 1] = landmarks[i].y()
                }
                mainHandler.post { holdPoseBuffer.add(pose) }
            }
        }
    }

    // ---- Small shared UI helpers (mirrors GlobeActivity's native-view styling) ----

    private fun currentThemeColorArgb(): Int {
        val idx = getSharedPreferences("theme_prefs", MODE_PRIVATE).getInt("theme_index", 0)
        return CedalThemes[idx % CedalThemes.size].primary.let {
            Color.argb((it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt())
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun pillBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(140, 0, 0, 0))
        cornerRadius = dp(8).toFloat()
    }

    private fun buildBackButton(themeColor: Int): TextView = TextView(this).apply {
        text = "◂ BACK"
        setTextColor(themeColor)
        textSize = 14f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        background = pillBackground()
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { finish() }
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(dp(16), dp(16) + statusBarInsetPx(), dp(16), dp(16))
        }
    }

    private fun statusBarInsetPx(): Int {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return 0
        return runCatching {
            window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: 0
        }.getOrDefault(0)
    }

    private fun navigationBarInsetPx(): Int {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return 0
        return runCatching {
            window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
        }.getOrDefault(0)
    }

    override fun onDestroy() {
        runCatching { cameraProvider?.unbindAll() }
        runCatching { handLandmarker?.close() }
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
