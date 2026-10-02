package com.example.scifilauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

private const val TAG = "WelcomeFaceSkeleton"

/**
 * Live front-camera face presence for the WelcomeScreen ("XENOS HACKER" screen, reached by
 * tapping back to welcome from the dashboard). Standalone FaceLandmarker pipeline, entirely
 * separate from HandGestureService's own - a phone has one front camera, and two consumers can't
 * bind it at once, so gesture control is turned off first if it was on (same pattern/reasoning
 * GestureTrainingActivity.releaseGestureControlCameraIfActive already established: stop the
 * service, clear the pref so the Security toggle honestly reflects it's off, and deliberately do
 * NOT auto-restore it - the user turns it back on themselves).
 *
 * Drives [EyeVisual]. No raw camera feed is ever drawn - only presence (is a face there right
 * now) and a rough gaze direction feed into the eye, same "skeleton, not a selfie" spirit as
 * HandSkeletonOverlayView's dot rendering. (XenosActivity/Xenos chat shows a DIFFERENT visual,
 * [XenosSkeleton] below - user corrected an earlier mix-up here: "xenos should be the skelton not
 * the eye". XenosSkeleton runs its OWN separate camera+FaceLandmarker pipeline for presence only -
 * its actual shape is never driven by your real landmarks, only by Xenos's own talking/idle state,
 * per the user's later clarification: "the skeleton is owned by the ai... only folows it own
 * emotion".)
 *
 * User's own words for the eye's behavior here (superseding the earlier fixed-timer version):
 * "let it b that the eye is normally closed when no one is ooking but when somone is it tares at
 * the person and his iris follows ur face and when no one is looking it eyse closes... dont
 * follow nthe rule of 1 minute open and 1 minute close". The old red-dot skeleton cloud and the
 * GlitchCoverView roots background are both gone too ("remove the skeleton fce") - the eye
 * opening/staring IS the reaction now, nothing drawn on top of it.
 */
@Composable
fun WelcomeFaceSkeleton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var points by remember { mutableStateOf<List<Offset>?>(null) }

    DisposableEffect(Unit) {
        Log.d(TAG, "DisposableEffect entering composition")
        val prefs = context.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("gesture_control_enabled", false)) {
            context.stopService(Intent(context, HandGestureService::class.java))
            prefs.edit().putBoolean("gesture_control_enabled", false).apply()
        }

        val executor = Executors.newSingleThreadExecutor()
        val faceLandmarker = runCatching {
            val baseOptions = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setResultListener { result, _ ->
                    val landmarks = result.faceLandmarks().firstOrNull()
                    points = landmarks?.map { Offset(it.x(), it.y()) }
                }
                .setErrorListener { e -> Log.e(TAG, "FaceLandmarker error", e) }
                .build()
            FaceLandmarker.createFromOptions(context, options)
        }.onFailure {
            Log.e(TAG, "FaceLandmarker.createFromOptions failed", it)
        }.getOrNull()
        Log.d(TAG, "faceLandmarker created = ${faceLandmarker != null}")

        var cameraProvider: ProcessCameraProvider? = null
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            // No hardcoded setTargetRotation here - that ROTATION_0 pin was copied from
            // HandGestureService, a headless background service with no window/display to match,
            // where a fixed target is the right call. MainActivity (this screen's host) has no
            // screenOrientation lock in the manifest, so a fixed ROTATION_0 could mismatch the
            // real display rotation and skew the landmark mapping. Leaving targetRotation unset
            // makes CameraX pick it up from the default display's actual current rotation.
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { imageProxy: ImageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null && faceLandmarker != null) {
                    runCatching {
                        val mpImage = MediaImageBuilder(mediaImage).build()
                        val processingOptions = ImageProcessingOptions.builder()
                            .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
                            .build()
                        faceLandmarker.detectAsync(mpImage, processingOptions, System.currentTimeMillis())
                    }.onFailure { Log.e(TAG, "detectAsync failed", it) }
                }
                imageProxy.close()
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                Log.d(TAG, "camera bound to lifecycle")
            }.onFailure { Log.e(TAG, "bindToLifecycle failed", it) }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            Log.d(TAG, "DisposableEffect leaving composition")
            runCatching { cameraProvider?.unbindAll() }
            runCatching { faceLandmarker?.close() }
            executor.shutdown()
        }
    }

    val currentPoints = points
    // Presence-only detection now, not fine per-frame gaze-tracking - per the user's explicit
    // call: "it shouldn't scan for people[s] eyes but instead people that pass[es] in front of
    // it". The eye still opens/stares the exact same way on facePresent.
    val facePresent = currentPoints != null
    // A middle ground the user asked for after seeing presence-only feel static: the iris still
    // turns roughly toward where you are, but computed from a SINGLE landmark (index 1, the nose
    // tip in MediaPipe's face mesh) quantized to three zones (left/center/right), not the old
    // continuous centroid average over all ~478 points every frame - real following, at a
    // fraction of the per-frame cost.
    val gazeOffset = run {
        val noseTip = currentPoints?.getOrNull(1) ?: return@run Offset.Zero
        val correctedX = 1f - noseTip.y
        val zoned = when {
            correctedX < 0.4f -> -0.4f
            correctedX > 0.6f -> 0.4f
            else -> 0f
        }
        Offset(zoned, 0f)
    }

    val phrases = remember {
        listOf(
            "I can see you.",
            "I am watching you.",
            "My maker is Xenos.",
            "I am the all-seeing eye.",
            "Bow to your emperor."
        )
    }
    var bubbleText by remember { mutableStateOf<String?>(null) }
    val latestFacePresent = rememberUpdatedState(facePresent)
    // Every 5 minutes, only if someone is actually there right at that moment - "as long as
    // someone passes by it", not a fixed timer that fires into an empty room.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60_000L)
            if (latestFacePresent.value) {
                bubbleText = phrases.random()
                delay(5_000L)
                bubbleText = null
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        EyeVisual(modifier = Modifier.fillMaxSize(), facePresent = facePresent, gazeOffset = gazeOffset)
        bubbleText?.let { text ->
            EyeSpeechBubble(text = text, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun EyeSpeechBubble(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .padding(top = 64.dp)
            .padding(horizontal = 28.dp)
            .background(Color(0xFF190505), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFFFF4444).copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            color = Color(0xFFFF4444),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * The red binary-ring eye - a dark medallion disk holding curved rows of binary digits above/
 * below a real eyelid-cropped lens shape (not a plain oval), with a detailed red-toned iris/pupil.
 * Shared by WelcomeScreen (real camera-driven [facePresent]/[gazeOffset]) and XenosActivity's
 * Xenos visual (always `facePresent = true`, `gazeOffset = Offset.Zero` - "doesn't follow u it
 * stille xist even if u not looking", no camera involved there at all).
 *
 * Open/closed is a smooth animated response to [facePresent] (closed when nobody's there, opens
 * and stares when a face shows up) - NOT a fixed timer. The iris also drifts toward [gazeOffset]
 * within the sclera, a coarse "follows your face" look, while the eyelid lens shape itself stays
 * put (blink/open-close is drawn as eyelid covers sliding over a fixed eyeball, never squashing
 * the whole shape).
 */
@Composable
fun EyeVisual(modifier: Modifier = Modifier, facePresent: Boolean, gazeOffset: Offset = Offset.Zero) {
    val upperRows = remember { List(6) { List(26) { Random.nextInt(0, 2) } } }
    val lowerRows = remember { List(6) { List(26) { Random.nextInt(0, 2) } } }

    val eyeOpenness by animateFloatAsState(
        targetValue = if (facePresent) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "eyeOpenness"
    )
    val gazeX by animateFloatAsState(targetValue = gazeOffset.x, animationSpec = tween(300), label = "gazeX")
    val gazeY by animateFloatAsState(targetValue = gazeOffset.y, animationSpec = tween(300), label = "gazeY")

    val digitPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF4444.toInt()
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
    }

    Canvas(modifier = modifier) {
        drawRect(color = Color.Black, size = size)

        val cx = size.width / 2f
        val cy = size.height / 2f
        val minDim = minOf(size.width, size.height)
        val medallionRadius = minDim * 0.46f

        drawCircle(color = Color(0xFF190505), radius = medallionRadius, center = Offset(cx, cy))
        drawCircle(
            color = Color(0xFFFF3333),
            alpha = 0.35f,
            radius = medallionRadius,
            center = Offset(cx, cy),
            style = Stroke(width = minDim * 0.006f)
        )

        val medallionClip = Path().apply {
            addOval(Rect(cx - medallionRadius, cy - medallionRadius, cx + medallionRadius, cy + medallionRadius))
        }
        clipPath(medallionClip) {
            digitPaint.textSize = minDim * 0.03f
            val nativeCanvas = drawContext.canvas.nativeCanvas
            fun drawArcRow(digits: List<Int>, radiusFrac: Float, startDeg: Float, endDeg: Float, alpha: Int) {
                digitPaint.alpha = alpha
                val radius = medallionRadius * radiusFrac
                val n = digits.size
                for (i in 0 until n) {
                    val t = i / (n - 1).toFloat()
                    val angle = Math.toRadians((startDeg + (endDeg - startDeg) * t).toDouble())
                    val dx = cx + radius * cos(angle).toFloat()
                    val dy = cy + radius * sin(angle).toFloat() + digitPaint.textSize * 0.3f
                    nativeCanvas.drawText(digits[i].toString(), dx, dy, digitPaint)
                }
            }
            val radiiFracs = listOf(0.32f, 0.44f, 0.56f, 0.68f, 0.80f, 0.92f)
            for ((idx, frac) in radiiFracs.withIndex()) {
                val alpha = (80 + idx * 14).coerceAtMost(210)
                drawArcRow(upperRows[idx], frac, 200f, 340f, alpha)
                drawArcRow(lowerRows[idx], frac, 20f, 160f, alpha)
            }
        }

        val eyeHalfWidth = minDim * 0.30f
        val eyeHalfHeight = minDim * 0.145f
        val lensPath = Path().apply {
            moveTo(cx - eyeHalfWidth, cy)
            quadraticBezierTo(cx, cy - eyeHalfHeight, cx + eyeHalfWidth, cy)
            quadraticBezierTo(cx, cy + eyeHalfHeight * 0.82f, cx - eyeHalfWidth, cy)
            close()
        }

        clipPath(lensPath) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF6B2020), Color(0xFF200606)),
                    center = Offset(cx, cy),
                    radius = eyeHalfWidth
                ),
                topLeft = Offset(cx - eyeHalfWidth, cy - eyeHalfHeight),
                size = Size(eyeHalfWidth * 2f, eyeHalfHeight * 2f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color(0xAA000000)),
                    center = Offset(cx, cy),
                    radius = eyeHalfWidth
                ),
                radius = eyeHalfWidth,
                center = Offset(cx, cy)
            )

            // Iris center drifts toward gazeOffset within the sclera - "his iris follows ur
            // face". Travel range is a fraction of the socket so it never leaves the lens.
            val irisRadius = eyeHalfHeight * 0.95f
            val irisCenter = Offset(
                cx + gazeX * (eyeHalfWidth - irisRadius) * 0.9f,
                cy + gazeY * (eyeHalfHeight - irisRadius) * 0.9f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFFF8866), Color(0xFF7A1010), Color(0xFF3A0505)),
                    center = irisCenter,
                    radius = irisRadius
                ),
                radius = irisRadius,
                center = irisCenter
            )
            val spokeCount = 28
            for (i in 0 until spokeCount) {
                val angle = Math.toRadians((360.0 / spokeCount) * i)
                val innerR = irisRadius * 0.32f
                drawLine(
                    color = Color(0x33FFDDCC),
                    start = Offset(irisCenter.x + innerR * cos(angle).toFloat(), irisCenter.y + innerR * sin(angle).toFloat()),
                    end = Offset(irisCenter.x + irisRadius * cos(angle).toFloat(), irisCenter.y + irisRadius * sin(angle).toFloat()),
                    strokeWidth = 1f
                )
            }
            drawCircle(
                color = Color(0x40FFDDCC),
                radius = irisRadius * 0.62f,
                center = irisCenter,
                style = Stroke(width = 1.2f)
            )
            drawCircle(color = Color.Black, radius = irisRadius * 0.42f, center = irisCenter)
            drawCircle(
                color = Color(0x55FFFFFF),
                radius = irisRadius * 0.12f,
                center = Offset(irisCenter.x - irisRadius * 0.28f, irisCenter.y - irisRadius * 0.28f)
            )

            // Eyelid covers - closed by default, open (retract) as eyeOpenness rises toward 1.
            val closedAmount = (1f - eyeOpenness).coerceIn(0f, 1f)
            val lidTravel = eyeHalfHeight * closedAmount
            drawRect(
                color = Color.Black,
                topLeft = Offset(cx - eyeHalfWidth - 4f, cy - eyeHalfHeight - 4f),
                size = Size(eyeHalfWidth * 2f + 8f, eyeHalfHeight + 4f + lidTravel)
            )
            drawRect(
                color = Color.Black,
                topLeft = Offset(cx - eyeHalfWidth - 4f, cy + eyeHalfHeight * 0.82f - lidTravel),
                size = Size(eyeHalfWidth * 2f + 8f, eyeHalfHeight * 0.82f + 4f + lidTravel)
            )
        }

        drawPath(
            path = lensPath,
            color = Color(0xFFFF3333),
            alpha = 0.55f,
            style = Stroke(width = minDim * 0.006f)
        )
    }
}

/**
 * Xenos's own visual - a red dot-skeleton in the shape of a real face, drawn from
 * [XENOS_FACE_MESH_RAW] (a genuine captured MediaPipe sample, not a hand-built approximation -
 * user rejected an earlier procedural version twice: "what trhe hell is that skeleton", "for my
 * face my face def doesnt look like that"; pointed at the real dot-cloud rendering the gesture-
 * control preview already shows as the reference, so this now draws with those exact points).
 * User: "xenos should be the skelton not the eye" - correcting an earlier mix-up where
 * XenosActivity was wired to [EyeVisual] instead.
 *
 * Runs its own front-camera + FaceLandmarker pipeline (same pattern as [WelcomeFaceSkeleton]:
 * turns off gesture control first if it was on, since only one consumer can hold the front
 * camera) - but ONLY for presence, a plain "is a face there right now" boolean via
 * [onFacePresenceChanged]. User: "make that the skeleton only appears when u bring ur face...
 * once no skeleton shows then it cant hear or reply u" - the caller (XenosActivity) uses this to
 * gate whether the mic can even be tapped.
 *
 * The skeleton's actual shape is NEVER driven by your real-time landmark positions, even while
 * visible - user: "the skeleton is owned by the ai so no matter ho ur face does the skelton only
 * folows it own emotion it think the ai make it do". It's a fixed real face shape; only Xenos's
 * own state moves it: [isSpeaking] opens the mouth (lower lip drops, upper lip barely moves - a
 * real mouth-opening motion, not a uniform spread - confirmed against a real live face-mesh
 * screenshot comparison after an earlier version scrambled into a starburst), and [expression]
 * (backend-driven, see main.py's "Your face" system-prompt section - a genuine reaction to the
 * conversation, "smile"/"frown"/"curious"/"neutral") curves the whole mouth line and, for
 * "curious", tilts the whole head. User: "let it express eemotion like frown or curios... let it
 * know he can do that".
 */
@Composable
fun XenosSkeleton(
    modifier: Modifier = Modifier,
    isSpeaking: Boolean = false,
    // "smile" | "frown" | "curious" | "neutral" - Xenos's own genuine reaction, driven by the
    // backend's "emotion" field (see main.py's "Your face" system-prompt section), never by
    // mirroring your real expression.
    expression: String = "neutral",
    onFacePresenceChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var facePresent by remember { mutableStateOf(false) }
    // Where you are, not what you're doing - used only to turn the head toward you (a "look at
    // you" billboard effect), never to reshape the face itself. User: "i wnat his head to move
    // with me so he must be facuing me alwys when i am facing him".
    var gazeOffset by remember { mutableStateOf(Offset.Zero) }
    // Real mouth aperture read straight off your own face (MediaPipe's inner-lip pair, landmark
    // 13 = upper inner lip, 14 = lower inner lip), normalized against your own face's real height
    // so it's roughly scale-invariant regardless of how close you are to the camera. User: "let
    // it be that it follows my face and opening of mouth and head rotation" - this is layered on
    // top of, not instead of, [isSpeaking] below: whichever is currently larger wins, so Xenos's
    // own TTS-driven talking still works exactly as before, but your real mouth opening now also
    // moves it even when he isn't speaking.
    var mouthOpenReal by remember { mutableStateOf(0f) }

    DisposableEffect(Unit) {
        val prefs = context.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("gesture_control_enabled", false)) {
            context.stopService(Intent(context, HandGestureService::class.java))
            prefs.edit().putBoolean("gesture_control_enabled", false).apply()
        }

        val executor = Executors.newSingleThreadExecutor()
        val faceLandmarker = runCatching {
            val baseOptions = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setResultListener { result, _ ->
                    val landmarks = result.faceLandmarks().firstOrNull()
                    facePresent = landmarks != null
                    if (landmarks != null) {
                        // Same axis-swap+flip + centroid-offset formula WelcomeFaceSkeleton's
                        // gaze tracking already uses.
                        var sx = 0f
                        var sy = 0f
                        for (p in landmarks) {
                            sx += 1f - p.y()
                            sy += 1f - p.x()
                        }
                        val n = landmarks.size
                        gazeOffset = Offset(
                            (((sx / n) - 0.5f) * 1.6f).coerceIn(-0.5f, 0.5f),
                            (((sy / n) - 0.5f) * 1.6f).coerceIn(-0.5f, 0.5f)
                        )

                        val upperLip = landmarks.getOrNull(13)
                        val lowerLip = landmarks.getOrNull(14)
                        if (upperLip != null && lowerLip != null) {
                            val gap = kotlin.math.abs(lowerLip.y() - upperLip.y())
                            val faceHeight = (landmarks.maxOf { it.y() } - landmarks.minOf { it.y() }).coerceAtLeast(0.01f)
                            mouthOpenReal = ((gap / faceHeight) * 4f).coerceIn(0f, 1f)
                        }
                    }
                }
                .setErrorListener { e -> Log.e(TAG, "XenosSkeleton FaceLandmarker error", e) }
                .build()
            FaceLandmarker.createFromOptions(context, options)
        }.getOrNull()

        var cameraProvider: ProcessCameraProvider? = null
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { imageProxy: ImageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null && faceLandmarker != null) {
                    runCatching {
                        val mpImage = MediaImageBuilder(mediaImage).build()
                        val processingOptions = ImageProcessingOptions.builder()
                            .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
                            .build()
                        faceLandmarker.detectAsync(mpImage, processingOptions, System.currentTimeMillis())
                    }.onFailure { Log.e(TAG, "XenosSkeleton detectAsync failed", it) }
                }
                imageProxy.close()
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            }.onFailure { Log.e(TAG, "XenosSkeleton bindToLifecycle failed", it) }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { cameraProvider?.unbindAll() }
            runCatching { faceLandmarker?.close() }
            executor.shutdown()
        }
    }

    LaunchedEffect(facePresent) { onFacePresenceChanged(facePresent) }

    val presenceAlpha by animateFloatAsState(
        targetValue = if (facePresent) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "xenosPresenceAlpha"
    )
    // User: "when am talking his mouth shouldnt move he should listen and when he wants to talk
    // back his mouth can move" - [isSpeaking] (from XenosActivity's TTS start/stop, NOT from the
    // mic/voice-note input) still guarantees the talking animation during TTS; [mouthOpenReal]
    // (your own real mouth aperture, see above) is layered on top so it also opens from your real
    // face the rest of the time - whichever is bigger at any instant wins.
    val mouthOpen by animateFloatAsState(
        targetValue = maxOf(if (isSpeaking) 1f else 0f, mouthOpenReal),
        animationSpec = tween(durationMillis = 120),
        label = "xenosMouthOpen"
    )
    val gazeX by animateFloatAsState(targetValue = gazeOffset.x, animationSpec = tween(250), label = "xenosGazeX")
    val gazeY by animateFloatAsState(targetValue = gazeOffset.y, animationSpec = tween(250), label = "xenosGazeY")

    // Real captured points, axis-swap+flip corrected (same convention as everywhere else in this
    // project) once, then normalized against their own bounding box so they fill the canvas
    // consistently regardless of exactly where/how large the face was in the original capture.
    val correctedPoints = remember {
        val raw = XENOS_FACE_MESH_RAW
        val n = raw.size / 2
        val pts = (0 until n).map { i ->
            val x = raw[i * 2]
            val y = raw[i * 2 + 1]
            Offset(1f - y, 1f - x)
        }
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        val w = (maxX - minX).coerceAtLeast(0.0001f)
        val h = (maxY - minY).coerceAtLeast(0.0001f)
        pts.map { Offset((it.x - minX) / w - 0.5f, (it.y - minY) / h - 0.5f) }
    }
    // Standard MediaPipe FaceMesh outer-lip-ring indices (well-known/widely published topology -
    // cross-checked against this project's own NOSE_TIP_LANDMARK_INDEX=1, which matches), split
    // by role so [mouthOpen]/[expression] can move different parts differently - a real mouth
    // opens by the lower lip/jaw dropping (upper lip barely moves) and smiles/frowns by the
    // corners moving, not by uniformly spreading every point from a shared center (too subtle/
    // wrong-looking, per user: "the mouth isnt opening although ut moved slightly").
    val cornerIndices = remember { setOf(61, 291) }
    val upperLipIndices = remember { setOf(185, 40, 39, 37, 0, 267, 269, 270, 409) }
    val lowerLipIndices = remember { setOf(146, 91, 181, 84, 17, 314, 405, 321, 375) }
    val mouthIndices = remember { cornerIndices + upperLipIndices + lowerLipIndices }
    val mouthCenter = remember(correctedPoints) {
        val pts = mouthIndices.mapNotNull { correctedPoints.getOrNull(it) }
        if (pts.isEmpty()) Offset.Zero
        else Offset(pts.sumOf { it.x.toDouble() }.toFloat() / pts.size, pts.sumOf { it.y.toDouble() }.toFloat() / pts.size)
    }
    // Half-width of the mouth's own real extent, used to build a bounded 0..1 "how close to the
    // corner is this point" weight for curving the WHOLE lip line on a smile/frown, not just the
    // two corner dots. Bounded/normalized against the mouth's real extent (not each point's own
    // raw distance, which is what made the earlier mouth-open attempt scramble into a starburst -
    // see lowerLipIndices below) so this stays predictable regardless of how unevenly the real
    // captured points happen to be spaced.
    val mouthHalfWidth = remember(correctedPoints) {
        val xs = mouthIndices.mapNotNull { correctedPoints.getOrNull(it)?.x }
        if (xs.isEmpty()) 0.1f else ((xs.max() - xs.min()) / 2f).coerceAtLeast(0.01f)
    }
    val smileAmount by animateFloatAsState(
        targetValue = if (expression == "smile") 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "xenosSmile"
    )
    val frownAmount by animateFloatAsState(
        targetValue = if (expression == "frown") 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "xenosFrown"
    )
    val curiousAmount by animateFloatAsState(
        targetValue = if (expression == "curious") 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "xenosCurious"
    )

    Canvas(modifier = modifier) {
        drawRect(color = Color.Black, size = size)
        if (presenceAlpha < 0.01f) return@Canvas

        val cx = size.width / 2f
        val cy = size.height / 2f
        val faceSize = minOf(size.width, size.height) * 0.72f
        val dotRadius = minOf(size.width, size.height) * 0.0035f
        // A real face isn't perfectly square in normalized-bbox space - keep it tall, not
        // stretched wide, by using the same faceSize for both axes but letting height run a
        // little taller (real face aspect is roughly 3:4 width:height).
        val faceW = faceSize
        val faceH = faceSize * 1.25f

        // Head rotation now genuinely follows your real head, not just a "look at you" billboard
        // toward your position in frame - [gazeX] shifts as your real landmarks shift when you
        // turn your head (not only when you move sideways), so a stronger gain here reads as
        // Xenos turning his head the way yours turns. User: "let it be that it follows my face
        // and opening of mouth and head rotation". "Curious" still adds a real head-tilt on top
        // (like a dog cocking its head).
        val turnAngleDeg = -gazeX * 32f + curiousAmount * 9f
        val shiftX = gazeX * faceW * 0.12f
        val shiftY = gazeY * faceH * 0.10f

        rotate(degrees = turnAngleDeg, pivot = Offset(cx, cy)) {
            correctedPoints.forEachIndexed { index, p ->
                var px = p.x
                var py = p.y
                // Bounded 0..1 weight (see mouthHalfWidth above) so smile/frown curve the WHOLE
                // lip line toward the corners, not just the two corner dots in isolation.
                val edgeWeight = (((p.x - mouthCenter.x).let { if (it < 0f) -it else it }) / mouthHalfWidth).coerceIn(0f, 1f)
                when (index) {
                    in lowerLipIndices -> {
                        // A rigid vertical shift, same amount for every point, x untouched - the
                        // lip curve just drops as one coherent band. Scaling each point's x by its
                        // own distance from mouthCenter (the old version) moved unevenly-spaced
                        // real points wildly different amounts and scrambled the curve into a
                        // radiating starburst instead of a mouth shape (confirmed via a real
                        // screenshot comparison against the real live face-mesh overlay).
                        py = p.y + mouthOpen * 0.09f - smileAmount * edgeWeight * 0.025f + frownAmount * edgeWeight * 0.02f
                    }
                    in upperLipIndices -> {
                        py = p.y - mouthOpen * 0.015f - smileAmount * edgeWeight * 0.02f + frownAmount * edgeWeight * 0.015f
                    }
                    in cornerIndices -> {
                        // Smile - corners lift and pull outward. Frown - corners droop and pull
                        // slightly inward. Whichever side each corner is actually on, worked out
                        // from its own position rather than assumed left/right.
                        val side = if (p.x < mouthCenter.x) -1f else 1f
                        px = p.x + side * (smileAmount * 0.045f - frownAmount * 0.02f)
                        py = p.y - smileAmount * 0.05f - mouthOpen * 0.02f + frownAmount * 0.045f
                    }
                }
                drawCircle(
                    color = Color(0xFFFF3333),
                    radius = dotRadius,
                    center = Offset(cx + shiftX + px * faceW, cy + shiftY + py * faceH),
                    alpha = (presenceAlpha * 0.85f).coerceIn(0f, 1f)
                )
            }
        }
    }
}

// Restarted on every value change - incrementing this from the SCREEN_ON receiver below is what
// re-opens each battery-saver camera window.
private const val LIVE_WALLPAPER_ACTIVE_WINDOW_MS = 8_000L

/** Wraps [WelcomeFaceSkeleton] as a selectable Live wallpaper (Settings > Wallpaper) - real
 * front-camera face tracking driving the same eye, not a canned animation. OFF battery saver runs
 * the camera pipeline the whole time the Dashboard is visible, same as the Welcome screen always
 * has. BALANCED/AGGRESSIVE only run it for a short window after the screen turns on (real
 * battery/privacy cost trimmed, matching what Battery Saver already means everywhere else in this
 * app), falling back to a static closed-eye the rest of the time - per the user's explicit call
 * on this tradeoff, not a guess. */
@Composable
fun WelcomeEyeLiveWallpaper(batteryMode: BatterySaverMode) {
    if (batteryMode == BatterySaverMode.OFF) {
        WelcomeFaceSkeleton(modifier = Modifier.fillMaxSize())
        return
    }
    val context = LocalContext.current
    var trigger by remember { mutableStateOf(0) }
    var active by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) { trigger++ }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    LaunchedEffect(trigger) {
        active = true
        delay(LIVE_WALLPAPER_ACTIVE_WINDOW_MS)
        active = false
    }

    if (active) {
        WelcomeFaceSkeleton(modifier = Modifier.fillMaxSize())
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF020202))) {
            EyeVisual(modifier = Modifier.fillMaxSize(), facePresent = false)
        }
    }
}

/** Same idea as [WelcomeEyeLiveWallpaper] but for the Xenos face (Settings > Wallpaper's "Xenos"
 * Live entry) - idle skeleton (not speaking, neutral expression, since there's no live
 * conversation happening on the Dashboard), gated by battery saver the same way. */
@Composable
fun XenosFaceLiveWallpaper(batteryMode: BatterySaverMode) {
    if (batteryMode == BatterySaverMode.OFF) {
        XenosSkeleton(modifier = Modifier.fillMaxSize())
        return
    }
    val context = LocalContext.current
    var trigger by remember { mutableStateOf(0) }
    var active by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) { trigger++ }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    LaunchedEffect(trigger) {
        active = true
        delay(LIVE_WALLPAPER_ACTIVE_WINDOW_MS)
        active = false
    }

    if (active) {
        XenosSkeleton(modifier = Modifier.fillMaxSize())
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF020202)))
    }
}
