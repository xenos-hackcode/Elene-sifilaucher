package com.example.scifilauncher

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody
import org.json.JSONObject

data class EleneResponse(
    val intent: String,
    val reply: String?,
    val command: String?,
    val commands: List<String> = command?.let { listOf(it) } ?: emptyList()
)

object EleneApiClient {

    private const val ELENE_BASE_URL = "https://elene-backend-717899371194.us-central1.run.app"

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun sendText(
        userId: String,
        text: String,
        context: Map<String, Any?> = emptyMap()
    ): EleneResponse? = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject().apply {
                put("user_id", userId)
                put("text", text)

                val ctx = JSONObject()
                context.forEach { (k, v) ->
                    when (v) {
                        null -> ctx.put(k, JSONObject.NULL)
                        is Boolean, is Number, is String -> ctx.put(k, v)
                        else -> ctx.put(k, v.toString())
                    }
                }
                put("context", ctx)
            }

            val body = RequestBody.create(jsonMediaType, root.toString())
            val request = Request.Builder()
                .url("$ELENE_BASE_URL/elene/chat")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("EleneApiClient", "HTTP error: ${response.code}")
                    return@withContext null
                }
                val respBody = response.body?.string() ?: return@withContext null
                val json = JSONObject(respBody)
                val intent = json.optString("intent", "")
                val reply = json.optString("reply", null)
                val command = json.optString("command", null)?.ifBlank { null }
                val commandsArray = json.optJSONArray("commands")
                val commands = if (commandsArray != null) {
                    (0 until commandsArray.length()).mapNotNull { i -> commandsArray.optString(i, null)?.ifBlank { null } }
                } else {
                    command?.let { listOf(it) } ?: emptyList()
                }
                EleneResponse(intent = intent, reply = reply, command = command, commands = commands)
            }
        } catch (e: Exception) {
            Log.e("EleneApiClient", "API call failed", e)
            null
        }
    }

    /** Sends a Sequence Mode alert email via the backend's /alert/email endpoint. */
    suspend fun sendAlertEmail(to: List<String>, message: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject().apply {
                put("to", org.json.JSONArray(to))
                put("message", message)
            }
            val body = RequestBody.create(jsonMediaType, root.toString())
            val request = Request.Builder()
                .url("$ELENE_BASE_URL/alert/email")
                .post(body)
                .build()

            client.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            Log.e("EleneApiClient", "Alert email call failed", e)
            false
        }
    }

    /** Fetches ElevenLabs-synthesized speech audio (mp3 bytes) for [text], or null if
     * the backend isn't configured for TTS / the call fails - caller should fall back
     * to the on-device system voice in that case. */
    suspend fun fetchTtsAudio(text: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject().apply { put("text", text) }
            val body = RequestBody.create(jsonMediaType, root.toString())
            val request = Request.Builder()
                .url("$ELENE_BASE_URL/tts")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("EleneApiClient", "TTS HTTP error: ${response.code}")
                    return@withContext null
                }
                response.body?.bytes()
            }
        } catch (e: Exception) {
            Log.e("EleneApiClient", "TTS fetch failed", e)
            null
        }
    }
}