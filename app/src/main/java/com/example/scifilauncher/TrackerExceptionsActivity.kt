package com.example.scifilauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout

/** Per-app tracker/ad-block exceptions - lets the user pick apps that should bypass tracker
 * blocking entirely (see TrackerExceptionsStore for why this is a per-app VPN exclusion, not a
 * per-domain one). Reuses TerminalToolsUi, the same style already used by the VPN client and App
 * Workshop screens, per the request to use it "everywhere". */
class TrackerExceptionsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = TerminalToolsUi(this, "APP EXCEPTIONS", "Tracker exceptions", "Apps that skip tracker & ad blocking entirely.")
        render(ui)
    }

    private fun render(ui: TerminalToolsUi) {
        val excluded = TrackerExceptionsStore.excludedPackages(this).toMutableSet()
        val apps = AppResolver.findInstalledApps(this).sortedBy { it.label.lowercase() }

        val card = ui.card("INSTALLED APPS (${apps.size})")
        if (apps.isEmpty()) {
            ui.text(card, "No apps found.")
            return
        }
        apps.forEach { app ->
            val isExcluded = { excluded.contains(app.packageName) }
            lateinit var refreshLabel: () -> Unit
            val row = ui.button(card, rowLabel(app.label, isExcluded())) {
                val nowExcluded = !isExcluded()
                if (nowExcluded) excluded.add(app.packageName) else excluded.remove(app.packageName)
                TrackerExceptionsStore.setExcluded(this, app.packageName, nowExcluded)
                refreshLabel()
                restartVpnIfRunning()
            }
            refreshLabel = { row.text = rowLabel(app.label, isExcluded()) }
        }
    }

    private fun rowLabel(appLabel: String, excluded: Boolean): String =
        if (excluded) "$appLabel  —  EXCEPTED (tap to re-block)" else "$appLabel  —  blocked (tap to except)"

    /** Exceptions are only read when the VPN interface is (re)established - if tracker blocking
     * is currently on, bounce it through the same self-stop/self-start pattern the toggle itself
     * uses (see TrackerBlockVpnService's ACTION_STOP notes) so a change here applies immediately
     * instead of silently waiting for the next manual toggle or reboot. */
    private fun restartVpnIfRunning() {
        if (!TrackerBlockVpnService.isRunning) return
        runCatching {
            startService(Intent(this, TrackerBlockVpnService::class.java).setAction(TrackerBlockVpnService.ACTION_STOP))
            startService(Intent(this, TrackerBlockVpnService::class.java))
        }
    }
}
