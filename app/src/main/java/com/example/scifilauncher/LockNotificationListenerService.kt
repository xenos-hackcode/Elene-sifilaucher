package com.example.scifilauncher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

// Hides notifications entirely while Sequence Mode (anti-theft) lockdown is active - whoever
// has the phone during a lockdown shouldn't see incoming messages/alerts on the lock screen.
// Cancelling the notification alone isn't enough - the system can already have buzzed/sounded/lit
// the LED for it before this service's onNotificationPosted ever runs, since that's driven by the
// notification's own channel settings at post time, not by a listener reacting afterward. Putting
// the phone into real "Total silence" DND (via requestInterruptionFilter) while lockdown is active
// closes that gap - nothing buzzes, sounds, or pops up at all, not even after-the-fact cancellation.
class LockNotificationListenerService : NotificationListenerService() {

    companion object {
        var instance: LockNotificationListenerService? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        val prefs = getSharedPreferences("lock_prefs", MODE_PRIVATE)
        applySilence(isSequenceModeActive(prefs))
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    fun applySilence(silent: Boolean) {
        runCatching {
            requestInterruptionFilter(
                if (silent) INTERRUPTION_FILTER_NONE else INTERRUPTION_FILTER_ALL
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val prefs = getSharedPreferences("lock_prefs", MODE_PRIVATE)
        if (isSequenceModeActive(prefs)) {
            cancelNotification(sbn.key)
        }
    }
}