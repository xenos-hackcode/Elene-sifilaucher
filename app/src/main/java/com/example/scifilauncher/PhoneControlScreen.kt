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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
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

enum class PhoneControlConnState { DISCONNECTED, CONNECTING, WAITING_FOR_AGENT, CONNECTED }

/** Controller side of the phone-link relay - pure network plumbing, mirrors LaptopControlClient
 * but reversed: sends tap/swipe/key commands as JSON text, receives screen frames as bytes.
 *
 * Auto-reconnects on drop with capped exponential backoff (2s -> 30s) - same reasoning as
 * LaptopControlClient: the relay is over the public internet, so a drop is expected to happen
 * occasionally and should recover on its own instead of leaving the screen stuck on
 * "Disconnected". `disconnect()` cancels any pending reconnect on the way out. */
class PhoneControlClient(private val token: String) {
    private var ws: WebSocket? = null
    var onState: ((PhoneControlConnState) -> Unit)? = null
    var onFrame: ((Bitmap) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    private var userDisconnected = false

    fun connect() {
        userDisconnected = false
        onState?.invoke(PhoneControlConnState.CONNECTING)
        val request = Request.Builder()
            .url("wss://elene-backend-717899371194.us-central1.run.app/phone/ws/controller/$token")
            .build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                onState?.invoke(PhoneControlConnState.WAITING_FOR_AGENT)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (BuildConfig.DEBUG) android.util.Log.d("PhoneControlClient", "WS text: $text")
                runCatching {
                    when (JSONObject(text).optString("type")) {
                        "agent_connected" -> onState?.invoke(PhoneControlConnState.CONNECTED)
                        "agent_disconnected", "agent_offline" -> onState?.invoke(PhoneControlConnState.WAITING_FOR_AGENT)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val bytesArray = bytes.toByteArray()
                val bmp = runCatching { BitmapFactory.decodeByteArray(bytesArray, 0, bytesArray.size) }.getOrNull()
                if (BuildConfig.DEBUG) android.util.Log.d("PhoneControlClient", "WS bytes: ${bytesArray.size}, decoded=${bmp != null}")
                if (bmp != null) onFrame?.invoke(bmp)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.w("PhoneControlClient", "WS closed: code=$code reason=$reason")
                onState?.invoke(PhoneControlConnState.DISCONNECTED)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.e("PhoneControlClient", "WS failure: response=$response", t)
                onState?.invoke(PhoneControlConnState.DISCONNECTED)
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

    fun sendTap(x: Float, y: Float) =
        send(JSONObject().put("type", "tap").put("x", x).put("y", y))

    fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) =
        send(
            JSONObject().put("type", "swipe")
                .put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2).put("durationMs", durationMs)
        )

    fun sendKeyText(text: String) =
        send(JSONObject().put("type", "key_text").put("text", text))

    fun sendKeyPress(key: String) =
        send(JSONObject().put("type", "key_press").put("key", key))
}

/** Where the (possibly zoomed/panned) video is actually rendered within the outer full-screen
 * box - same helper as LaptopControlScreen's videoRect, duplicated rather than shared since
 * these two screens are otherwise fully independent and small enough not to warrant a shared
 * utility file just for this. */
private fun controlVideoRect(outerSize: IntSize, aspect: Float, scale: Float, panOffset: Offset): Rect {
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

@Composable
fun PhoneControlScreen(
    themeColor: Color,
    isDark: Boolean,
    savedToken: String?,
    onSaveToken: (String) -> Unit,
    onForgetToken: () -> Unit,
    onScanQr: (onResult: (String) -> Unit) -> Unit,
    onBack: () -> Unit
) {
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
                    text = "LINK TO PHONE",
                    color = themeColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(16.dp))
            PhonePairingSetup(themeColor = themeColor, onSaveToken = onSaveToken, onScanQr = onScanQr)
        }
    } else {
        PhoneLiveControl(themeColor = themeColor, token = savedToken, onBack = onBack, onForgetToken = onForgetToken)
    }
}

@Composable
private fun PhonePairingSetup(
    themeColor: Color,
    onSaveToken: (String) -> Unit,
    onScanQr: (onResult: (String) -> Unit) -> Unit
) {
    var input by remember { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        androidx.compose.material3.Text("📱", color = themeColor, fontSize = 40.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.Text(
            text = "Not linked yet",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.Text(
            text = "On the other phone, open Settings and set up \"Linked device access\" - it " +
                "will show a pairing code and QR after you complete its disclosure and " +
                "permission steps. Enter or scan it below.",
            color = Color.Gray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.trim() },
            label = { androidx.compose.material3.Text("Pairing code", fontFamily = FontFamily.Monospace) },
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
            androidx.compose.material3.Text("SCAN QR INSTEAD", color = Color.White, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { if (input.length >= 16) onSaveToken(input) },
            enabled = input.length >= 16,
            colors = ButtonDefaults.buttonColors(containerColor = themeColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            androidx.compose.material3.Text("LINK", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PhoneLiveControl(
    themeColor: Color,
    token: String,
    onBack: () -> Unit,
    onForgetToken: () -> Unit
) {
    val client = remember(token) { PhoneControlClient(token) }
    var connState by remember { mutableStateOf(PhoneControlConnState.CONNECTING) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var frameAspect by remember { mutableStateOf(9f / 19.5f) }
    var outerSize by remember { mutableStateOf(IntSize(1, 1)) }
    var typed by remember { mutableStateOf(TextFieldValue("")) }

    var scale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
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
        PhoneControlConnState.DISCONNECTED -> "Disconnected"
        PhoneControlConnState.CONNECTING -> "Connecting..."
        PhoneControlConnState.WAITING_FOR_AGENT -> "Waiting for the other phone..."
        PhoneControlConnState.CONNECTED -> "Connected"
    }
    val statusColor = if (connState == PhoneControlConnState.CONNECTED) themeColor else Color.Gray

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { outerSize = it }
            .pointerInput(token) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var multiTouch = false
                    var dragged = false
                    var dragStart: Offset? = null

                    fun normalizedPos(pos: Offset): Offset {
                        val rect = controlVideoRect(outerSize, frameAspect, scale, panOffset)
                        val nx = ((pos.x - rect.left) / rect.width).coerceIn(0f, 1f)
                        val ny = ((pos.y - rect.top) / rect.height).coerceIn(0f, 1f)
                        return Offset(nx, ny)
                    }

                    pointerMarker = normalizedPos(down.position)
                    dragStart = pointerMarker

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }

                        if (pressed.size >= 2) {
                            multiTouch = true
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val rect = controlVideoRect(outerSize, frameAspect, scale, panOffset)
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
                                change.consume()
                            }
                        }

                        if (event.changes.none { it.pressed }) break
                    }

                    if (!multiTouch) {
                        val end = pointerMarker ?: normalizedPos(down.position)
                        val start = dragStart ?: end
                        if (dragged) {
                            client.sendSwipe(start.x, start.y, end.x, end.y, 250L)
                        } else {
                            client.sendTap(end.x, end.y)
                        }
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
                androidx.compose.material3.Text("No image yet", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }

            pointerMarker?.let { marker ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = marker.x * size.width
                    val cy = marker.y * size.height
                    drawCircle(color = Color.Black, radius = 5.dp.toPx(), center = Offset(cx, cy), alpha = 0.4f)
                    drawCircle(color = themeColor, radius = 3.dp.toPx(), center = Offset(cx, cy))
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
            androidx.compose.material3.Text(
                text = "◀",
                color = themeColor,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 10.dp)
            )
            androidx.compose.material3.Text(statusText, color = statusColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.Text(
                text = "UNLINK",
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
                listOf("◀ BACK" to "back", "⌂ HOME" to "home", "▢ RECENTS" to "recents").forEach { (label, key) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Gray.copy(alpha = 0.2f))
                            .clickable { client.sendKeyPress(key) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        androidx.compose.material3.Text(label, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                if (scale > 1.01f) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(themeColor.copy(alpha = 0.3f))
                            .clickable { scale = 1f; panOffset = Offset.Zero }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        androidx.compose.material3.Text("⊙ RESET ZOOM", color = themeColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { new ->
                    // Always send the full current text, not a diff - the remote side applies
                    // this via Accessibility's ACTION_SET_TEXT, which replaces the whole field's
                    // content rather than inserting/appending at a cursor position. Sending only
                    // newly-typed characters (as if the remote could "append" them) silently
                    // wiped out everything typed before it on every keystroke - confirmed live.
                    if (new.text != typed.text) client.sendKeyText(new.text)
                    typed = new
                },
                singleLine = true,
                label = { androidx.compose.material3.Text("Type to send text", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.Text(
                text = "1 finger: tap=tap, drag=swipe. 2 fingers: pinch=zoom, drag=pan.",
                color = Color.Gray,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
