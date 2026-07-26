package com.example.scifilauncher

import android.content.Context
import android.content.Intent
import androidx.core.graphics.drawable.toBitmap

/** Single source of truth for "find the app that matches what was said" - used by both the
 * home-screen Elene (MainActivity) and the cross-app overlay bubble (ScifiAccessibilityService),
 * which used to each have their own separate, differently-buggy matching logic. Always queries
 * PackageManager live (never a cached list some other code forgot to refresh), so a just-
 * installed app resolves immediately without needing the launcher to come back to the
 * foreground first.
 *
 * The backend LLM often guesses a package name from its own training knowledge rather than
 * knowing what's actually on this phone - that guess is frequently wrong for anything but the
 * most common apps. [resolvePackageName] treats [query] as either a literal package name OR a
 * plain spoken app name, and tries progressively looser label matches before giving up. */
object AppResolver {
    fun findInstalledApps(context: Context): List<AppItem> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        return pm.queryIntentActivities(mainIntent, 0).mapNotNull { info ->
            runCatching {
                AppItem(
                    label = info.loadLabel(pm).toString(),
                    packageName = info.activityInfo.packageName,
                    iconBitmap = info.activityInfo.loadIcon(pm).toBitmap()
                )
            }.getOrNull()
        }
    }

    fun resolvePackageName(context: Context, query: String, favorites: Set<String> = emptySet()): String? {
        if (query.isBlank()) return null
        val pm = context.packageManager
        if (pm.getLaunchIntentForPackage(query) != null) return query

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val candidates = pm.queryIntentActivities(mainIntent, 0).mapNotNull { info ->
            runCatching { Triple(info.activityInfo.packageName, info.loadLabel(pm).toString(), info.activityInfo.packageName in favorites) }.getOrNull()
        }
        if (candidates.isEmpty()) return null

        // A guessed package name (e.g. "com.some.guess") reads oddly as a label search, but its
        // tail segment is often the real product name ("guess") - worth trying alongside the
        // query as typed.
        val needles = listOf(
            normalize(query),
            normalize(query.substringAfterLast('.').replace('_', ' '))
        ).distinct().filter { it.isNotBlank() }

        for (needle in needles) {
            val compactNeedle = needle.replace(" ", "")
            // Each tier: favorites first, then anything - a looser match on a favorite is still
            // more likely correct than a loose match on a random installed app.
            val tiers = listOf<(String, String, Boolean) -> Boolean>(
                { _, label, _ -> normalize(label).contains(needle) },
                { _, label, _ -> normalize(label).replace(" ", "").contains(compactNeedle) },
                { _, label, _ -> compactNeedle.isNotEmpty() && normalize(label).replace(" ", "").let { compactNeedle.contains(it) && it.isNotEmpty() } }
            )
            for (tier in tiers) {
                candidates.firstOrNull { (pkg, label, isFav) -> isFav && tier(pkg, label, isFav) }?.let { return it.first }
                candidates.firstOrNull { (pkg, label, isFav) -> tier(pkg, label, isFav) }?.let { return it.first }
            }
        }
        return null
    }

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()
}
