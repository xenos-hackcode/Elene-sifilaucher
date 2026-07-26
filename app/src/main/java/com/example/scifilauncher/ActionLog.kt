package com.example.scifilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ActionRequestStatus { PENDING, APPROVED, DENIED, SNOOZED }

data class ActionRequestEntry(
    val id: Long,
    val actionLabel: String,
    val reason: String,
    val target: String?,
    val timestamp: Long,
    val status: ActionRequestStatus,
    val respondedAt: Long?,
    val denialReason: String? = null,
    val voiceMatchScore: Float? = null
)

private const val PREFS_NAME = "action_log_prefs"
private const val KEY_ENTRIES = "entries"
private const val MAX_ENTRIES = 300

/** Every device-owner-level action (install, uninstall, force-stop, permission grants, etc.)
 * goes through a Yes/No/Ask-later confirmation before it happens - this is the permanent
 * record of every one of those requests, what was asked, why, and how it was resolved. */
object ActionLog {
    @Synchronized
    fun record(context: Context, entry: ActionRequestEntry) {
        val list = loadAll(context).toMutableList()
        list.add(0, entry)
        while (list.size > MAX_ENTRIES) list.removeAt(list.lastIndex)
        saveAll(context, list)
    }

    @Synchronized
    fun updateStatus(context: Context, id: Long, status: ActionRequestStatus) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(status = status, respondedAt = System.currentTimeMillis())
            saveAll(context, list)
        }
    }

    /** Purely informational - denying an action is final regardless of whether a reason is
     * given, this just records the "why" for the Requests history. */
    @Synchronized
    fun recordDenialReason(context: Context, id: Long, reason: String) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(denialReason = reason)
            saveAll(context, list)
        }
    }

    /** Non-blocking - Voice ID is a supporting signal, never the thing that gates approval.
     * Recorded after fingerprint approval so the Requests history shows whether the voice on
     * the mic at approval time matched the enrolled sample. */
    @Synchronized
    fun recordVoiceMatch(context: Context, id: Long, score: Float) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(voiceMatchScore = score)
            saveAll(context, list)
        }
    }

    fun loadAll(context: Context): List<ActionRequestEntry> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ENTRIES, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ActionRequestEntry(
                    id = o.getLong("id"),
                    actionLabel = o.getString("actionLabel"),
                    reason = o.getString("reason"),
                    target = if (o.isNull("target")) null else o.optString("target"),
                    timestamp = o.getLong("timestamp"),
                    status = runCatching { ActionRequestStatus.valueOf(o.getString("status")) }.getOrDefault(ActionRequestStatus.PENDING),
                    respondedAt = if (o.has("respondedAt") && !o.isNull("respondedAt")) o.getLong("respondedAt") else null,
                    denialReason = if (o.has("denialReason") && !o.isNull("denialReason")) o.getString("denialReason") else null,
                    voiceMatchScore = if (o.has("voiceMatchScore") && !o.isNull("voiceMatchScore")) o.getDouble("voiceMatchScore").toFloat() else null
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(context: Context, list: List<ActionRequestEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
            o.put("id", e.id)
            o.put("actionLabel", e.actionLabel)
            o.put("reason", e.reason)
            o.put("target", e.target)
            o.put("timestamp", e.timestamp)
            o.put("status", e.status.name)
            o.put("respondedAt", e.respondedAt)
            o.put("denialReason", e.denialReason)
            o.put("voiceMatchScore", e.voiceMatchScore)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }
}
