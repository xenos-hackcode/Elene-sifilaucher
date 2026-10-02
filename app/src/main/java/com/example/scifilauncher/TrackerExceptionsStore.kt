package com.example.scifilauncher

import android.content.Context

/** Per-app tracker/ad-block exceptions - package names that should bypass the tracker-block VPN
 * entirely (its own traffic never enters the tunnel, so nothing it does is DNS-filtered),
 * for apps that break when their own trackers/analytics are blocked (banking apps flagging the
 * device, some games' anti-cheat, etc). Backed by VpnService.Builder.addDisallowedApplication(),
 * which is a real per-app VPN exclusion the OS enforces - not a domain-level exception, since the
 * DNS sinkhole itself has no reliable way to attribute a given DNS query to the app that made it. */
object TrackerExceptionsStore {
    private const val PREFS = "tracker_exceptions_prefs"
    private const val KEY = "excluded_packages"

    fun excludedPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()

    fun setExcluded(context: Context, packageName: String, excluded: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        if (excluded) current.add(packageName) else current.remove(packageName)
        prefs.edit().putStringSet(KEY, current).apply()
    }
}
