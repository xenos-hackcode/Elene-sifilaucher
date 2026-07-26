package com.example.scifilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LocationEntry(val timestamp: Long, val lat: Double, val lon: Double)

private const val LOC_PREFS = "location_history_prefs"
private const val LOC_KEY = "entries"
private const val LOC_MAX = 500

/** Background location log - not a one-off "where is it right now" check like Sequence Mode's
 * trigger snapshot, a running history captured periodically while enabled, the way you'd want
 * for actually reconstructing where a device has been rather than just where it is right now. */
object LocationHistory {
    @Synchronized
    fun record(context: Context, entry: LocationEntry) {
        val list = loadAll(context).toMutableList()
        list.add(0, entry)
        while (list.size > LOC_MAX) list.removeAt(list.lastIndex)
        saveAll(context, list)
    }

    fun loadAll(context: Context): List<LocationEntry> {
        val raw = context.getSharedPreferences(LOC_PREFS, Context.MODE_PRIVATE).getString(LOC_KEY, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LocationEntry(o.getLong("timestamp"), o.getDouble("lat"), o.getDouble("lon"))
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        context.getSharedPreferences(LOC_PREFS, Context.MODE_PRIVATE).edit().remove(LOC_KEY).apply()
    }

    private fun saveAll(context: Context, list: List<LocationEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
            o.put("timestamp", e.timestamp)
            o.put("lat", e.lat)
            o.put("lon", e.lon)
            arr.put(o)
        }
        context.getSharedPreferences(LOC_PREFS, Context.MODE_PRIVATE).edit().putString(LOC_KEY, arr.toString()).apply()
    }
}
