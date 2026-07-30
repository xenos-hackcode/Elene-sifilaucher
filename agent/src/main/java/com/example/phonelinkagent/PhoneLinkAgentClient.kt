package com.example.phonelinkagent

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Phone side of the Link to Phone relay's agent role - the phone being controlled. Pure network
 * plumbing, mirrors the main SciFiLauncher app's LaptopControlClient shape but reversed: sends
 * screen frames as bytes, receives commands as JSON text. */
class PhoneLinkAgentClient(private val token: String) {
    private var ws: WebSocket? = null
    var onControllerPresence: ((Boolean) -> Unit)? = null
    var onCommand: ((JSONObject) -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun connect() {
        val url = "wss://elene-backend-717899371194.us-central1.run.app/phone/ws/agent/$token"
        android.util.Log.d("PhoneLinkAgentClient", "connecting: $url")
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                android.util.Log.d("PhoneLinkAgentClient", "WS open")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                android.util.Log.d("PhoneLinkAgentClient", "WS text: $text")
                runCatching {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "controller_connected" -> onControllerPresence?.invoke(true)
                        "controller_disconnected", "controller_offline" -> onControllerPresence?.invoke(false)
                        "tap", "swipe", "key_text", "key_press" -> onCommand?.invoke(json)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.w("PhoneLinkAgentClient", "WS closed: code=$code reason=$reason")
                onClosed?.invoke()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.e("PhoneLinkAgentClient", "WS failure: response=$response", t)
                onClosed?.invoke()
            }
        })
    }

    fun sendFrame(bytes: ByteArray) {
        val sent = runCatching { ws?.send(ByteString.of(*bytes)) }.getOrNull()
        if (sent != true) {
            android.util.Log.w("PhoneLinkAgentClient", "sendFrame failed to enqueue (ws=${ws != null}, result=$sent)")
        }
    }

    fun disconnect() {
        runCatching { ws?.close(1000, null) }
        ws = null
    }
}
