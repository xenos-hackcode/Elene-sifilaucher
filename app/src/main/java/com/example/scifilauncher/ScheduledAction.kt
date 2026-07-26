package com.example.scifilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A device action deferred to a later time - "uninstall X in 2 hours", "download Y after
 * 90 minutes". Stored as plain data (not a closure) because the process that eventually
 * executes it (ScheduledActionReceiver, fired by AlarmManager) may not be the same one that
 * scheduled it - the app could easily have been killed in between. */
data class ScheduledAction(
    val id: Long,
    val type: String,       // "uninstall" | "download_app" - see ScheduledActionReceiver
    val target: String,     // package name or search query, depending on type
    val label: String,      // human-readable, for display
    val executeAtMillis: Long,
    val createdAtMillis: Long
)

private const val SCHED_PREFS = "scheduled_actions_prefs"
private const val SCHED_KEY = "entries"

object ScheduledActions {
    @Synchronized
    fun record(context: Context, action: ScheduledAction) {
        val list = loadAll(context).toMutableList()
        list.add(action)
        saveAll(context, list)
    }

    @Synchronized
    fun remove(context: Context, id: Long) {
        val list = loadAll(context).filterNot { it.id == id }
        saveAll(context, list)
    }

    fun loadAll(context: Context): List<ScheduledAction> {
        val raw = context.getSharedPreferences(SCHED_PREFS, Context.MODE_PRIVATE).getString(SCHED_KEY, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ScheduledAction(
                    id = o.getLong("id"),
                    type = o.getString("type"),
                    target = o.getString("target"),
                    label = o.getString("label"),
                    executeAtMillis = o.getLong("executeAtMillis"),
                    createdAtMillis = o.getLong("createdAtMillis")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(context: Context, list: List<ScheduledAction>) {
        val arr = JSONArray()
        list.forEach { a ->
            val o = JSONObject()
            o.put("id", a.id)
            o.put("type", a.type)
            o.put("target", a.target)
            o.put("label", a.label)
            o.put("executeAtMillis", a.executeAtMillis)
            o.put("createdAtMillis", a.createdAtMillis)
            arr.put(o)
        }
        context.getSharedPreferences(SCHED_PREFS, Context.MODE_PRIVATE).edit().putString(SCHED_KEY, arr.toString()).apply()
    }
}
