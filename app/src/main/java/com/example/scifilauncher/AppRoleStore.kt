package com.example.scifilauncher

import android.content.Context

/** One generic role a voice command can refer to ("open music", "open chat", "open call") that
 * you point at whichever specific app you actually mean, once, in Settings > Info - the same app
 * can be assigned to more than one role (e.g. one super-app covering both chat and call). */
enum class AppRole(val key: String, val label: String) {
    MUSIC("music", "Music"),
    CHAT("chat", "Chat"),
    CALL("call", "Call")
}

/** Persists which real installed app each [AppRole] points at. Nothing here resolves anything by
 * guessing a label/search match - it's a direct, user-chosen packageName per role, so "open
 * music" always opens the exact same app you picked, not whatever AppResolver's fuzzy search
 * happens to turn up that day. */
object AppRoleStore {
    private const val PREFS = "app_role_prefs"

    fun get(context: Context, role: AppRole): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(role.key, null)

    fun set(context: Context, role: AppRole, packageName: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (packageName == null) remove(role.key) else putString(role.key, packageName)
        }.apply()
    }

    fun labelFor(context: Context, role: AppRole, apps: List<AppItem>): String? {
        val pkg = get(context, role) ?: return null
        return apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
    }
}
