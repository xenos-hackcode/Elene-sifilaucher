package com.example.scifilauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/** Pinch dispatches through GestureAction (ZOOM_IN/ZOOM_OUT) like everything else once trained -
 * this fixed thumb/index-distance heuristic is only the untrained fallback, same role as
 * fixedDirectionFallback plays for the four cardinal swipes. */
private enum class PinchGesture { PINCH_IN, PINCH_OUT }

/**
 * Touchless gesture control: real-time on-device hand tracking over the front camera (MediaPipe
 * Hand Landmarker, fully local - no cloud, no frames ever leave the device). Two kinds of
 * recognition run here:
 * - TRAJECTORY (one-shot swipes + open globe/reactor/chat/app/sleep): the live palm-center path
 *   is compared via DTW against every trained GestureAction template; the four cardinal swipes
 *   additionally have a fixed-geometry fallback so they work immediately, untrained.
 * - HOLD: the live per-frame hand shape would be compared against trained pose templates, firing
 *   repeatedly while a match keeps holding - infrastructure kept for a future gesture, but no
 *   action currently uses it (continuous scroll via a held pose was removed in favor of repeated
 *   swipes with return-motion suppression, see OPPOSITE_SWIPE_COOLDOWN_MS below).
 *
 * Real hardware limit, disclosed rather than hidden: this phone has no depth/ToF/radar sensor,
 * so there's no true millimeter-precision 3D tracking - hand position comes from a single RGB
 * camera plus MediaPipe's own monocular depth estimate. That's solid for discrete gesture
 * classification (this file's entire job), not for continuous cursor-precision control.
 *
 * Only ever runs while explicitly armed (Settings > Gesture Control) - continuous camera + ML
 * inference is a real, meaningful battery cost, never something to run silently by default.
 */
class HandGestureService : LifecycleService() {

    companion object {
        private const val TAG = "HandGestureService"
        private const val CHANNEL_ID = "gesture_control"
        private const val NOTIFICATION_ID = 7301
        const val ACTION_STOP = "com.example.scifilauncher.action.GESTURE_STOP"

        @Volatile
        var isRunning: Boolean = false
            private set

        // How far (normalized 0..1 screen-fraction) the tracked hand must travel within
        // SWIPE_WINDOW_MS to count as a deliberate swipe, not just idle repositioning in frame.
        private const val SWIPE_DISTANCE_THRESHOLD = 0.18f
        private const val SWIPE_WINDOW_MS = 500L
        // Thumb-tip to index-tip distance (normalized): below this = "closed" (pinched),
        // above this = "open". The gap between the two avoids flickering back and forth right
        // at one single threshold value.
        private const val PINCH_CLOSED_THRESHOLD = 0.08f
        private const val PINCH_OPEN_THRESHOLD = 0.12f
        // A real in-air pinch is performed with the palm roughly still - a fast swipe
        // naturally makes the thumb and index tip pass close together for a frame or two too,
        // which used to get misread as a pinch mid-swipe (confirmed via logcat: dispatched a
        // real two-pointer pinch gesture instead of the trained swipe). Skip pinch evaluation
        // entirely while the palm has moved more than this over the recent window below.
        // Raised from 0.08 after live logcat evidence showed genuine pinch attempts landing at
        // 0.08-0.16 palm motion - every real pinch was being rejected as "moved too much", never
        // completing at all. 0.18 covers the observed real-pinch range with a little margin while
        // staying below swipe-scale displacement (observed ~0.19-0.26 during active-pinch swipe
        // suppression, a different check, but a useful reference for "how far a real swipe moves").
        private const val PINCH_MAX_PALM_MOTION = 0.18f
        private const val PINCH_MOTION_WINDOW_MS = 150L
        // Minimum thumb-index distance swing (normalized) within SWIPE_WINDOW_MS before a
        // trained zoom match is even attempted - filters out hand tremor/noise from being
        // read as a deliberate pinch-in/pinch-out motion.
        private const val ZOOM_DISTANCE_RANGE_THRESHOLD = 0.03f
        private const val ZOOM_DIRECTION_EPSILON = 0.008f
        // Deliberately HIGHER than ZOOM_DISTANCE_RANGE_THRESHOLD - that one only gates whether a
        // zoom match is worth attempting at all (kept loose so real-but-subtle pinches aren't
        // missed), but reusing that same loose bar to also mark "a pinch is in progress" (which
        // blocks swipe recognition, see isPinchActive) meant ordinary finger jitter during an
        // actual swipe was constantly satisfying it and silently swallowing every swipe attempt
        // (confirmed via logcat: every swipe logged "ignored during active pinch" with pinch
        // never actually firing either). This higher bar requires real, deliberate thumb-index
        // movement before a swipe gets blocked.
        private const val PINCH_BLOCK_SWIPE_RANGE_THRESHOLD = 0.07f
        // After firing any one-shot gesture, ignore new ones for this long - one continuous
        // hand motion (e.g. a pinch naturally relaxing back open right after) must not fire
        // twice. Same heuristic distance thresholds GestureTrainingActivity warns against at
        // recording time - if a trained gesture fires on the wrong motion or doesn't fire on
        // the right one, these are the first things to retune, not the DTW/pose math itself.
        private const val GESTURE_COOLDOWN_MS = 700L
        // A real touchscreen swipe has a natural "release" (lift the finger) so bringing your
        // hand/finger back for the next repeat doesn't itself count as a swipe. In-air gestures
        // have no equivalent release, so the return motion after e.g. a swipe-up (bringing the
        // hand back down to repeat it) would otherwise fire a swipe-down right after - annoying
        // for anyone repeating the same swipe to scroll further, same direction each time. This
        // window suppresses only the OPPOSITE direction right after a swipe fires (longer than
        // GESTURE_COOLDOWN_MS, which still allows a fast same-direction repeat) - the return
        // swing typically needs a bit more time than a deliberate repeat.
        private const val OPPOSITE_SWIPE_COOLDOWN_MS = 1100L
        private const val TRAJECTORY_MATCH_THRESHOLD = 0.35f
        // A trained gesture must be clearly better than the runner-up. Without this, similar
        // recordings can randomly trade actions depending on one jittery frame.
        private const val TRAJECTORY_MATCH_MARGIN = 0.08f
        private const val POSE_MATCH_THRESHOLD = 0.4f
        private const val POSE_MATCH_MARGIN = 0.08f
        private const val SWIPE_AXIS_DOMINANCE_RATIO = 1.25f
        private const val HOLD_MAX_PALM_MOTION = 0.035f
        // A HOLD pose needs to match for this many consecutive frames before it's treated as
        // "really being held" (not one stray matching frame), then fires a scroll on this
        // interval for as long as it keeps matching.
        private const val HOLD_CONFIRM_FRAMES = 4
        private const val HOLD_REPEAT_INTERVAL_MS = 500L

        // ---- Face gestures: fixed, non-trainable heuristics only (agreed with Codex in
        // combination.md - narrow first pass, no training UI for these). Shares the camera
        // pipeline and gesture cooldown with hand recognition above, never a second camera
        // binding. Nod/shake use the same "track a reference point's position over a rolling
        // window, threshold the dominant axis" pattern as hand swipes (see checkTrajectoryGesture)
        // rather than decomposing the facial transformation matrix into Euler angles - this
        // session had enough rotation/axis bugs from matrix math already; reusing the
        // already-proven simpler approach is a deliberate risk reduction, not an oversight. ----

        // Face inference is real extra CPU/battery on top of hand tracking every frame - only
        // run it on every Nth analyzed frame. Hand recognition still runs on every frame.
        private const val FACE_ANALYSIS_FRAME_SKIP = 2
        private const val NOSE_TIP_LANDMARK_INDEX = 1
        private const val FACE_MOTION_WINDOW_MS = 700L
        // Smaller than SWIPE_DISTANCE_THRESHOLD - head movement within frame for a nod/shake is
        // naturally a smaller fraction of the frame than a hand swipe. Unmeasured heuristic like
        // every other threshold in this file, first thing to retune against real use.
        private const val FACE_MOTION_DISTANCE_THRESHOLD = 0.05f
        private const val SMILE_BLENDSHAPE_THRESHOLD = 0.5f
        // Consecutive frames a smile must hold before firing, so one noisy frame doesn't trigger
        // it - same idea as HOLD_CONFIRM_FRAMES above.
        private const val SMILE_CONFIRM_FRAMES = 4
        // Smile and mouth-open used to share one toggleHidePage() gesture; user asked for two
        // distinct one-way gestures instead (smile = hide, mouth-open = un-hide) so each direction
        // is deliberate rather than a second smile accidentally un-hiding. Same threshold/confirm-
        // frames shape as smile, just a different blendshape ("jawOpen" - MediaPipe's own name).
        private const val MOUTH_OPEN_BLENDSHAPE_THRESHOLD = 0.5f
        private const val MOUTH_OPEN_CONFIRM_FRAMES = 4
        // Lower guard used only to suppress nod/shake's trajectory check while the mouth is
        // visibly moving (see checkNodShake) - not a gesture-confirm threshold itself.
        private const val MOUTH_ACTIVITY_GUARD_THRESHOLD = 0.3f
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var handLandmarker: HandLandmarker? = null
    private var faceLandmarker: FaceLandmarker? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Face gestures - fixed, non-trainable, off by default (see faceGesturesEnabled). Real toggle
    // wiring is a separate step; onCreate reads it the same way pinchEnabled is read.
    private var faceGesturesEnabled = false
    private var faceFrameCounter = 0
    // Rolling nose-tip position, same shape as the hand palm-center trajectory above.
    private val faceTrajectory = ArrayDeque<Triple<Long, Float, Float>>()
    private var smileConsecutiveFrames = 0
    private var mouthOpenConsecutiveFrames = 0

    // Rolling trajectory of the tracked hand's reference point (palm center), each entry
    // (timestampMs, x, y) in normalized [0,1] frame coordinates.
    private val trajectory = ArrayDeque<Triple<Long, Float, Float>>()

    // Last cardinal swipe that actually fired, for the opposite-direction return-motion
    // suppression (see OPPOSITE_SWIPE_COOLDOWN_MS).
    private var lastSwipeAction: GestureAction? = null
    private var lastSwipeAtMs = 0L

    // Rolling thumb-index distance signal for the trainable zoom gestures (a pinch's shape is
    // this distance changing over time, not palm XY position - reuses the same DTW machinery as
    // swipes by treating each sample as a (distance, 0f) point).
    private val pinchTrajectory = ArrayDeque<Pair<Long, Float>>()
    private var lastPinchMotionAtMs = 0L
    private var lastGestureAtMs = 0L

    // Loaded once per arm (see onCreate) - re-arming (turning gesture control off/on) picks up
    // anything newly trained in the meantime, without needing to poll a live file during use.
    private var trainedTrajectory: Map<GestureAction, List<FloatArray>> = emptyMap()
    private var trainedHold: Map<GestureAction, List<FloatArray>> = emptyMap()

    private var activeHoldAction: GestureAction? = null
    private var holdConsecutiveFrames = 0
    private var lastHoldFireAtMs = 0L

    // Pinch-to-zoom is a fixed, non-trainable heuristic (see checkPinch below) - not everyone
    // wants it competing with their trained gestures for the shared cooldown, so it's a plain
    // on/off toggle in Security rather than something the service assumes everyone uses.
    private var pinchEnabled = true

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        pinchEnabled = getSharedPreferences("lock_prefs", MODE_PRIVATE).getBoolean("pinch_gesture_enabled", true)
        faceGesturesEnabled = getSharedPreferences("lock_prefs", MODE_PRIVATE).getBoolean("face_gestures_enabled", false)
        loadTrainedGestures()
        runCatching { setUpHandLandmarker() }
            .onFailure { Log.e(TAG, "Failed to init HandLandmarker", it) }
        if (faceGesturesEnabled) {
            runCatching { setUpFaceLandmarker() }
                .onFailure { Log.e(TAG, "Failed to init FaceLandmarker", it) }
        }
        startCamera()
        isRunning = true
        mainHandler.post { ScifiAccessibilityService.instance?.showSkeletonOverlay() }
    }

    private fun loadTrainedGestures() {
        val all = GestureTemplateStore.loadAll(this)
        trainedTrajectory = all.filterKeys { it.kind == GestureKind.TRAJECTORY }
            .filterValues { it.samples.size >= MIN_SAMPLES_TO_BE_TRAINED }
            .mapValues { (_, data) -> data.samples.map { it.points } }
        trainedHold = all.filterKeys { it.kind == GestureKind.HOLD }
            .filterValues { it.samples.size >= MIN_SAMPLES_TO_BE_TRAINED }
            .mapValues { (_, data) -> data.samples.map { it.points } }
        Log.d(TAG, "Loaded trained gestures: trajectory=${trainedTrajectory.keys} hold=${trainedHold.keys}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { cameraProvider?.unbindAll() }
        runCatching { handLandmarker?.close() }
        runCatching { faceLandmarker?.close() }
        analysisExecutor.shutdown()
        mainHandler.post { ScifiAccessibilityService.instance?.hideSkeletonOverlay() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun setUpHandLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()
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

    private fun setUpFaceLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setOutputFaceBlendshapes(true)
            .setResultListener(::onFaceResult)
            .setErrorListener { e -> Log.e(TAG, "FaceLandmarker error", e) }
            .build()
        faceLandmarker = FaceLandmarker.createFromOptions(this, options)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                // This is a headless LifecycleService with no window of its own, unlike
                // GestureTrainingActivity (which gets a stable rotation from its real Activity
                // window automatically) - without an explicit target, CameraX falls back to
                // guessing the ambient display rotation, which can depend on whatever app
                // happens to be in the foreground when this service starts rather than how the
                // phone is actually being held. Confirmed via live testing: a consistent-but-
                // session-varying 90° rotation in imageInfo.rotationDegrees. This whole feature
                // assumes the phone held in standard portrait, so pin that explicitly.
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()
            analysis.setAnalyzer(analysisExecutor) { imageProxy -> analyzeFrame(imageProxy) }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            }.onFailure { Log.e(TAG, "Camera bind failed", it) }
        }, ContextCompat.getMainExecutor(this))
    }

    private var lastLoggedRotation = -1

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val landmarker = handLandmarker
        val mediaImage = imageProxy.image
        if (landmarker == null || mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        if (rotationDegrees != lastLoggedRotation) {
            lastLoggedRotation = rotationDegrees
            Log.d(TAG, "analyzeFrame: rotationDegrees=$rotationDegrees imageSize=${imageProxy.width}x${imageProxy.height}")
        }
        val timestampMs = System.currentTimeMillis()
        runCatching {
            val mpImage = MediaImageBuilder(mediaImage).build()
            val processingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(rotationDegrees)
                .build()
            landmarker.detectAsync(mpImage, processingOptions, timestampMs)
        }.onFailure { Log.e(TAG, "detectAsync failed", it) }

        // Throttled - real extra CPU/battery cost on top of hand tracking every frame, and face
        // gestures (nod/shake/smile) don't need every-frame precision the way a fast swipe does.
        val face = faceLandmarker
        if (face != null) {
            faceFrameCounter++
            if (faceFrameCounter > FACE_ANALYSIS_FRAME_SKIP) {
                faceFrameCounter = 0
                runCatching {
                    val faceMpImage = MediaImageBuilder(mediaImage).build()
                    val faceProcessingOptions = ImageProcessingOptions.builder()
                        .setRotationDegrees(rotationDegrees)
                        .build()
                    face.detectAsync(faceMpImage, faceProcessingOptions, timestampMs)
                }.onFailure { Log.e(TAG, "face detectAsync failed", it) }
            }
        }
        imageProxy.close()
    }

    private fun onHandResult(result: HandLandmarkerResult, image: MPImage) {
        val landmarks = result.landmarks().firstOrNull()
        if (landmarks == null) {
            // No hand in frame right now - don't let a stale trajectory bridge across the gap
            // and get misread as one continuous swipe once a hand reappears later, and stop any
            // in-progress hold (a lost hand is not "still holding the pose").
            trajectory.clear()
            pinchTrajectory.clear()
            lastPinchMotionAtMs = 0L
            endHold()
            ScifiAccessibilityService.instance?.updateHandSkeleton(null)
            return
        }
        val now = System.currentTimeMillis()

        // 3 floats/landmark (x,y,z) for the skeleton preview's 3D rendering (see combination.md) -
        // z is MediaPipe's own relative-depth estimate (same "no ToF sensor" caveat as everywhere
        // else in this feature). Display-only: recognition below still reads raw x()/y() directly,
        // never this array.
        val skeletonPoints = FloatArray(landmarks.size * 3)
        for (i in landmarks.indices) {
            skeletonPoints[i * 3] = landmarks[i].x()
            skeletonPoints[i * 3 + 1] = landmarks[i].y()
            skeletonPoints[i * 3 + 2] = landmarks[i].z()
        }
        ScifiAccessibilityService.instance?.updateHandSkeleton(skeletonPoints)

        // Palm-center reference point: average of the wrist (0) and the four knuckles
        // (5,9,13,17) - steadier than tracking a single fingertip, which jitters more and is
        // more likely to leave frame during a fast swipe.
        val refX = (landmarks[0].x() + landmarks[5].x() + landmarks[9].x() + landmarks[13].x() + landmarks[17].x()) / 5f
        val refY = (landmarks[0].y() + landmarks[5].y() + landmarks[9].y() + landmarks[13].y() + landmarks[17].y()) / 5f
        trajectory.addLast(Triple(now, refX, refY))
        while (trajectory.isNotEmpty() && now - trajectory.first().first > SWIPE_WINDOW_MS) {
            trajectory.removeFirst()
        }

        val swipeCandidate = currentSwipeCandidate()
        if (swipeCandidate != null) {
            checkTrajectoryGesture(now)
        } else {
            if (pinchEnabled) checkZoomGesture(landmarks, now)
            checkTrajectoryGesture(now)
        }
        checkHoldGesture(landmarks, now)
    }

    // ---- Face gestures: fixed heuristics only, shares lastGestureAtMs cooldown with hand
    // gestures above so the two can never double-fire off the same moment. See combination.md
    // for the narrow-first-pass scope agreed with Codex. ----

    private fun onFaceResult(result: FaceLandmarkerResult, image: MPImage) {
        val landmarks = result.faceLandmarks().firstOrNull()
        if (landmarks == null || landmarks.size <= NOSE_TIP_LANDMARK_INDEX) {
            faceTrajectory.clear()
            smileConsecutiveFrames = 0
            mouthOpenConsecutiveFrames = 0
            ScifiAccessibilityService.instance?.updateFaceSkeleton(null)
            return
        }
        val now = System.currentTimeMillis()

        // Display-only, same as the hand skeleton preview - recognition below reads landmarks
        // directly, never this array. ~478 points (face mesh incl. iris) as flat (x,y) pairs,
        // matching what HandSkeletonOverlayView's face-dot drawing loop already expects.
        val skeletonPoints = FloatArray(landmarks.size * 2)
        for (i in landmarks.indices) {
            skeletonPoints[i * 2] = landmarks[i].x()
            skeletonPoints[i * 2 + 1] = landmarks[i].y()
        }
        ScifiAccessibilityService.instance?.updateFaceSkeleton(skeletonPoints)

        val noseX = landmarks[NOSE_TIP_LANDMARK_INDEX].x()
        val noseY = landmarks[NOSE_TIP_LANDMARK_INDEX].y()
        faceTrajectory.addLast(Triple(now, noseX, noseY))
        while (faceTrajectory.isNotEmpty() && now - faceTrajectory.first().first > FACE_MOTION_WINDOW_MS) {
            faceTrajectory.removeFirst()
        }

        // Computed once and shared - checkNodShake needs it too now (see mouthActive guard
        // below), not just checkSmile/checkMouthOpen.
        val blendshapes = result.faceBlendshapes().orElse(null)?.firstOrNull()
        checkSmile(blendshapes, now)
        checkMouthOpen(blendshapes, now)
        checkNodShake(now, blendshapes)
    }

    private fun smileScoreOf(blendshapes: List<Category>?): Float =
        blendshapes
            ?.filter { it.categoryName() == "mouthSmileLeft" || it.categoryName() == "mouthSmileRight" }
            ?.maxOfOrNull { it.score() } ?: 0f

    private fun jawOpenScoreOf(blendshapes: List<Category>?): Float =
        blendshapes?.firstOrNull { it.categoryName() == "jawOpen" }?.score() ?: 0f

    /** Smile = hide only (one-way, not a toggle - see MOUTH_OPEN_BLENDSHAPE_THRESHOLD above for
     * why). Smiling again while already hidden is a no-op, not an accidental un-hide. */
    private fun checkSmile(blendshapes: List<Category>?, now: Long) {
        val smileScore = smileScoreOf(blendshapes)

        if (smileScore < SMILE_BLENDSHAPE_THRESHOLD) {
            smileConsecutiveFrames = 0
            return
        }
        smileConsecutiveFrames++
        if (smileConsecutiveFrames < SMILE_CONFIRM_FRAMES) return
        if (now - lastGestureAtMs < GESTURE_COOLDOWN_MS) return

        lastGestureAtMs = now
        smileConsecutiveFrames = 0
        Log.d(TAG, "Face gesture recognized: SMILE (score=$smileScore)")
        mainHandler.post { ScifiAccessibilityService.instance?.activateHidePage() }
    }

    /** Mouth-open = un-hide only (one-way). MediaPipe's own blendshape name for this is
     * "jawOpen" - not a custom computation, same as smile reading mouthSmileLeft/Right directly. */
    private fun checkMouthOpen(blendshapes: List<Category>?, now: Long) {
        val openScore = jawOpenScoreOf(blendshapes)

        if (openScore < MOUTH_OPEN_BLENDSHAPE_THRESHOLD) {
            mouthOpenConsecutiveFrames = 0
            return
        }
        mouthOpenConsecutiveFrames++
        if (mouthOpenConsecutiveFrames < MOUTH_OPEN_CONFIRM_FRAMES) return
        if (now - lastGestureAtMs < GESTURE_COOLDOWN_MS) return

        lastGestureAtMs = now
        mouthOpenConsecutiveFrames = 0
        Log.d(TAG, "Face gesture recognized: MOUTH_OPEN (score=$openScore)")
        mainHandler.post { ScifiAccessibilityService.instance?.deactivateHidePage() }
    }

    private fun checkNodShake(now: Long, blendshapes: List<Category>?) {
        // Real user-observed bug: opening the mouth to un-hide kept firing confirm_yes/
        // confirm_no instead - jaw-drop naturally moves the nose-tip position enough to cross
        // FACE_MOTION_DISTANCE_THRESHOLD before MOUTH_OPEN_CONFIRM_FRAMES's 4-consecutive-frame
        // window completes, so nod/shake "won the race" and consumed the shared cooldown first.
        // Guard threshold is deliberately lower than SMILE/MOUTH_OPEN_BLENDSHAPE_THRESHOLD (which
        // gate the actual gesture firing) - this only needs to detect "mouth is doing something,
        // don't trust head-position trajectory this frame," not confirm a full gesture.
        val mouthActive = smileScoreOf(blendshapes) > MOUTH_ACTIVITY_GUARD_THRESHOLD ||
            jawOpenScoreOf(blendshapes) > MOUTH_ACTIVITY_GUARD_THRESHOLD
        if (mouthActive) return

        if (faceTrajectory.size < 3) return
        val (_, x0, y0) = faceTrajectory.first()
        val (_, x1, y1) = faceTrajectory.last()
        // Axis-swapped at the source, same fix and same reason as checkTrajectoryGesture's dx/dy
        // for hand swipes - confirmed via live testing: real nod (vertical) fired "no" (the
        // horizontal branch) and real shake (horizontal) fired "yes" (the vertical branch),
        // exactly the transposed-axis pattern hand tracking had. Same camera/rotation pipeline,
        // so unsurprising in hindsight - should have applied this preemptively.
        val dx = y1 - y0
        val dy = x1 - x0
        val distance = sqrt(dx * dx + dy * dy)
        if (distance < FACE_MOTION_DISTANCE_THRESHOLD) return
        if (now - lastGestureAtMs < GESTURE_COOLDOWN_MS) return

        // Nose moving mostly horizontally = shake (no), mostly vertically = nod (yes) - same
        // axis-dominance idea as hand swipes, no sign-convention guessing needed here since
        // there's no "left means X" mapping, just "which axis moved."
        val command = if (abs(dx) > abs(dy) * SWIPE_AXIS_DOMINANCE_RATIO) {
            "confirm_no" // shake (horizontal)
        } else if (abs(dy) > abs(dx) * SWIPE_AXIS_DOMINANCE_RATIO) {
            "confirm_yes" // nod (vertical)
        } else {
            return // not clearly axis-dominant, not a deliberate nod/shake
        }

        // A pending confirmation is only ever visible while our own app is already the
        // foreground app (it's Compose UI state inside MainActivity, not a system dialog) - if
        // something else is in front, there's nothing to answer, and forcing MainActivity to the
        // front from a stray nod/shake would be a real, unwanted interruption. Fail closed: do
        // nothing rather than guess.
        if (ScifiAccessibilityService.instance?.currentForegroundPackage != packageName) return

        lastGestureAtMs = now
        faceTrajectory.clear()
        Log.d(TAG, "Face gesture recognized: $command (dx=$dx dy=$dy)")
        mainHandler.post {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_ELENE_COMMAND, command)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ---- Pinch/zoom: trained-template match first (thumb-index distance signal over time),
    // fixed distance-threshold fallback so it still works before ZOOM_IN/ZOOM_OUT are trained ----

    /** How far the tracked palm-center point has moved over the last [PINCH_MOTION_WINDOW_MS] -
     * near-zero while holding a pinch shape still, large while mid-swipe. */
    private fun recentPalmMotion(now: Long): Float {
        val windowStart = trajectory.firstOrNull { now - it.first <= PINCH_MOTION_WINDOW_MS } ?: return 0f
        val latest = trajectory.lastOrNull() ?: return 0f
        val dx = latest.second - windowStart.second
        val dy = latest.third - windowStart.third
        return sqrt(dx * dx + dy * dy)
    }

    private fun currentSwipeCandidate(): Pair<Float, Float>? {
        if (trajectory.size < 3) return null
        val (_, x0, y0) = trajectory.first()
        val (_, x1, y1) = trajectory.last()
        // Axis-swapped at the source - see the matching comment in checkTrajectoryGesture below,
        // same fix applied here for consistency (this function's own axisDominant check is
        // symmetric either way, but the returned Pair should still carry correctly-labeled
        // values for whatever reads it).
        val dx = y1 - y0
        val dy = x1 - x0
        val distance = sqrt(dx * dx + dy * dy)
        if (distance < SWIPE_DISTANCE_THRESHOLD) return null
        val axisDominant = abs(dx) > abs(dy) * SWIPE_AXIS_DOMINANCE_RATIO ||
            abs(dy) > abs(dx) * SWIPE_AXIS_DOMINANCE_RATIO
        return if (axisDominant) dx to dy else null
    }

    private fun checkZoomGesture(landmarks: List<NormalizedLandmark>, now: Long) {
        val zoomTrained = trainedTrajectory.filterKeys { it == GestureAction.ZOOM_IN || it == GestureAction.ZOOM_OUT }
        // If zoom isn't trained AND the fixed fallback is disabled (because other gestures are
        // trained - see hasCustomTrajectoryTraining), nothing zoom-related could possibly fire
        // right now. Bail out before touching pinchTrajectory/lastPinchMotionAtMs entirely -
        // otherwise ordinary thumb-index jitter during a swipe's early ramp-up (before the palm
        // has moved far enough for currentSwipeCandidate() to recognize it as a swipe) still
        // marks "pinch active" and blocks the swipe fallback once it does become recognizable a
        // few frames later, since the cooldown window straddles that transition (confirmed via
        // logcat: "Zoom candidate held" firing repeatedly right before "Fallback swipe ignored
        // during active pinch" for the exact same motion).
        if (zoomTrained.isEmpty() && hasCustomTrajectoryTraining()) return

        val thumb = landmarks[4]
        val index = landmarks[8]
        val dx = thumb.x() - index.x()
        val dy = thumb.y() - index.y()
        val distance = sqrt(dx * dx + dy * dy)

        pinchTrajectory.addLast(now to distance)
        while (pinchTrajectory.isNotEmpty() && now - pinchTrajectory.first().first > SWIPE_WINDOW_MS) {
            pinchTrajectory.removeFirst()
        }

        if (recentPinchRange() >= PINCH_BLOCK_SWIPE_RANGE_THRESHOLD) {
            lastPinchMotionAtMs = now
        }

        val palmMotion = recentPalmMotion(now)
        if (palmMotion > PINCH_MAX_PALM_MOTION) {
            // A real pinch often pulls the palm sideways slightly. Do not fire zoom while the
            // palm is moving too much, but keep the short pinch-active block so this same motion
            // cannot immediately fall through as a left/right swipe.
            if (pinchTrajectory.isNotEmpty()) {
                Log.d(TAG, "Zoom candidate held: palm moved too much (motion=$palmMotion > $PINCH_MAX_PALM_MOTION)")
            }
            return
        }

        if (zoomTrained.isNotEmpty()) {
            checkTrainedZoom(now, zoomTrained)
        } else {
            checkFixedPinch(distance, now)
        }
    }

    private fun hasCustomTrajectoryTraining(): Boolean =
        trainedTrajectory.keys.any { it.family != GestureFamily.ZOOM }

    private fun checkTrainedZoom(now: Long, zoomTrained: Map<GestureAction, List<FloatArray>>) {
        if (pinchTrajectory.size < 3) return
        val distances = pinchTrajectory.map { it.second }
        val range = (distances.maxOrNull() ?: 0f) - (distances.minOrNull() ?: 0f)
        if (range < ZOOM_DISTANCE_RANGE_THRESHOLD) return
        if (now - lastGestureAtMs < GESTURE_COOLDOWN_MS) {
            Log.d(TAG, "Zoom candidate: in cooldown (range=$range)")
            return
        }

        val delta = distances.last() - distances.first()
        val liveSample = GestureMatcher.normalizeTrajectory(
            GestureMatcher.resampleTrajectory(pinchTrajectory.map { it.second to 0f })
        )

        val ranked = zoomTrained.entries
            .filter { (action, _) -> zoomDirectionMatches(action, delta) }
            .map { (action, samples) -> action to GestureMatcher.bestTrajectoryDistance(liveSample, samples) }
            .sortedBy { it.second }
        val match = ranked.firstOrNull()
            ?.takeIf { it.second < TRAJECTORY_MATCH_THRESHOLD }
            ?.takeIf { ranked.isClearWinner(TRAJECTORY_MATCH_MARGIN) }

        if (match == null) {
            Log.d(TAG, "Zoom candidate rejected: range=$range delta=$delta ranked=$ranked (threshold=$TRAJECTORY_MATCH_THRESHOLD margin=$TRAJECTORY_MATCH_MARGIN)")
            return
        }

        lastGestureAtMs = now
        pinchTrajectory.clear()
        lastPinchMotionAtMs = 0L
        Log.d(TAG, "Zoom gesture recognized: ${match.first} (trained)")
        mainHandler.post { dispatchAction(match.first) }
    }

    /** Untrained fallback only - once either zoom direction has a trained template,
     * checkTrainedZoom above takes over entirely (see checkZoomGesture).
     * Used to require a full open<->closed toggle (fire PINCH_IN only on the open->closed
     * transition, PINCH_OUT only on closed->open) - real user report: "it becomes harder after
     * the first correct pinch". Root cause: repeated small squeezes rarely re-cross all the way
     * past PINCH_OPEN_THRESHOLD between each one, so only the very first pinch (starting from a
     * genuinely open hand) could ever fire. Fixed by dropping the toggle requirement entirely -
     * fires purely on distance band + firePinch's own GESTURE_COOLDOWN_MS gate, same "repeat on
     * cooldown, no return-to-neutral needed" pattern the cardinal swipes already use. CLOSED
     * (0.08) and OPEN (0.12) stay far enough apart that a single distance value can never satisfy
     * both, so this can't double-fire both directions from the same frame. */
    private fun checkFixedPinch(distance: Float, now: Long) {
        if (distance < PINCH_CLOSED_THRESHOLD) {
            firePinch(PinchGesture.PINCH_IN, now)
        } else if (distance > PINCH_OPEN_THRESHOLD) {
            firePinch(PinchGesture.PINCH_OUT, now)
        }
    }

    private fun firePinch(gesture: PinchGesture, now: Long) {
        if (now - lastGestureAtMs < GESTURE_COOLDOWN_MS) return
        lastGestureAtMs = now
        trajectory.clear()
        pinchTrajectory.clear()
        lastPinchMotionAtMs = 0L
        mainHandler.post {
            when (gesture) {
                PinchGesture.PINCH_IN -> dispatchZoom(zoomIn = true)
                PinchGesture.PINCH_OUT -> dispatchZoom(zoomIn = false)
            }
        }
    }

    // ---- Trajectory gestures: trained-template match first, fixed 4-direction fallback for
    // the cardinal swipes only (so they work before anything's been trained) ----

    private fun checkTrajectoryGesture(now: Long) {
        if (trajectory.size < 3) return
        val (_, x0, y0) = trajectory.first()
        val (_, x1, y1) = trajectory.last()
        // Axis-swapped at the source: live testing (all 4 cardinal directions tested explicitly)
        // showed a clean transpose - real horizontal motion was being read as the vertical (raw
        // y) delta and vice versa (down->dispatched left, up->right, left->down, right->up, all
        // consistent with dx/dy meaning the opposite axis from what the rest of this file
        // assumed). Worked out algebraically from the 4 observed mappings: swapping raw dx/dy
        // right here, with NO sign negation needed, reproduces all 4 correctly - everything
        // downstream (fixedDirectionFallback, trajectoryAxisMatches) reads the corrected values.
        // DTW-based trained matching itself is unaffected (shape comparison is self-referential,
        // same convention used for both recording and live), but trajectoryAxisMatches' axis
        // pre-filter was silently rejecting correct trained candidates before DTW ever ran.
        val dx = y1 - y0
        val dy = x1 - x0
        val distance = sqrt(dx * dx + dy * dy)
        if (distance < SWIPE_DISTANCE_THRESHOLD) return
        if (now - lastGestureAtMs < GESTURE_COOLDOWN_MS) return

        val liveSample = GestureMatcher.normalizeTrajectory(
            GestureMatcher.resampleTrajectory(trajectory.map { it.second to it.third })
        )

        // Zoom gestures are matched separately against the thumb-index distance signal (see
        // checkTrainedZoom) - the palm barely moves during a pinch, so they're excluded here to
        // avoid a stray palm-position match against a zoom template.
        val trainedMatches = trainedTrajectory.entries
            .filter { (action, _) -> action.family != GestureFamily.ZOOM }
            .filter { (action, _) -> trajectoryAxisMatches(action, dx, dy) }
            .map { (action, samples) -> action to GestureMatcher.bestTrajectoryDistance(liveSample, samples) }
            .sortedBy { it.second }
        val trainedMatch = trainedMatches
            .firstOrNull()
            ?.takeIf { it.second < TRAJECTORY_MATCH_THRESHOLD }
            ?.takeIf { trainedMatches.isClearWinner(TRAJECTORY_MATCH_MARGIN) }

        val fallbackAction = fixedDirectionFallback(dx, dy)?.takeIf {
            val blocked = isPinchActive(now)
            if (blocked) Log.d(TAG, "Fallback swipe ignored during active pinch: dx=$dx dy=$dy")
            !blocked
        }
        val action = trainedMatch?.first ?: fallbackAction
        if (action == null) return // no trained match, and this isn't a clean cardinal swipe

        if (action == oppositeSwipe(lastSwipeAction) && now - lastSwipeAtMs < OPPOSITE_SWIPE_COOLDOWN_MS) {
            // The return swing of bringing the hand back to repeat the last swipe - not a real
            // opposite-direction gesture. Consume the trajectory so this same return motion isn't
            // re-evaluated next frame, but don't touch lastGestureAtMs/lastSwipeAction - a real
            // gesture right after this should still be free to fire on its own timing.
            Log.d(TAG, "Swipe suppressed as return motion from $lastSwipeAction: $action")
            trajectory.clear()
            return
        }

        lastGestureAtMs = now
        trajectory.clear()
        pinchTrajectory.clear()
        endHold()
        if (action.family == GestureFamily.SWIPE_HORIZONTAL || action.family == GestureFamily.SWIPE_VERTICAL) {
            lastSwipeAction = action
            lastSwipeAtMs = now
        }
        Log.d(TAG, "Trajectory gesture recognized: $action (trained=${trainedMatch != null})")
        mainHandler.post { dispatchAction(action) }
    }

    private fun oppositeSwipe(action: GestureAction?): GestureAction? = when (action) {
        GestureAction.SWIPE_UP -> GestureAction.SWIPE_DOWN
        GestureAction.SWIPE_DOWN -> GestureAction.SWIPE_UP
        GestureAction.SWIPE_LEFT -> GestureAction.SWIPE_RIGHT
        GestureAction.SWIPE_RIGHT -> GestureAction.SWIPE_LEFT
        else -> null
    }

    /** Only fires for a direction with NO trained template at all - once a direction has been
     * trained, its trained template is what recognizes it (see checkTrajectoryGesture above),
     * this fallback only fills the gap for cardinal directions nobody's trained yet. */
    private fun fixedDirectionFallback(dx: Float, dy: Float): GestureAction? {
        // Mirrored-frame note (see swipe-direction comment history): dx>0 in this raw sensor
        // frame is the hand moving toward the phone's physical LEFT, not right.
        val candidate = if (abs(dx) > abs(dy) * SWIPE_AXIS_DOMINANCE_RATIO) {
            if (dx > 0) GestureAction.SWIPE_LEFT else GestureAction.SWIPE_RIGHT
        } else if (abs(dy) > abs(dx) * SWIPE_AXIS_DOMINANCE_RATIO) {
            if (dy > 0) GestureAction.SWIPE_DOWN else GestureAction.SWIPE_UP
        } else return null
        return if (trainedTrajectory.containsKey(candidate)) null else candidate
    }

    // ---- Hold gestures: continuous scroll while a trained pose keeps matching ----

    private fun checkHoldGesture(landmarks: List<NormalizedLandmark>, now: Long) {
        if (trainedHold.isEmpty()) return
        if (now - lastGestureAtMs < GESTURE_COOLDOWN_MS || recentPalmMotion(now) > HOLD_MAX_PALM_MOTION) {
            endHold()
            return
        }
        val pose = FloatArray(GestureMatcher.POSE_LANDMARK_COUNT * 2)
        for (i in 0 until GestureMatcher.POSE_LANDMARK_COUNT) {
            pose[i * 2] = landmarks[i].x()
            pose[i * 2 + 1] = landmarks[i].y()
        }
        val normalized = GestureMatcher.normalizePose(pose)

        val rankedMatches = trainedHold.entries
            .map { (action, samples) -> action to GestureMatcher.bestPoseDistance(normalized, samples) }
            .sortedBy { it.second }
        val bestMatch = rankedMatches
            .firstOrNull()
            ?.takeIf { it.second < POSE_MATCH_THRESHOLD }
            ?.takeIf { rankedMatches.isClearWinner(POSE_MATCH_MARGIN) }

        if (bestMatch == null) {
            endHold()
            return
        }

        val (action, _) = bestMatch
        if (activeHoldAction != action) {
            activeHoldAction = action
            holdConsecutiveFrames = 0
            lastHoldFireAtMs = 0L
        }
        holdConsecutiveFrames++
        if (holdConsecutiveFrames < HOLD_CONFIRM_FRAMES) return

        if (now - lastHoldFireAtMs >= HOLD_REPEAT_INTERVAL_MS) {
            lastHoldFireAtMs = now
            mainHandler.post { dispatchAction(action) }
        }
    }

    private fun endHold() {
        activeHoldAction = null
        holdConsecutiveFrames = 0
    }

    private fun recentPinchRange(): Float {
        if (pinchTrajectory.isEmpty()) return 0f
        val distances = pinchTrajectory.map { it.second }
        return (distances.maxOrNull() ?: 0f) - (distances.minOrNull() ?: 0f)
    }

    private fun isPinchActive(now: Long): Boolean =
        pinchEnabled && now - lastPinchMotionAtMs < GESTURE_COOLDOWN_MS

    private fun List<Pair<GestureAction, Float>>.isClearWinner(margin: Float): Boolean {
        if (isEmpty()) return false
        val runnerUp = getOrNull(1)?.second ?: return true
        return runnerUp - first().second >= margin
    }

    private fun zoomDirectionMatches(action: GestureAction, delta: Float): Boolean = when (action) {
        GestureAction.ZOOM_IN -> delta < -ZOOM_DIRECTION_EPSILON
        GestureAction.ZOOM_OUT -> delta > ZOOM_DIRECTION_EPSILON
        else -> true
    }

    private fun trajectoryAxisMatches(action: GestureAction, dx: Float, dy: Float): Boolean = when (action.family) {
        GestureFamily.SWIPE_HORIZONTAL -> abs(dx) > abs(dy) * SWIPE_AXIS_DOMINANCE_RATIO
        GestureFamily.SWIPE_VERTICAL -> abs(dy) > abs(dx) * SWIPE_AXIS_DOMINANCE_RATIO
        else -> true
    }

    // ---- Dispatch: real action for every trainable GestureAction ----

    private fun dispatchAction(action: GestureAction) {
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2
        val cy = metrics.heightPixels / 2
        val span = (metrics.widthPixels * 0.35f).toInt()
        val service = ScifiAccessibilityService.instance

        when (action) {
            GestureAction.SWIPE_UP -> service?.swipeCoords(cx, cy + span / 2, cx, cy - span / 2)
            GestureAction.SWIPE_DOWN -> service?.swipeCoords(cx, cy - span / 2, cx, cy + span / 2)
            GestureAction.SWIPE_LEFT -> service?.swipeCoords(cx + span / 2, cy, cx - span / 2, cy)
            GestureAction.SWIPE_RIGHT -> service?.swipeCoords(cx - span / 2, cy, cx + span / 2, cy)
            GestureAction.ZOOM_IN -> dispatchZoom(zoomIn = true)
            GestureAction.ZOOM_OUT -> dispatchZoom(zoomIn = false)
            GestureAction.OPEN_GLOBE ->
                startActivity(Intent(this, GlobeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            GestureAction.OPEN_REACTOR ->
                startActivity(Intent(this, XenosActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            GestureAction.SLEEP -> runCatching {
                val dpm = getSystemService(DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
                dpm?.lockNow()
            }.onFailure { Log.e(TAG, "lockNow failed", it) }
            GestureAction.OPEN_AI_CHAT -> startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_ELENE_COMMAND, "open_chat")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            GestureAction.OPEN_APP -> {
                val pkg = GestureTemplateStore.load(this, GestureAction.OPEN_APP)?.openAppPackage
                val launchIntent = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
                if (launchIntent != null) {
                    startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    Toast.makeText(this, "No app chosen for this gesture yet.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun dispatchZoom(zoomIn: Boolean) {
        if (GlobeActivity.zoomActiveGlobe(zoomIn)) return
        ScifiAccessibilityService.instance?.pinchZoom(zoomIn = zoomIn)
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Gesture Control", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gesture control active")
            .setContentText("Watching for hand gestures - turn off in Settings when done")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
