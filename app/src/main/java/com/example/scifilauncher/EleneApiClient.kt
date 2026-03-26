package com.example.scifilauncher

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject

data class EleneResponse(
    val intent: String,
    val reply: String?
)

object EleneApiClient {

    // TODO: change to your real endpoint when ready
    private const val BASE_URL = "http://192.168.0.13:8000/elene/chat"

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun sendTextSync(
        userId: String,
        text: String,
        context: Map<String, Any?> = emptyMap()
    ): EleneResponse? {
        return try {
            val root = JSONObject()
            root.put("user_id", userId)
            root.put("text", text)

            val ctx = JSONObject()
            context.forEach { (k, v) ->
                when (v) {
                    null -> ctx.put(k, JSONObject.NULL)
                    is Boolean, is Number, is String -> ctx.put(k, v)
                    else -> ctx.put(k, v.toString())
                }
            }
            root.put("context", ctx)

            val body = RequestBody.create(jsonMediaType, root.toString())
            val request = Request.Builder()
                .url(BASE_URL)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("EleneApiClient", "HTTP error: ${response.code}")
                    return null
                }
                val respBody = response.body?.string() ?: return null
                val json = JSONObject(respBody)
                val intent = json.optString("intent", "")
                val reply = json.optString("reply", null)
                EleneResponse(intent = intent, reply = reply)
            }
        } catch (e: Exception) {
            Log.e("EleneApiClient", "API call failed", e)
            null
        }
    }
}
