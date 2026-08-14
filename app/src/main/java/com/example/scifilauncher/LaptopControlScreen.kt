package com.example.scifilauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class LaptopConnState { DISCONNECTED, CONNECTING, WAITING_FOR_AGENT, CONNECTED }

/** Where the (possibly zoomed/panned) video is actually rendered within the outer full-screen
 * box, in that box's own coordinate space - lets gesture handling on the outer box translate
 * a raw touch position into a normalized 0..1 position on the real video content. */
private fun videoRect(outerSize: IntSize, aspect: Float, scale: Float, panOffset: Offset): Rect {
    if (outerSize.width <= 0 || outerSize.height <= 0 || aspect <= 0f) {
        return Rect(0f, 0f, 1f, 1f)
    }
    val outerAspect = outerSize.width.toFloat() / outerSize.height.toFloat()
    val preW: Float
    val preH: Float
    if (outerAspect > aspect) {
        preH = outerSize.height.toFloat()
        preW = preH * aspect
    } else {
        preW = outerSize.width.toFloat()
        preH = preW / aspect
    }
    val preLeft = (outerSize.width - preW) / 2f
    val preTop = (outerSize.height - preH) / 2f
    val centerX = preLeft + preW / 2f
    val centerY = preTop + preH / 2f
    val visW = preW * scale
    val visH = preH * scale
    val visLeft = centerX - visW / 2f + panOffset.x
    val visTop = centerY - visH / 2f + panOffset.y
    return Rect(visLeft, visTop, visLeft + visW, visTop + visH)
}

/** Thin OkHttp WebSocket wrapper for the phone side of the laptop relay - pure network
 * plumbing, no Compose state of its own. The screen below owns state and lifecycle.
 *
 * Auto-reconnects on drop with capped exponential backoff (2s -> 30s, mirrors the laptop
 * agent's own reconnect loop) - the relay is over the public internet, not local WiFi, so a
 * dropped connection is expected to happen (network handoff, backend instance recycling,
 * walking out of signal) and should recover on its own rather than leaving the screen stuck
 * on "Disconnected" until the user manually backs out and back in. `disconnect()` cancels
 * any pending reconnect so leaving the screen doesn't leave a retry loop running behind it. */
class LaptopControlClient(private val token: String) {
    private var ws: WebSocket? = null
    var onState: ((LaptopConnState) -> Unit)? = null
    var onFrame: ((Bitmap) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    private var userDisconnected = false

    fun connect() {
        userDisconnected = false
        onState?.invoke(LaptopConnState.CONNECTING)
        val request = Request.Builder()
            .url("wss://elene-backend-717899371194.us-central1.run.app/laptop/ws/phone/$token")
            .build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                onState?.invoke(LaptopConnState.WAITING_FOR_AGENT)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    when (JSONObject(text).optString("type")) {
                        "agent_connected" -> onState?.invoke(LaptopConnState.CONNECTED)
                        "agent_disconnected", "agent_offline" -> onState?.invoke(LaptopConnState.WAITING_FOR_AGENT)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val bytesArray = bytes.toByteArray()
                val bmp = runCatching { BitmapFactory.decodeByteArray(bytesArray, 0, bytesArray.size) }.getOrNull()
                if (bmp != null) onFrame?.invoke(bmp)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onState?.invoke(LaptopConnState.DISCONNECTED)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onState?.invoke(LaptopConnState.DISCONNECTED)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (userDisconnected) return
        reconnectHandler.postDelayed({
            if (!userDisconnected) connect()
        }, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    fun disconnect() {
        userDisconnected = true
        reconnectHandler.removeCallbacksAndMessages(null)
        runCatching { ws?.close(1000, null) }
        ws = null
    }

    companion object {
        private const val INITIAL_RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
    }

    private fun send(json: JSONObject) {
        runCatching { ws?.send(json.toString()) }
    }

    fun sendMouseMove(x: Float, y: Float) =
        send(JSONObject().put("type", "mouse_move").put("x", x).put("y", y))

    fun sendMouseClick(x: Float, y: Float, button: String = "left") =
        send(JSONObject().put("type", "mouse_click").put("x", x).put("y", y).put("button", button))

    fun sendScroll(dy: Int) =
        send(JSONObject().put("type", "scroll").put("dy", dy))

    fun sendKeyText(text: String) =
        send(JSONObject().put("type", "key_text").put("text", text))

    fun sendKeyPress(key: String) =
        send(JSONObject().put("type", "key_press").put("key", key))
}

@Composable
fun LaptopControlScreen(
    themeColor: Color,
    isDark: Boolean,
    savedToken: String?,
    onSaveToken: (String) -> Unit,
    onForgetToken: () -> Unit,
    onScanQr: (onResult: (String) -> Unit) -> Unit,
    onBack: () -> Unit
) {
    // Forcing a real Activity orientation change was tried here and reverted - this app is
    // the phone's Device Owner home launcher, and rotating it had device-wide side effects
    // (status bar / system UI elements misplacing). The zoom controls on the live view are
    // how you get a closer look instead; the video itself just stays letterboxed in portrait.
    if (savedToken.isNullOrBlank()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDark) Color(0xFF060911) else Color(0xFFEFEFEF))
                .systemBarsPadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp, top = 40.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "◀",
                    color = themeColor,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp)
                )
                Text(
                    text = "LINK TO LAPTOP",
                    color = themeColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(16.dp))
            PairingSetup(themeColor = themeColor, onSaveToken = onSaveToken, onScanQr = onScanQr)
        }
    } else {
        LiveControl(themeColor = themeColor, token = savedToken, onBack = onBack, onForgetToken = onForgetToken)
    }
}

@Composable
private fun PairingSetup(
    themeColor: Color,
    onSaveToken: (String) -> Unit,
    onScanQr: (onResult: (String) -> Unit) -> Unit
) {
    var input by remember { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("PC", color = themeColor, fontSize = 40.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Not paired yet",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Run agent.py on your laptop (see laptop_agent/ in the project). It " +
                    "prints a pairing code and a QR code - enter or scan it below. Keep it " +
                    "private, it's the only thing gating who can view/control that laptop.",
            color = Color.Gray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.trim() },
            label = { Text("Pairing code", fontFamily = FontFamily.Monospace) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrect = false
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onScanQr { scanned -> input = scanned.trim() } },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("SCAN QR INSTEAD", color = Color.White, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { if (input.length >= 16) onSaveToken(input) },
            enabled = input.length >= 16,
            colors = ButtonDefaults.buttonColors(containerColor = themeColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("PAIR", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LiveControl(
    themeColor: Color,
    token: String,
    onBack: () -> Unit,
    onForgetToken: () -> Unit
) {
    val client = remember(token) { LaptopControlClient(token) }
    var connState by remember { mutableStateOf(LaptopConnState.CONNECTING) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var frameAspect by remember { mutableStateOf(16f / 9f) }
    var outerSize by remember { mutableStateOf(IntSize(1, 1)) }
    var typed by remember { mutableStateOf(TextFieldValue("")) }

    // Pinch-to-zoom/pan so small text/icons on the laptop can actually be read clearly -
    // two fingers control the view (zoom/pan), one finger still controls the remote pointer
    // (tap/drag/hold), so the two never fight over the same gesture.
    var scale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Where we last told the remote pointer to go, normalized 0..1 within the video - there's
    // no real cursor visible in the raw screen capture, so this synthetic marker is the only
    // way to see where you're about to click/type.
    var pointerMarker by remember { mutableStateOf<Offset?>(null) }

    DisposableEffect(token) {
        client.onState = { connState = it }
        client.onFrame = { bmp ->
            frame = bmp
            if (bmp.width > 0 && bmp.height > 0) frameAspect = bmp.width.toFloat() / bmp.height.toFloat()
        }
        client.connect()
        onDispose { client.disconnect() }
    }

    val statusText = when (connState) {
        LaptopConnState.DISCONNECTED -> "Disconnected"
        LaptopConnState.CONNECTING -> "Connecting..."
        LaptopConnState.WAITING_FOR_AGENT -> "Waiting for laptop agent..."
        LaptopConnState.CONNECTED -> "Connected"
    }
    val statusColor = if (connState == LaptopConnState.CONNECTED) themeColor else Color.Gray

    // Full-bleed: the black backdrop fills the entire screen edge to edge, the video sits
    // centered inside it sized to its own real aspect ratio, and the back/status/keyboard
    // chrome floats as translucent overlays instead of taking up dedicated, screen-shrinking
    // rows. Gesture detection lives on this OUTER box (not the video box itself) because the
    // video is letterboxed - narrower than the full screen in portrait - and a real two-finger
    // pinch very easily has one finger land outside that narrow strip; a gesture area scoped
    // to just the video box would silently miss it.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { outerSize = it }
            .pointerInput(token) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    var multiTouch = false
                    var dragged = false
                    var rightClicked = false

                    fun normalizedPos(pos: Offset): Offset {
                        val rect = videoRect(outerSize, frameAspect, scale, panOffset)
                        val nx = ((pos.x - rect.left) / rect.width).coerceIn(0f, 1f)
                        val ny = ((pos.y - rect.top) / rect.height).coerceIn(0f, 1f)
                        return Offset(nx, ny)
                    }

                    pointerMarker = normalizedPos(down.position)

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }

                        if (pressed.size >= 2) {
                            multiTouch = true
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val rect = videoRect(outerSize, frameAspect, scale, panOffset)
                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                            val maxOffsetX = (rect.width * (newScale - 1f) / 2f).coerceAtLeast(0f)
                            val maxOffsetY = (rect.height * (newScale - 1f) / 2f).coerceAtLeast(0f)
                            scale = newScale
                            panOffset = Offset(
                                (panOffset.x + panChange.x).coerceIn(-maxOffsetX, maxOffsetX),
                                (panOffset.y + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                            )
                            event.changes.forEach { it.consume() }
                        } else if (!multiTouch && pressed.isNotEmpty()) {
                            val change = pressed.first()
                            val dist = hypot(
                                (change.position.x - down.position.x).toDouble(),
                                (change.position.y - down.position.y).toDouble()
                            )
                            val n = normalizedPos(change.position)
                            if (dist > viewConfiguration.touchSlop) {
                                dragged = true
                                pointerMarker = n
                                client.sendMouseMove(n.x, n.y)
                                change.consume()
                            } else if (!dragged && !rightClicked &&
                                System.currentTimeMillis() - downTime > 500L
                            ) {
                                rightClicked = true
                                pointerMarker = n
                                client.sendMouseClick(n.x, n.y, "right")
                            }
                        }

                        if (event.changes.none { it.pressed }) break
                    }

                    if (!multiTouch && !dragged && !rightClicked) {
                        val n = normalizedPos(down.position)
                        pointerMarker = n
                        client.sendMouseClick(n.x, n.y, "left")
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .aspectRatio(frameAspect)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = panOffset.x
                    translationY = panOffset.y
                },
            contentAlignment = Alignment.Center
        ) {
            val currentFrame = frame
            if (currentFrame != null) {
                Image(
                    bitmap = currentFrame.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("No image yet", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }

            pointerMarker?.let { marker ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = marker.x * size.width
                    val cy = marker.y * size.height
                    val armLength = 6.dp.toPx()
                    val gap = 2.dp.toPx()
                    drawCircle(color = Color.Black, radius = 3.dp.toPx(), center = Offset(cx, cy), alpha = 0.5f)
                    drawCircle(color = themeColor, radius = 2.dp.toPx(), center = Offset(cx, cy))
                    listOf(
                        Offset(cx - armLength - gap, cy) to Offset(cx - gap, cy),
                        Offset(cx + gap, cy) to Offset(cx + armLength + gap, cy),
                        Offset(cx, cy - armLength - gap) to Offset(cx, cy - gap),
                        Offset(cx, cy + gap) to Offset(cx, cy + armLength + gap)
                    ).forEach { (start, end) ->
                        drawLine(color = themeColor, start = start, end = end, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .systemBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "◀",
                color = themeColor,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 10.dp)
            )
            Text(statusText, color = statusColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text(
                text = "UNPAIR",
                color = Color.Gray,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable(onClick = onForgetToken)
            )
        }

        Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // pyautogui.scroll(): positive = scroll up, negative = scroll down.
                    // A magnitude of 3 was too small to even notice - this is a full page-ish jump.
                    listOf("▲" to 40, "▼" to -40).forEach { (glyph, dy) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Gray.copy(alpha = 0.2f))
                                .clickable { client.sendScroll(dy) }
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(glyph, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    listOf("ENTER" to "enter", "TAB" to "tab", "ESC" to "esc", "⌫" to "backspace").forEach { (label, key) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Gray.copy(alpha = 0.2f))
                                .clickable { client.sendKeyPress(key) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(label, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (scale > 1.01f) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(themeColor.copy(alpha = 0.3f))
                                .clickable {
                                    scale = 1f
                                    panOffset = Offset.Zero
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("⊙ RESET ZOOM", color = themeColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { new ->
                        val old = typed.text
                        if (new.text.length > old.length && new.text.startsWith(old)) {
                            client.sendKeyText(new.text.substring(old.length))
                        } else if (new.text.length < old.length && old.startsWith(new.text)) {
                            repeat(old.length - new.text.length) { client.sendKeyPress("backspace") }
                        } else if (new.text != old) {
                            // Non-trivial edit (paste, autocorrect, cursor move+type) - just
                            // resync by resending the full new text rather than guessing a diff.
                            repeat(old.length) { client.sendKeyPress("backspace") }
                            if (new.text.isNotEmpty()) client.sendKeyText(new.text)
                        }
                        typed = new
                    },
                    singleLine = true,
                    label = { Text("Type to send keystrokes", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "1 finger: tap=click, hold=right-click, drag=move pointer. " +
                            "2 fingers: pinch=zoom, drag=pan.",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
        }
    }
}
