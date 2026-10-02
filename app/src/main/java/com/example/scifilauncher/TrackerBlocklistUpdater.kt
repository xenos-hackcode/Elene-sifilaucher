package com.example.scifilauncher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val PREFS = "tracker_blocklist_update_prefs"
private const val KEY_CUSTOM_LIST = "custom_list"
private const val KEY_LAST_UPDATED = "last_updated"
// Same real AdAway source the bundled starter list was already built from (see
// TrackerBlockVpnService's own comment: "grew from a 71-entry starter list to ~6,500 real AdAway
// entries") - fetching a fresh copy of the same trusted list, not a new/different source.
private const val SOURCE_URL = "https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt"

/** Lets the user refresh the tracker/ad blocklist without a full app update - the bundled
 * R.raw.tracker_blocklist is a snapshot from whenever the APK was built. */
object TrackerBlocklistUpdater {
    suspend fun update(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(SOURCE_URL).build()
            val body = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string() ?: error("Empty response")
            }
            // Standard hosts-file format: "0.0.0.0 tracker.example.com" / "127.0.0.1 ..." per line.
            val domains = body.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && (it.startsWith("0.0.0.0 ") || it.startsWith("127.0.0.1 ")) }
                .mapNotNull { line -> line.substringAfter(' ').trim().takeIf { it.isNotEmpty() && it != "localhost" } }
                .toSet()
            // Sanity floor - a truncated/malformed download shouldn't silently replace a real
            // ~6,500-entry list with a near-empty one.
            if (domains.size < 100) error("Downloaded list looks too small (${domains.size} entries) - not replacing the current one")
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_CUSTOM_LIST, domains)
                .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
                .apply()
            domains.size
        }
    }

    /** Null means "no update fetched yet, use the bundled default". */
    fun customList(context: Context): Set<String>? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_CUSTOM_LIST, null)

    fun lastUpdatedMillis(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_UPDATED, 0L)
}
