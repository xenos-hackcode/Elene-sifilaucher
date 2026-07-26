package com.example.scifilauncher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

// Hides notifications entirely while Sequence Mode (anti-theft) lockdown is active - whoever
// has the phone during a lockdown shouldn't see incoming messages/alerts on the lock screen.
class LockNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val prefs = getSharedPreferences("lock_prefs", MODE_PRIVATE)
        if (isSequenceModeActive(prefs)) {
            cancelNotification(sbn.key)
        }
    }
}