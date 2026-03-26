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
        val lockTimeoutMinutes = loadLockTimeoutMinutes(prefs)
        val hideLocked = loadHideLockedNotifications(prefs)

        if (!hideLocked) return

        // App is in locked list AND timer says it is locked → cancel notification
        if (lockedSet.contains(pkg) && shouldRequireUnlock(prefs, lockTimeoutMinutes)) {
            cancelNotification(sbn.key)
        }
    }
}
