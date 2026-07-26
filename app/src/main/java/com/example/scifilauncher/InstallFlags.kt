package com.example.scifilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class InstallFlagEntry(
    val timestamp: Long,
    val packageName: String,
    val appLabel: String,
    val sideloaded: Boolean,
    val flaggedPermissions: List<String>
) {
    val isFlagged: Boolean get() = sideloaded || flaggedPermissions.isNotEmpty()
}

private const val INSTALL_PREFS = "install_flags_prefs"
private const val INSTALL_KEY = "entries"
private const val INSTALL_MAX = 300

/** Record of every new app install this launcher has seen, with a lightweight risk read -
 * NOT a malware-signature scanner, just "was this sideloaded, and does it ask for anything
 * sensitive" - the same kind of at-a-glance flags a careful person would check by hand. */
object InstallFlags {
    @Synchronized
    fun record(context: Context, entry: InstallFlagEntry) {
        val list = loadAll(context).toMutableList()
        list.add(0, entry)
        while (list.size > INSTALL_MAX) list.removeAt(list.lastIndex)
        saveAll(context, list)
    }

    fun loadAll(context: Context): List<InstallFlagEntry> {
        val raw = context.getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE).getString(INSTALL_KEY, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val permsArr = o.optJSONArray("flaggedPermissions") ?: JSONArray()
                InstallFlagEntry(
                    timestamp = o.getLong("timestamp"),
                    packageName = o.getString("packageName"),
                    appLabel = o.getString("appLabel"),
                    sideloaded = o.getBoolean("sideloaded"),
                    flaggedPermissions = (0 until permsArr.length()).map { permsArr.getString(it) }
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(context: Context, list: List<InstallFlagEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
            o.put("timestamp", e.timestamp)
            o.put("packageName", e.packageName)
            o.put("appLabel", e.appLabel)
            o.put("sideloaded", e.sideloaded)
            o.put("flaggedPermissions", JSONArray(e.flaggedPermissions))
            arr.put(o)
        }
        context.getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE).edit().putString(INSTALL_KEY, arr.toString()).apply()
    }
}
