package com.example.scifilauncher

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** "pending" (still queued or building), "ready" (downloadUrl set), or "failed". */
data class NewAppStatus(
    val status: String,
    val downloadUrl: String?,
    val appName: String?,
    val message: String?
)

data class EleneResponse(
    val intent: String,
    val reply: String?,
    val command: String?,
    val commands: List<String> = command?.let { listOf(it) } ?: emptyList(),
    // "smile" | "frown" | "curious" | "neutral" - only meaningful to XenosActivity's skeleton
    // visual, see backend's system prompt "Your face" section. Null/unrecognized == neutral.
    val emotion: String? = null
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

/** One intruder-capture entry being backed up - mirrors IntruderCaptureLog's IntruderCapture,
 * with the photo file already read and base64-encoded (or null if the file was missing/unreadable). */
data class EvacuationPhoto(
    val id: Long,
    val timestamp: Long,
    val reason: String,
    val lat: Double?,
    val lng: Double?,
    val photoBase64: String?
)

/** One point from LocationHistory being backed up. */
data class EvacuationLocationPoint(val timestamp: Long, val lat: Double, val lon: Double)

data class EvacuationResult(val uploadedPhotos: Int, val locationPoints: Int)

object EleneApiClient {

    // Not hardcoded to any specific deployment - this app is open source, and everyone who
    // builds/runs it needs to point at their OWN backend (see backend/elene/.env.example),
    // not share one person's Cloud Run instance and their API billing. Set once at startup
    // from Settings > Elene Backend URL (persisted in theme_prefs) via configureBaseUrl();
    // every call site below reads the current value through ELENE_BASE_URL. Left blank, every
    // call in this file fails gracefully (caught by its own try/catch, returns null/false) -
    // it does not crash.
    @Volatile
    private var ELENE_BASE_URL: String = ""

    fun configureBaseUrl(context: android.content.Context) {
        val prefs = context.getSharedPreferences("theme_prefs", android.content.Context.MODE_PRIVATE)
        ELENE_BASE_URL = prefs.getString("elene_backend_url", "")?.trimEnd('/') ?: ""
    }

    fun currentBaseUrl(): String = ELENE_BASE_URL

    fun setBaseUrl(context: android.content.Context, url: String) {
        val cleaned = url.trim().trimEnd('/')
        context.getSharedPreferences("theme_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("elene_backend_url", cleaned).apply()
        ELENE_BASE_URL = cleaned
    }

    // Cloud Run scales this service to zero when idle - real evidence (server-side Cloud Run
    // logs) showed a cold-start /elene/chat call taking 22.6s, well past OkHttp's 10s default
    // read timeout, so the client gave up even though the server went on to answer with a real
    // 200. Generous margin here, not just enough to cover that one measurement.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Evacuation backups can carry several intruder photos in one request (unlike the other
    // single-image calls here) - the default 10s timeouts are too tight for that, so this call
    // gets its own longer-timeout client instead of raising it for every other call too. Found
    // by real on-device failure, not assumed: raising only callTimeout wasn't enough - the
    // per-stream readTimeout (still 10s, inherited unchanged from the base client) was what
    // actually tripped first while waiting on the response, since elene-backend runs at
    // minScale=0 (a ~9.5s cold start alone, confirmed elsewhere in this project) on top of the
    // endpoint uploading each photo to GCS synchronously before it replies.
    private val longUploadClient = client.newBuilder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

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
                val emotion = json.optString("emotion", null)?.ifBlank { null }
                EleneResponse(intent = intent, reply = reply, command = command, commands = commands, emotion = emotion)
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

    /** "Create an app" proposals - deliberately a separate endpoint/label from
     * submit_update_request above, so a request for a brand-new standalone app can never be
     * mistaken by the cloud pipeline for a change to this app's own source. See
     * planner/not_started.md for why these are kept on two different scheduled routines. */
    suspend fun submitNewAppRequest(proposalId: Long, title: String, description: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val root = JSONObject().apply {
                    put("proposal_id", proposalId)
                    put("title", title)
                    put("description", description)
                }
                val body = RequestBody.create(jsonMediaType, root.toString())
                val request = Request.Builder()
                    .url("$ELENE_BASE_URL/elene/submit_new_app_request")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response -> response.isSuccessful }
            } catch (e: Exception) {
                Log.e("EleneApiClient", "submit_new_app_request call failed", e)
                false
            }
        }

    /** Polls the new-app build status for one approved proposal - see
     * report_new_app_build_result/new_app_status in backend/elene/main.py. Returns null on any
     * network failure (caller should just treat that as "still pending", not an error). */
    suspend fun fetchNewAppStatus(proposalId: Long): NewAppStatus? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$ELENE_BASE_URL/elene/new_app_status/$proposalId")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                NewAppStatus(
                    status = json.optString("status", "pending"),
                    downloadUrl = json.optString("download_url").takeIf { it.isNotBlank() },
                    appName = json.optString("app_name").takeIf { it.isNotBlank() },
                    message = json.optString("message").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            Log.e("EleneApiClient", "new_app_status call failed", e)
            null
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

    /** Phoenix Protocol (small version) - uploads just the intruder-capture photos and location
     * history to the backend's /elene/evacuate_backup. Called right before Sequence Mode's real
     * ~30-day auto-wipe deletes this data for good (only when Full-device wipe is on), and from
     * the Security screen's manual "Test evacuation backup now" button so it can be verified
     * without a real wipe. Returns null on any failure - callers should treat this as best-effort
     * and never let it block the actual wipe. */
    suspend fun evacuateBackup(
        deviceLabel: String,
        intruderPhotos: List<EvacuationPhoto>,
        locationHistory: List<EvacuationLocationPoint>
    ): EvacuationResult? = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject().apply {
                put("device_label", deviceLabel)
                put("intruder_photos", org.json.JSONArray().apply {
                    intruderPhotos.forEach { p ->
                        put(JSONObject().apply {
                            put("id", p.id)
                            put("timestamp", p.timestamp)
                            put("reason", p.reason)
                            put("lat", p.lat ?: JSONObject.NULL)
                            put("lng", p.lng ?: JSONObject.NULL)
                            put("photo_base64", p.photoBase64 ?: JSONObject.NULL)
                        })
                    }
                })
                put("location_history", org.json.JSONArray().apply {
                    locationHistory.forEach { l ->
                        put(JSONObject().apply {
                            put("timestamp", l.timestamp)
                            put("lat", l.lat)
                            put("lon", l.lon)
                        })
                    }
                })
            }
            val body = RequestBody.create(jsonMediaType, root.toString())
            val request = Request.Builder()
                .url("$ELENE_BASE_URL/elene/evacuate_backup")
                .post(body)
                .build()

            longUploadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("EleneApiClient", "evacuate_backup HTTP error: ${response.code}")
                    return@withContext null
                }
                val respBody = response.body?.string() ?: return@withContext null
                val json = JSONObject(respBody)
                if (!json.optBoolean("ok", false)) {
                    Log.e("EleneApiClient", "evacuate_backup failed: ${json.optString("error")}")
                    return@withContext null
                }
                EvacuationResult(
                    uploadedPhotos = json.optInt("uploaded_photos", 0),
                    locationPoints = json.optInt("location_points", 0)
                )
            }
        } catch (e: Exception) {
            Log.e("EleneApiClient", "evacuate_backup call failed", e)
            null
        }
    }
}