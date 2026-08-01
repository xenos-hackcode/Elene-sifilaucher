package com.example.scifilauncher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Real current weather via Open-Meteo (api.open-meteo.com) - free, no API key/signup needed,
 * same "no-account, no-cost" preference this project already leans on elsewhere (Cloudflare
 * 1.1.1.1 for the tracker-blocking VPN's upstream DNS). Uses the phone's own last-known
 * location (the same one Sequence Mode/Intruder Attempts already read), so this needs no new
 * permission. */
object WeatherClient {
    private val client = OkHttpClient()
    private var cached: Pair<Long, String>? = null
    private val CACHE_TTL_MILLIS = 30 * 60_000L // real weather doesn't change fast enough to
    // justify a live fetch on every single chat turn

    suspend fun currentWeatherDescription(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        val cachedValue = cached
        if (cachedValue != null && System.currentTimeMillis() - cachedValue.first < CACHE_TTL_MILLIS) {
            return@withContext cachedValue.second
        }

        val fetched: String? = runCatching { fetchFromNetwork(lat, lng) }.getOrNull()
        if (fetched != null) cached = System.currentTimeMillis() to fetched
        fetched
    }

    private fun fetchFromNetwork(lat: Double, lng: Double): String? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng" +
            "&current=temperature_2m,weather_code,relative_humidity_2m,wind_speed_10m" +
            "&temperature_unit=celsius&wind_speed_unit=kmh"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return null

        val current = JSONObject(body).getJSONObject("current")
        val temp = current.getDouble("temperature_2m").toInt()
        val condition = weatherCodeToDescription(current.getInt("weather_code"))
        val humidity = current.optDouble("relative_humidity_2m", Double.NaN)
        val wind = current.optDouble("wind_speed_10m", Double.NaN)
        return buildString {
            append("$condition, ${temp}°C")
            if (!humidity.isNaN()) append(", ${humidity.toInt()}% humidity")
            if (!wind.isNaN()) append(", wind ${wind.toInt()} km/h")
        }
    }

    // WMO weather codes, per Open-Meteo's own documented mapping.
    private fun weatherCodeToDescription(code: Int): String = when (code) {
        0 -> "Clear sky"
        1, 2, 3 -> "Partly cloudy"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown conditions"
    }
}
