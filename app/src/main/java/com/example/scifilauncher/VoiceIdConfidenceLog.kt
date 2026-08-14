package com.example.scifilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val CONF_PREFS = "voice_id_confidence_log_prefs"
private const val CONF_KEY = "entries"
private const val CONF_MAX = 40
private const val DRIFT_WINDOW = 5
private const val DRIFT_FAIL_COUNT = 3

data class VoiceIdConfidenceEntry(
    val timestamp: Long,
    val style: String,
    val score: Float,
    val passed: Boolean
)

/**
 * Tracks how real (non-test-button) voice verification attempts have actually been scoring, so
 * a slow drift toward more rejections is visible before the user notices it themselves. Only the
 * two production gates (ScifiAccessibilityService's command gate, MainActivity's confirmation
 * voice-match logging) record here - the Security screen's "Test voice match" button doesn't,
 * since that's a deliberate practice call and would bias real drift stats toward whatever
 * conditions the user happens to test in.
 */
object VoiceIdConfidenceLog {
    @Synchronized
    fun record(context: Context, style: VoiceStyle, score: Float, passed: Boolean) {
        val list = loadAll(context).toMutableList()
        list.add(0, VoiceIdConfidenceEntry(System.currentTimeMillis(), style.name, score, passed))
        while (list.size > CONF_MAX) list.removeAt(list.lastIndex)
        saveAll(context, list)
    }

    fun loadAll(context: Context): List<VoiceIdConfidenceEntry> {
        val raw = context.getSharedPreferences(CONF_PREFS, Context.MODE_PRIVATE).getString(CONF_KEY, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                VoiceIdConfidenceEntry(o.getLong("timestamp"), o.getString("style"), o.getDouble("score").toFloat(), o.getBoolean("passed"))
            }
        }.getOrDefault(emptyList())
    }

    /** True if most of the last [DRIFT_WINDOW] real attempts failed - a real accuracy dip
     * (tired voice, new environment, a stale enrollment) worth nudging the user about, not a
     * one-off rejection. Needs the full window before it can say anything, so a single bad day
     * right after enrollment doesn't immediately trip it. */
    fun isDrifting(context: Context): Boolean {
        val recent = loadAll(context).take(DRIFT_WINDOW)
        if (recent.size < DRIFT_WINDOW) return false
        return recent.count { !it.passed } >= DRIFT_FAIL_COUNT
    }

    /** Called after a successful re-enrollment - without this, old pre-enrollment failures stay
     * in the window isDrifting() reads, so the "weaker lately" nudge kept showing even right
     * after a fresh, good enrollment (real bug, found 2026-08-10). A fresh enrollment is exactly
     * the fix the drift warning was suggesting, so its own history shouldn't outlive it. */
    fun clear(context: Context) {
        context.getSharedPreferences(CONF_PREFS, Context.MODE_PRIVATE).edit().remove(CONF_KEY).apply()
    }

    private fun saveAll(context: Context, list: List<VoiceIdConfidenceEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
            o.put("timestamp", e.timestamp)
            o.put("style", e.style)
            o.put("score", e.score)
            o.put("passed", e.passed)
            arr.put(o)
        }
        context.getSharedPreferences(CONF_PREFS, Context.MODE_PRIVATE).edit().putString(CONF_KEY, arr.toString()).apply()
    }
}
