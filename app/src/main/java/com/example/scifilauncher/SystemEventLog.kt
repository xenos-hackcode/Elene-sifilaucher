package com.example.scifilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val EVT_PREFS = "system_event_log_prefs"
private const val EVT_KEY = "entries"
private const val EVT_MAX = 300

/** Notable system-level events worth having real data on later, rather than guessing when
 * something's suspected to have gone wrong - every biometric prompt shown (and its outcome), VPN
 * drops/reconnects, Voice ID model load failures, and Sequence Mode arm/exit. Distinct from
 * ErrorLog (this app's own bugs/crashes) and ActionLog (device-owner actions taken on other apps) -
 * this is "what changed or happened, that might matter for diagnosing something later". Uses the
 * same entry shape as ErrorLog so both logs can be displayed together in AppLogScreen without a
 * second screen. */
object SystemEventLog {
    @Synchronized
    fun record(context: Context, tag: String, message: String) {
        val list = loadAll(context).toMutableList()
        list.add(0, ErrorLogEntry(System.currentTimeMillis(), tag, message.take(2000)))
        while (list.size > EVT_MAX) list.removeAt(list.lastIndex)
        saveAll(context, list)
    }

    fun loadAll(context: Context): List<ErrorLogEntry> {
        val raw = context.getSharedPreferences(EVT_PREFS, Context.MODE_PRIVATE).getString(EVT_KEY, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ErrorLogEntry(o.getLong("timestamp"), o.getString("tag"), o.getString("message"))
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(context: Context, list: List<ErrorLogEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
            o.put("timestamp", e.timestamp)
            o.put("tag", e.tag)
            o.put("message", e.message)
            arr.put(o)
        }
        context.getSharedPreferences(EVT_PREFS, Context.MODE_PRIVATE).edit().putString(EVT_KEY, arr.toString()).apply()
    }
}
