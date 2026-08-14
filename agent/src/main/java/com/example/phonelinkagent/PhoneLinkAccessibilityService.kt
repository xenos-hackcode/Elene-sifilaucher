package com.example.phonelinkagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The only accessibility service in this app - real gesture dispatch (same mechanics as
 * SciFiLauncher's ScifiAccessibilityService: GestureDescription taps/swipes, ACTION_SET_TEXT for
 * typing, GLOBAL_ACTION_* for back/home/recents) plus the phone-link WebSocket session and
 * continuous screen-capture loop. No unrelated app logic of any kind lives here - this app does
 * exactly one thing.
 */
class PhoneLinkAccessibilityService : AccessibilityService() {

    companion object {
        var instance: PhoneLinkAccessibilityService? = null
        private const val FRAME_INTERVAL_MS = 150L
        private const val MAX_CONSECUTIVE_FRAME_FAILURES = 8
        private const val PAUSED_CHANNEL_ID = "phone_link_paused"
        private const val PAUSED_NOTIFICATION_ID = 4825
    }

    private val handler = Handler(Looper.getMainLooper())

    private var client: PhoneLinkAgentClient? = null
    @Volatile private var streaming = false
    // True between MediaProjection being revoked (screen off) and a fresh consent grant -
    // streaming stays true the whole time (websocket/session logically still "on"), this is
    // the finer-grained flag for "capture specifically isn't running right now".
    @Volatile private var paused = false
    private var controllerPresent = false
    private var pendingFrameRequestId: String? = null

    // Held for the whole streaming session (start to stop), not tied to ScreenCaptureService's
    // own lifecycle - that service can fully stop and restart across a pause/resume cycle
    // (confirmed necessary live 2026-08-10: it crashes if it tries to stay foreground-alive
    // through a projection loss, see ScreenCaptureService.onStop()), but the CPU should stay
    // awake for the whole logical session regardless. Real known limit found the same day: this
    // alone does NOT keep capture alive through a screen-off - Android revokes MediaProjection
    // on screen-off unconditionally, wake lock or not - it only helps avoid unrelated CPU-sleep
    // stalls while the screen is genuinely on but the app is backgrounded/idle.
    private var wakeLock: PowerManager.WakeLock? = null

    // A single missed frame (acquireLatestImage() returning null, a real and unremarkable
    // hiccup on some hardware - confirmed live on a Unisoc-chipset device where frame #2 of a
    // session failed while every other frame around it succeeded) must NOT kill the whole
    // session - only a genuine sustained failure should. ACTION_FRAME_ERROR just skips that one
    // frame and tries again; ACTION_PROJECTION_STOPPED (screen off - Android revokes the grant)
    // pauses instead of ending the session, see pauseSession() below.
    private var consecutiveFrameFailures = 0

    var onStatusChanged: ((PhoneLinkAgentStatus) -> Unit)? = null

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenCaptureService.ACTION_FRAME_READY -> onFrameReady(intent)
                ScreenCaptureService.ACTION_FRAME_ERROR -> onFrameFailed()
                ScreenCaptureService.ACTION_PROJECTION_STOPPED -> {
                    // Android itself revokes MediaProjection when the screen turns off - a
                    // deliberate platform privacy behavior, not something a wake lock or any
                    // other in-app fix can prevent. Found live 2026-08-10. Real consequence:
                    // resuming needs one fresh user consent tap, Android won't allow silently
                    // restarting capture - so this pauses (keeps the websocket + foreground
                    // service alive, shows a real ongoing notification with a resume action)
                    // instead of fully logging out, so reconnecting is as fast as possible.
                    android.util.Log.w("PhoneLinkAgent", "Projection stopped (screen off) - pausing")
                    pauseSession()
                }
                ScreenCaptureService.ACTION_STOP_SESSION_REQUESTED -> {
                    android.util.Log.d("PhoneLinkAgent", "Stop requested from paused notification")
                    stopSession()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_FRAME_READY)
            addAction(ScreenCaptureService.ACTION_FRAME_ERROR)
            addAction(ScreenCaptureService.ACTION_PROJECTION_STOPPED)
            addAction(ScreenCaptureService.ACTION_STOP_SESSION_REQUESTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(captureReceiver, filter)
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { unregisterReceiver(captureReceiver) }
        stopSession()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- gesture dispatch ----

    fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipeCoords(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 250): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditableNode(root) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFirstEditableNode(child)?.let { return it }
        }
        return null
    }

    fun goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun openRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    // ---- phone-link session ----

    fun isActive(): Boolean = streaming

    /** Accurate current status for a caller that wasn't around to receive onStatusChanged
     * updates as they happened (e.g. MainActivity re-reading state fresh on onCreate/onResume,
     * after being closed and reopened) - reconstructs the same states pauseSession()/
     * onCaptureStarted()/the onControllerPresence callback would have pushed live. */
    fun currentStatus(): PhoneLinkAgentStatus = when {
        !streaming -> PhoneLinkAgentStatus.IDLE
        paused -> PhoneLinkAgentStatus.PAUSED
        controllerPresent -> PhoneLinkAgentStatus.ACTIVE
        else -> PhoneLinkAgentStatus.WAITING_FOR_CONTROLLER
    }

    fun startSession(token: String) {
        if (streaming) return
        streaming = true
        controllerPresent = false
        consecutiveFrameFailures = 0
        acquireWakeLock()
        onStatusChanged?.invoke(PhoneLinkAgentStatus.WAITING_FOR_CONTROLLER)
        val agentClient = PhoneLinkAgentClient(token)
        agentClient.onControllerPresence = { present ->
            android.util.Log.d("PhoneLinkAgent", "onControllerPresence: $present")
            controllerPresent = present
            onStatusChanged?.invoke(
                if (present) PhoneLinkAgentStatus.ACTIVE else PhoneLinkAgentStatus.WAITING_FOR_CONTROLLER
            )
            if (present) requestFrame()
        }
        agentClient.onCommand = { cmd -> runCatching { applyCommand(cmd) } }
        agentClient.onClosed = { android.util.Log.w("PhoneLinkAgent", "WS closed"); handler.post { stopSession() } }
        client = agentClient
        agentClient.connect()
    }

    fun stopSession() {
        if (!streaming) return
        streaming = false
        paused = false
        controllerPresent = false
        pendingFrameRequestId = null
        client?.disconnect()
        client = null
        runCatching {
            startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP_CAPTURE))
        }
        releaseWakeLock()
        cancelPausedNotification()
        onStatusChanged?.invoke(PhoneLinkAgentStatus.IDLE)
    }

    /** MediaProjection was revoked by Android (screen turned off) - the session stays logically
     * "on" (websocket, pairing token stay alive), just not sending frames until the user grants
     * a fresh capture consent. ScreenCaptureService itself is allowed to fully stop here (see
     * its onStop() for why trying to keep it foreground-alive through a projection loss crashes)
     * - what actually needs to survive the pause (the websocket connection, the wake lock, a
     * real notification offering a fast way back) all lives here instead, in a service with no
     * foreground-service-type restrictions to fight with. */
    private fun pauseSession() {
        if (!streaming) return
        paused = true
        pendingFrameRequestId = null
        postPausedNotification()
        onStatusChanged?.invoke(PhoneLinkAgentStatus.PAUSED)
    }

    /** Called directly by MainActivity right after MediaProjection consent is granted and
     * ScreenCaptureService has been started - both for the first START and for resuming after
     * a pause, so it has to restore the right status either way, not just blindly request a
     * frame (a resume needs onStatusChanged fired again since controllerPresent likely didn't
     * change - the phone/controller connection was kept alive through the pause). */
    fun onCaptureStarted() {
        android.util.Log.d("PhoneLinkAgent", "onCaptureStarted: streaming=$streaming controllerPresent=$controllerPresent")
        paused = false
        cancelPausedNotification()
        onStatusChanged?.invoke(
            if (controllerPresent) PhoneLinkAgentStatus.ACTIVE else PhoneLinkAgentStatus.WAITING_FOR_CONTROLLER
        )
        handler.postDelayed({ requestFrame() }, 500L)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneLinkAgent:session").apply {
            setReferenceCounted(false)
            acquire(TimeUnit.HOURS.toMillis(6))
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /** A plain notification, NOT tied to any foreground-service claim - this service (an
     * AccessibilityService, bound via BIND_ACCESSIBILITY_SERVICE) has no foreground-service-type
     * restriction to run into, unlike ScreenCaptureService's mediaProjection type. setOngoing
     * still applies (resists a casual swipe) without needing to fake foreground-service status
     * to get it. RESUME jumps straight to a fresh capture consent request; STOP genuinely ends
     * the whole session - never a dead end. */
    private fun postPausedNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(PAUSED_CHANNEL_ID, "Phone Link", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val resumeIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_RESUME_CAPTURE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(ScreenCaptureService.ACTION_STOP_SESSION_REQUESTED).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, PAUSED_CHANNEL_ID)
            .setContentTitle("Screen sharing paused")
            .setContentText("Screen turned off - tap to resume, or Stop to end the session")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(resumeIntent)
            .addAction(0, "RESUME", resumeIntent)
            .addAction(0, "STOP", stopIntent)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(PAUSED_NOTIFICATION_ID, notification) }
    }

    private fun cancelPausedNotification() {
        runCatching { getSystemService(NotificationManager::class.java).cancel(PAUSED_NOTIFICATION_ID) }
    }

    fun onCaptureDenied() {
        stopSession()
    }

    private fun requestFrame() {
        if (!streaming || !controllerPresent || paused) {
            android.util.Log.d("PhoneLinkAgent", "requestFrame skipped: streaming=$streaming controllerPresent=$controllerPresent paused=$paused")
            return
        }
        val requestId = System.currentTimeMillis().toString()
        pendingFrameRequestId = requestId
        runCatching {
            sendBroadcast(
                Intent(ScreenCaptureService.ACTION_REQUEST_FRAME).setPackage(packageName).putExtra("requestId", requestId)
            )
        }.onFailure { android.util.Log.e("PhoneLinkAgent", "requestFrame broadcast failed", it) }
    }

    private fun onFrameReady(intent: Intent) {
        val requestId = intent.getStringExtra("requestId")
        if (requestId == null || requestId != pendingFrameRequestId) {
            android.util.Log.d("PhoneLinkAgent", "onFrameReady: stale/mismatched requestId=$requestId pending=$pendingFrameRequestId")
            return
        }
        pendingFrameRequestId = null
        val path = intent.getStringExtra("path")
        val bytes = path?.let { runCatching { java.io.File(it).readBytes() }.getOrNull() }
        path?.let { runCatching { java.io.File(it).delete() } }
        if (bytes == null) {
            android.util.Log.w("PhoneLinkAgent", "onFrameReady: couldn't read frame bytes from $path")
            onFrameFailed()
            return
        }
        android.util.Log.d("PhoneLinkAgent", "onFrameReady: sending ${bytes.size} bytes")
        consecutiveFrameFailures = 0
        client?.sendFrame(bytes)
        handler.postDelayed({ requestFrame() }, FRAME_INTERVAL_MS)
    }

    /** A single missed frame is normal on some hardware and must not end the session - only
     * skip this one frame and try again next cycle, same interval as a successful frame. Only
     * a real sustained streak (the capture pipe is actually broken, not just a slow frame)
     * gives up and ends the session. */
    private fun onFrameFailed() {
        pendingFrameRequestId = null
        consecutiveFrameFailures++
        if (consecutiveFrameFailures >= MAX_CONSECUTIVE_FRAME_FAILURES) {
            android.util.Log.w("PhoneLinkAgent", "onFrameFailed: $consecutiveFrameFailures in a row - stopping session")
            stopSession()
            return
        }
        android.util.Log.w("PhoneLinkAgent", "onFrameFailed: $consecutiveFrameFailures consecutive, retrying")
        handler.postDelayed({ requestFrame() }, FRAME_INTERVAL_MS)
    }

    private fun applyCommand(json: JSONObject) {
        val metrics = resources.displayMetrics
        when (json.optString("type")) {
            "tap" -> tapAt(
                (json.optDouble("x") * metrics.widthPixels).toInt(),
                (json.optDouble("y") * metrics.heightPixels).toInt()
            )
            "swipe" -> swipeCoords(
                (json.optDouble("x1") * metrics.widthPixels).toInt(),
                (json.optDouble("y1") * metrics.heightPixels).toInt(),
                (json.optDouble("x2") * metrics.widthPixels).toInt(),
                (json.optDouble("y2") * metrics.heightPixels).toInt(),
                json.optLong("durationMs", 250L)
            )
            // Always replaces the whole field (ACTION_SET_TEXT semantics) - including clearing
            // it back to empty when the controller backspaces everything away.
            "key_text" -> typeText(json.optString("text"))
            "key_press" -> when (json.optString("key")) {
                "back" -> goBack()
                "home" -> goHome()
                "recents" -> openRecents()
            }
        }
    }
}
