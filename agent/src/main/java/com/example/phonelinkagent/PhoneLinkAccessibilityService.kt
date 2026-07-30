package com.example.phonelinkagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

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
    }

    private val handler = Handler(Looper.getMainLooper())

    private var client: PhoneLinkAgentClient? = null
    @Volatile private var streaming = false
    private var controllerPresent = false
    private var pendingFrameRequestId: String? = null

    // A single missed frame (acquireLatestImage() returning null, a real and unremarkable
    // hiccup on some hardware - confirmed live on a Unisoc-chipset device where frame #2 of a
    // session failed while every other frame around it succeeded) must NOT kill the whole
    // session - only a genuine sustained failure should. MediaProjection actually stopping
    // (ACTION_PROJECTION_STOPPED) is a real, unambiguous hard-stop signal and always ends the
    // session immediately; ACTION_FRAME_ERROR just skips that one frame and tries again.
    private var consecutiveFrameFailures = 0

    var onStatusChanged: ((PhoneLinkAgentStatus) -> Unit)? = null

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenCaptureService.ACTION_FRAME_READY -> onFrameReady(intent)
                ScreenCaptureService.ACTION_FRAME_ERROR -> onFrameFailed()
                ScreenCaptureService.ACTION_PROJECTION_STOPPED -> {
                    android.util.Log.w("PhoneLinkAgent", "Projection stopped - ending session")
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

    fun startSession(token: String) {
        if (streaming) return
        streaming = true
        controllerPresent = false
        consecutiveFrameFailures = 0
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
        controllerPresent = false
        pendingFrameRequestId = null
        client?.disconnect()
        client = null
        runCatching {
            startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP_CAPTURE))
        }
        onStatusChanged?.invoke(PhoneLinkAgentStatus.IDLE)
    }

    /** Called directly by MainActivity right after MediaProjection consent is granted and
     * ScreenCaptureService has been started. */
    fun onCaptureStarted() {
        android.util.Log.d("PhoneLinkAgent", "onCaptureStarted: streaming=$streaming controllerPresent=$controllerPresent")
        handler.postDelayed({ requestFrame() }, 500L)
    }

    fun onCaptureDenied() {
        stopSession()
    }

    private fun requestFrame() {
        if (!streaming || !controllerPresent) {
            android.util.Log.d("PhoneLinkAgent", "requestFrame skipped: streaming=$streaming controllerPresent=$controllerPresent")
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
