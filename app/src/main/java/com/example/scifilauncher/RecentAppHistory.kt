package com.example.scifilauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

data class RecentAppEntry(
    val packageName: String,
    val lastOpenedMs: Long,
    val openCount: Int,
    val hasThumbnail: Boolean
)

private const val PREFS = "recent_app_history_prefs"
private const val KEY = "entries"
private const val THUMB_DIR = "recent_thumbs"

/**
 * Real per-app open history, driven by ScifiAccessibilityService's own foreground-app tracking
 * (not just launcher-initiated taps) - so switching back into an app via a notification, another
 * app's link, or Android's own multitasking counts as "entering" it too, same as the user meant
 * by "number of time entered ... counted +1 everytime u close the app and open again". Thumbnails
 * are real screenshots (AccessibilityService.takeScreenshot(), API 30+) taken shortly after each
 * app comes to foreground, not just its static icon.
 */
object RecentAppHistory {
    @Synchronized
    fun recordOpen(context: Context, packageName: String): RecentAppEntry {
        val all = loadAllRaw(context).toMutableMap()
        val existing = all[packageName]
        val updated = RecentAppEntry(
            packageName = packageName,
            lastOpenedMs = System.currentTimeMillis(),
            openCount = (existing?.openCount ?: 0) + 1,
            hasThumbnail = existing?.hasThumbnail ?: false
        )
        all[packageName] = updated
        saveAll(context, all)
        return updated
    }

    fun loadAll(context: Context): List<RecentAppEntry> =
        loadAllRaw(context).values.sortedByDescending { it.lastOpenedMs }

    fun thumbnailFile(context: Context, packageName: String): File =
        File(File(context.filesDir, THUMB_DIR).apply { mkdirs() }, "${packageName.replace('.', '_')}.png")

    fun loadThumbnail(context: Context, packageName: String): Bitmap? {
        val file = thumbnailFile(context, packageName)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    @Synchronized
    fun saveThumbnail(context: Context, packageName: String, bitmap: Bitmap) {
        runCatching {
            thumbnailFile(context, packageName).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        }
        val all = loadAllRaw(context).toMutableMap()
        val existing = all[packageName] ?: return
        all[packageName] = existing.copy(hasThumbnail = true)
        saveAll(context, all)
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching { File(context.filesDir, THUMB_DIR).listFiles()?.forEach { it.delete() } }
    }

    private fun loadAllRaw(context: Context): Map<String, RecentAppEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { pkg ->
                val e = obj.getJSONObject(pkg)
                RecentAppEntry(
                    packageName = pkg,
                    lastOpenedMs = e.getLong("lastOpenedMs"),
                    openCount = e.getInt("openCount"),
                    hasThumbnail = e.optBoolean("hasThumbnail", false)
                )
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveAll(context: Context, map: Map<String, RecentAppEntry>) {
        val obj = JSONObject()
        map.forEach { (pkg, e) ->
            obj.put(pkg, JSONObject().apply {
                put("lastOpenedMs", e.lastOpenedMs)
                put("openCount", e.openCount)
                put("hasThumbnail", e.hasThumbnail)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, obj.toString()).apply()
    }
}
