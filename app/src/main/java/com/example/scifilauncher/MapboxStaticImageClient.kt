package com.example.scifilauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Mapbox Static Images API client - Stage A of the Globe's satellite DETAIL panel (see
 * planner/combination/combination.md for the staged plan and Codex's agreed constraints). One
 * plain HTTP GET per explicit user tap - no tile math, no map SDK, no custom caching (Mapbox
 * bills per request and documents its own cache behavior separately; not layering a second cache
 * on top without checking that ToS in more depth first, per Codex's constraint #7). Token comes
 * from BuildConfig.MAPBOX_TOKEN, itself read from local.properties at build time - never
 * hardcoded here (this repo is public on GitHub). */
object MapboxStaticImageClient {
    private val client = OkHttpClient()

    sealed class Result {
        data class Success(val bitmap: Bitmap) : Result()
        object NoToken : Result()
        object InvalidToken : Result()
        object RateLimited : Result()
        data class NetworkError(val message: String) : Result()
    }

    // z18 is real house/building-resolution per Mapbox's own zoom-level docs, kept as the default
    // starting point; DEFAULT_ZOOM/MIN_ZOOM/MAX_ZOOM back the DETAIL panel's Stage A2 zoom chips
    // (combination.md) - each chip tap is still exactly one bounded Static Images request, never
    // a continuous tile fetch. 640x640 requested at @2x (retina) returns 1280x1280 actual pixels,
    // comfortably inside the Static Images API's documented 1280px request-dimension limit.
    const val DEFAULT_ZOOM = 18
    const val MIN_ZOOM = 12
    const val MAX_ZOOM = 20
    private const val REQUEST_SIZE_PX = 640

    suspend fun fetchDetailImage(lat: Double, lng: Double, zoom: Int = DEFAULT_ZOOM): Result = withContext(Dispatchers.IO) {
        val token = BuildConfig.MAPBOX_TOKEN
        if (token.isBlank()) return@withContext Result.NoToken
        val clampedZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

        val url = "https://api.mapbox.com/styles/v1/mapbox/satellite-v9/static/" +
            "$lng,$lat,$clampedZoom/${REQUEST_SIZE_PX}x${REQUEST_SIZE_PX}@2x?access_token=$token"
        val request = Request.Builder().url(url).build()

        runCatching {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val bytes = response.body?.bytes()
                        val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        if (bitmap != null) Result.Success(bitmap) else Result.NetworkError("Bad image data")
                    }
                    401, 403 -> Result.InvalidToken
                    429 -> Result.RateLimited
                    else -> Result.NetworkError("HTTP ${response.code}")
                }
            }
        }.getOrElse { Result.NetworkError(it.message ?: "Network error") }
    }
}
