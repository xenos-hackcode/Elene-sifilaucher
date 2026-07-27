package com.example.scifilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** General "remember this" facts the user tells Elene to keep - distinct from
 * EleneMemoryPrefs.kt's avoid_topics (which is specifically "never bring this up"). The whole
 * point of this store: the backend's own conversation history is in-memory and resets whenever
 * the Cloud Run instance recycles/scales to zero - this is the durable fallback, fed back into
 * every turn's context (see ctxMap builders) so Elene can still recall a fact even after her
 * own short-term memory of the conversation is gone. */
data class RememberedFact(val id: Long, val text: String, val timestamp: Long)

private const val PREFS_NAME = "remembered_fact_prefs"
private const val KEY_ENTRIES = "entries"
private const val MAX_ENTRIES = 300

object RememberedFactLog {
    @Synchronized
    fun record(context: Context, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val list = loadAll(context).toMutableList()
        list.add(0, RememberedFact(System.currentTimeMillis(), trimmed, System.currentTimeMillis()))
        while (list.size > MAX_ENTRIES) list.removeAt(list.lastIndex)
        saveAll(context, list)
    }

    @Synchronized
    fun forget(context: Context, id: Long) {
        val list = loadAll(context).toMutableList()
        list.removeAll { it.id == id }
        saveAll(context, list)
    }

    fun loadAll(context: Context): List<RememberedFact> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ENTRIES, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RememberedFact(
                    id = o.getLong("id"),
                    text = o.getString("text"),
                    timestamp = o.getLong("timestamp")
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Newest-first (record() always inserts at index 0), capped short so it doesn't blow out
     * the backend prompt's context block. Deliberately does NOT try to detect and delete
     * "superseded" facts itself (e.g. an old name vs a newly corrected one) - reliably telling
     * two facts are "about the same thing" from text alone is fragile. Instead the ordering
     * itself carries the signal: facts are numbered oldest-to-newest-doesn't-matter, newest
     * always listed FIRST, with an explicit instruction in the backend prompt that a later
     * (earlier-listed) fact wins if two conflict - simple, robust, and doesn't require ever
     * silently deleting something the user might still want visible in the Memory screen. */
    fun asContextString(context: Context, limit: Int = 20): String =
        loadAll(context).take(limit).mapIndexed { i, fact ->
            if (i == 0) "most recent: ${fact.text}" else fact.text
        }.joinToString("; ")

    private fun saveAll(context: Context, list: List<RememberedFact>) {
        val arr = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
            o.put("id", e.id)
            o.put("text", e.text)
            o.put("timestamp", e.timestamp)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }
}
