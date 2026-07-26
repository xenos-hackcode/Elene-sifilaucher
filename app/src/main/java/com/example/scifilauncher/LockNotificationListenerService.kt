package com.example.scifilauncher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class LockNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return

        val prefs = getSharedPreferences("lock_prefs", MODE_PRIVATE)
        val lockedSet = loadLockedApps(prefs)
        val hideLocked = loadHideLockedNotifications(prefs)

        if (!hideLocked) return

        val isLockedNow = isAppLockedRightNow(
            prefs = prefs,
            packageName = pkg,
            lockedApps = lockedSet
        )

        if (isLockedNow) {
            cancelNotification(sbn.key)
        }
    }
}