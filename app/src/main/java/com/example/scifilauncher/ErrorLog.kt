package com.example.scifilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ErrorLogEntry(val timestamp: Long, val tag: String, val message: String)

private const val ERR_PREFS = "error_log_prefs"
private const val ERR_KEY = "entries"
private const val ERR_MAX = 200

/** Internal errors/crashes from this app itself - separate from ActionLog, which tracks
 * actions taken regarding other apps. This is "what went wrong in here", not "what did we
 * do out there". */
object ErrorLog {
    @Synchronized
    fun record(context: Context, tag: String, message: String) {
        val list = loadAll(context).toMutableList()
        list.add(0, ErrorLogEntry(System.currentTimeMillis(), tag, message.take(2000)))
        while (list.size > ERR_MAX) list.removeAt(list.lastIndex)
        saveAll(context, list)
    }

    fun loadAll(context: Context): List<ErrorLogEntry> {
        val raw = context.getSharedPreferences(ERR_PREFS, Context.MODE_PRIVATE).getString(ERR_KEY, null)
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
        context.getSharedPreferences(ERR_PREFS, Context.MODE_PRIVATE).edit().putString(ERR_KEY, arr.toString()).apply()
    }
}
