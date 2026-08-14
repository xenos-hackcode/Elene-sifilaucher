package com.example.scifilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class NearbyDeviceType { BLUETOOTH, WIFI }

data class NearbyDeviceEntry(
    val id: String,        // MAC address for Bluetooth, IP address for WiFi/LAN
    val name: String,
    val type: NearbyDeviceType,
    val lastSeenMs: Long
)

private const val PREFS = "nearby_device_history_prefs"
private const val KEY = "entries"
private const val MAX_ENTRIES = 200

/** A single scan alone can't answer "when was this last seen" - that needs remembering what
 * was found across previous scans too. Keyed by MAC (Bluetooth) or IP (WiFi/LAN) - IP isn't a
 * perfectly stable identity across a DHCP lease renewal, but it's the only identifier a ping
 * sweep can get without parsing the router's ARP table, and still good enough for "have I seen
 * this address before, and when" on a typical home network where addresses rarely change. */
object NearbyDeviceHistory {
    @Synchronized
    fun recordSeen(context: Context, id: String, name: String, type: NearbyDeviceType, whenMs: Long) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id && it.type == type }
        val updated = NearbyDeviceEntry(id, name, type, whenMs)
        if (idx >= 0) list[idx] = updated else list.add(updated)
        while (list.size > MAX_ENTRIES) {
            val oldest = list.minByOrNull { it.lastSeenMs } ?: break
            list.remove(oldest)
        }
        saveAll(context, list)
    }

    fun loadAll(context: Context): List<NearbyDeviceEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NearbyDeviceEntry(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    type = NearbyDeviceType.valueOf(o.getString("type")),
                    lastSeenMs = o.getLong("lastSeenMs")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(context: Context, list: List<NearbyDeviceEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
            o.put("id", e.id)
            o.put("name", e.name)
            o.put("type", e.type.name)
            o.put("lastSeenMs", e.lastSeenMs)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
