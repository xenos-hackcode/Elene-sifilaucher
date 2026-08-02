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

/** [x]/[y] are a single tap target, [x2]/[y2] a swipe's end point (both null for a plain tap).
 * Coordinates are in the SAME downscaled frame the image was sent in, not full-screen pixels -
 * callers must scale up using the frame/full width and height ScreenPerceptionService reports. */
data class GameMoveDecision(
    val action: String, // "tap" | "swipe" | "wait" | "give_up"
    val x: Int?,
    val y: Int?,
    val x2: Int?,
    val y2: Int?,
    val reasoning: String,
    val gameOver: Boolean
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

    /** Called right after a real fingerprint approval of an update proposal (see
     * UpdateProposalLog / MainActivity's onApprove hooks) - bridges the approval to the
     * backend, which files a real GitHub issue a scheduled cloud agent polls for. Silent
     * best-effort: if this fails, the proposal is still marked APPROVED on-device (the
     * fingerprint gate already did its job), it just won't reach the automated build pipeline
     * until someone notices and re-triggers it another way. */
    suspend fun submitUpdateRequest(proposalId: Long, title: String, description: String, category: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val root = JSONObject().apply {
                    put("proposal_id", proposalId)
                    put("title", title)
                    put("description", description)
                    put("category", category)
                }
                val body = RequestBody.create(jsonMediaType, root.toString())
                val request = Request.Builder()
                    .url("$ELENE_BASE_URL/elene/submit_update_request")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response -> response.isSuccessful }
            } catch (e: Exception) {
                Log.e("EleneApiClient", "submit_update_request call failed", e)
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

    /** One-shot "what's on my screen" - plain-text reply, no JSON forcing needed since it's
     * spoken straight back via TTS. */
    suspend fun describeScreen(imageBytes: ByteArray, question: String?): String? = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject().apply {
                put("image_base64", android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP))
                put("question", question ?: JSONObject.NULL)
            }
            val body = RequestBody.create(jsonMediaType, root.toString())
            val request = Request.Builder()
                .url("$ELENE_BASE_URL/elene/describe_screen")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("EleneApiClient", "describe_screen HTTP error: ${response.code}")
                    return@withContext null
                }
                val respBody = response.body?.string() ?: return@withContext null
                JSONObject(respBody).optString("description", "").ifBlank { null }
            }
        } catch (e: Exception) {
            Log.e("EleneApiClient", "describe_screen call failed", e)
            null
        }
    }

    /** Deliberately separate from sendText/describeScreen - a much more constrained response
     * format for the turn-based game auto-play loop, stateless on the server (recentMoves
     * carries continuity instead of a server-side session), kept fast and cheap per call. */
    suspend fun decideGameMove(
        imageBytes: ByteArray,
        gameHint: String?,
        recentMoves: List<String>
    ): GameMoveDecision? = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject().apply {
                put("image_base64", android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP))
                put("game_hint", gameHint ?: JSONObject.NULL)
                put("recent_moves", org.json.JSONArray(recentMoves))
            }
            val body = RequestBody.create(jsonMediaType, root.toString())
            val request = Request.Builder()
                .url("$ELENE_BASE_URL/elene/game_move")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("EleneApiClient", "game_move HTTP error: ${response.code}")
                    return@withContext null
                }
                val respBody = response.body?.string() ?: return@withContext null
                val json = JSONObject(respBody)
                GameMoveDecision(
                    action = json.optString("action", "wait"),
                    x = json.optInt("x", -1).takeIf { it >= 0 },
                    y = json.optInt("y", -1).takeIf { it >= 0 },
                    x2 = json.optInt("x2", -1).takeIf { it >= 0 },
                    y2 = json.optInt("y2", -1).takeIf { it >= 0 },
                    reasoning = json.optString("reasoning", ""),
                    gameOver = json.optBoolean("game_over", false)
                )
            }
        } catch (e: Exception) {
            Log.e("EleneApiClient", "game_move call failed", e)
            null
        }
    }
}