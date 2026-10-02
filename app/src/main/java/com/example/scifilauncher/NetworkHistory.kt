package com.example.scifilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class NetworkEntry(
    val ssid: String,
    val bssid: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val lat: Double,
    val lon: Double
)

private const val NET_PREFS = "network_history_prefs"
private const val NET_KEY = "entries"
private const val NET_MAX = 200

/** Real network history, same shape as LocationHistory - one entry per distinct network
 * (deduped by SSID - a network you've already connected to shouldn't get a second listing just
 * because it answered from a different BSSID this time, e.g. a dual-band router's 2.4GHz vs 5GHz
 * radio, or band-steering. User: "if it mentioned once then dont mention again just update the
 * last info". Trade-off noted: two real, different networks that happen to share an SSID would
 * now collapse into one entry too, but that's a rarer case than the reconnect-duplicate the user
 * actually hit), location captured once at first connection (not tracked continuously - a
 * network's physical location doesn't move). Feeds the Globe's NETWORK picker (see
 * combination.md): a list of networks you've connected to, fly to where each one was, plus an
 * INFO panel with what's actually knowable about it. */
object NetworkHistory {
    @Synchronized
    fun recordConnection(context: Context, ssid: String, bssid: String, lat: Double?, lon: Double?) {
        if (ssid.isBlank() || bssid.isBlank()) return
        val list = loadAll(context).toMutableList()
        val now = System.currentTimeMillis()
        val existingIndex = list.indexOfFirst { it.ssid == ssid }
        if (existingIndex >= 0) {
            val existing = list[existingIndex]
            list[existingIndex] = existing.copy(
                // BSSID updates to whichever radio/AP answered this time - "last info", not the
                // one recorded at first connection.
                bssid = bssid,
                lastSeen = now,
                // Keep the original first-seen location - only backfill if we didn't have one
                // yet (e.g. location permission was granted after the first connection).
                lat = existing.lat.takeIf { it != 0.0 || existing.lon != 0.0 } ?: (lat ?: existing.lat),
                lon = existing.lon.takeIf { existing.lat != 0.0 || it != 0.0 } ?: (lon ?: existing.lon)
            )
        } else {
            list.add(0, NetworkEntry(ssid, bssid, now, now, lat ?: 0.0, lon ?: 0.0))
        }
        while (list.size > NET_MAX) list.removeAt(list.lastIndex)
        saveAll(context, list)
    }

    fun loadAll(context: Context): List<NetworkEntry> {
        val raw = context.getSharedPreferences(NET_PREFS, Context.MODE_PRIVATE).getString(NET_KEY, null)
            ?: return emptyList()
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NetworkEntry(
                    o.getString("ssid"),
                    o.getString("bssid"),
                    o.getLong("first_seen"),
                    o.getLong("last_seen"),
                    o.getDouble("lat"),
                    o.getDouble("lon")
                )
            }
        }.getOrDefault(emptyList())
        // Collapse any duplicate-SSID entries saved before recordConnection started deduping by
        // SSID instead of BSSID - keeps the earliest firstSeen and the most recently-seen row's
        // bssid/lastSeen/location for each name, so old duplicates clean up automatically instead
        // of requiring the user to clear their history.
        if (parsed.map { it.ssid }.distinct().size == parsed.size) return parsed
        return parsed.groupBy { it.ssid }.map { (_, group) ->
            val newest = group.maxByOrNull { it.lastSeen }!!
            newest.copy(firstSeen = group.minOf { it.firstSeen })
        }.sortedByDescending { it.lastSeen }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(NET_PREFS, Context.MODE_PRIVATE).edit().remove(NET_KEY).apply()
    }

    private fun saveAll(context: Context, list: List<NetworkEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
            o.put("ssid", e.ssid)
            o.put("bssid", e.bssid)
            o.put("first_seen", e.firstSeen)
            o.put("last_seen", e.lastSeen)
            o.put("lat", e.lat)
            o.put("lon", e.lon)
            arr.put(o)
        }
        context.getSharedPreferences(NET_PREFS, Context.MODE_PRIVATE).edit().putString(NET_KEY, arr.toString()).apply()
    }
}
