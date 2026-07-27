package com.example.scifilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class UpdateCategory { FEATURE_ADDED, BUG_FIX, FEATURE_REMOVED, OTHER }
enum class ProposalOrigin { USER, ELENE }
enum class UpdateProposalStatus { PROPOSED, APPROVED, DENIED }

/** APPROVED means the user has greenlit the change with a real fingerprint scan - it does NOT
 * mean the change has actually happened. There is no code-generation/build/deploy pipeline
 * behind this yet (a separate, much larger future piece) - this is only the approval queue, so
 * that whatever comes later is fingerprint-gated by construction, not by promise. */
data class UpdateProposalEntry(
    val id: Long,
    val title: String,
    val description: String,
    val category: UpdateCategory,
    val origin: ProposalOrigin,
    val timestamp: Long,
    val status: UpdateProposalStatus,
    val respondedAt: Long?,
    val denialReason: String? = null
)

private const val PREFS_NAME = "update_proposal_log_prefs"
private const val KEY_ENTRIES = "entries"
private const val MAX_ENTRIES = 300

object UpdateProposalLog {
    @Synchronized
    fun record(context: Context, entry: UpdateProposalEntry) {
        val list = loadAll(context).toMutableList()
        list.add(0, entry)
        while (list.size > MAX_ENTRIES) list.removeAt(list.lastIndex)
        saveAll(context, list)
    }

    @Synchronized
    fun updateStatus(context: Context, id: Long, status: UpdateProposalStatus) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(status = status, respondedAt = System.currentTimeMillis())
            saveAll(context, list)
        }
    }

    fun loadAll(context: Context): List<UpdateProposalEntry> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ENTRIES, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                UpdateProposalEntry(
                    id = o.getLong("id"),
                    title = o.getString("title"),
                    description = o.getString("description"),
                    category = runCatching { UpdateCategory.valueOf(o.getString("category")) }.getOrDefault(UpdateCategory.OTHER),
                    origin = runCatching { ProposalOrigin.valueOf(o.getString("origin")) }.getOrDefault(ProposalOrigin.USER),
                    timestamp = o.getLong("timestamp"),
                    status = runCatching { UpdateProposalStatus.valueOf(o.getString("status")) }.getOrDefault(UpdateProposalStatus.PROPOSED),
                    respondedAt = if (o.has("respondedAt") && !o.isNull("respondedAt")) o.getLong("respondedAt") else null,
                    denialReason = if (o.has("denialReason") && !o.isNull("denialReason")) o.getString("denialReason") else null
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(context: Context, list: List<UpdateProposalEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
            o.put("id", e.id)
            o.put("title", e.title)
            o.put("description", e.description)
            o.put("category", e.category.name)
            o.put("origin", e.origin.name)
            o.put("timestamp", e.timestamp)
            o.put("status", e.status.name)
            o.put("respondedAt", e.respondedAt)
            o.put("denialReason", e.denialReason)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }
}
