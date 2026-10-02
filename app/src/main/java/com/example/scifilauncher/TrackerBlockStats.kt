package com.example.scifilauncher

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFS = "tracker_block_stats_prefs"
private const val KEY_DATE = "date"
private const val KEY_TODAY_COUNT = "today_count"
private const val KEY_ALL_TIME_COUNT = "all_time_count"

/** Real running counts of blocked tracker/ad domains - cosmetic but satisfying, per the user's
 * own "nice to have" ask. Today's count resets on a real date change (not a rolling 24h window),
 * all-time count never resets. */
object TrackerBlockStats {
    fun recordBlock(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = dateKey()
        val storedDate = prefs.getString(KEY_DATE, null)
        val todayCount = if (storedDate == today) prefs.getInt(KEY_TODAY_COUNT, 0) else 0
        prefs.edit()
            .putString(KEY_DATE, today)
            .putInt(KEY_TODAY_COUNT, todayCount + 1)
            .putInt(KEY_ALL_TIME_COUNT, prefs.getInt(KEY_ALL_TIME_COUNT, 0) + 1)
            .apply()
    }

    fun todayCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getString(KEY_DATE, null) == dateKey()) prefs.getInt(KEY_TODAY_COUNT, 0) else 0
    }

    fun allTimeCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ALL_TIME_COUNT, 0)

    private fun dateKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
