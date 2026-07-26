package com.example.scifilauncher

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

data class LastMessageInfo(
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String
)

/** Notification access is a special Android permission that can only be granted through
 * Settings, never a normal runtime permission dialog - without this explicit check + deep
 * link, the listener silently never connects and nothing ever shows up, with no error
 * anywhere to explain why. */
fun isNotificationListenerEnabled(context: Context): Boolean {
    val enabled = android.provider.Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    return enabled.contains(context.packageName)
}

data class ReplyableNotification(
    val key: String,                    // sbn.key
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String,
    val action: Notification.Action?    // direct reply action
)

class XenosNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var lastMessageInfo: LastMessageInfo? = null

        @Volatile
        var hasUnreadMessage: Boolean = false

        @JvmStatic
        val missedNotifications: MutableList<LastMessageInfo> = mutableListOf()

        // sbn.key -> replyable notification
        @JvmStatic
        val replyableMap: MutableMap<String, ReplyableNotification> = mutableMapOf()

        // active service instance
        @Volatile
        var instance: XenosNotificationListener? = null

        /** Removes a single entry from the feed, e.g. after the user dismisses it in the
         * custom in-app notification bar. Matches on identity since LastMessageInfo has no key. */
        @JvmStatic
        @Synchronized
        fun dismissMissed(item: LastMessageInfo) {
            missedNotifications.remove(item)
        }

        @JvmStatic
        @Synchronized
        fun clearMissed() {
            missedNotifications.clear()
            hasUnreadMessage = false
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d("XenosNL", "NotificationListener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.d("XenosNL", "NotificationListener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val notification = sbn.notification ?: return
        val pkg = sbn.packageName ?: return

        // ignore own launcher
        if (pkg == packageName) return

        val extras: Bundle = notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        val appName = try {
            val pm: PackageManager = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkg
        }

        if (text.isNotBlank()) {
            val msg = LastMessageInfo(
                appName = appName,
                packageName = pkg,
                title = title,
                text = text
            )
            lastMessageInfo = msg
            hasUnreadMessage = true

            missedNotifications.add(msg)
            if (missedNotifications.size > 50) {
                missedNotifications.removeAt(0)
            }

            val replyAction = findDirectReplyAction(notification)
            if (replyAction != null) {
                replyableMap[sbn.key] = ReplyableNotification(
                    key = sbn.key,
                    appName = appName,
                    packageName = pkg,
                    title = title,
                    text = text,
                    action = replyAction
                )
                Log.d("XenosNL", "Stored replyable notification for $appName / $title")
            } else {
                Log.d("XenosNL", "No RemoteInput actions for $appName / $title")
            }
        }

        // Only announce via Elene if toggle is ON and battery saver not Aggressive
        if (canAnnounceNotification()) {
            val intent = Intent("com.example.scifilauncher.NEW_NOTIFICATION_VOICE").apply {
                putExtra("appName", appName)
                putExtra("packageName", pkg)
            }
            sendBroadcast(intent)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null) {
            replyableMap.remove(sbn.key)
        }
    }

    private fun findDirectReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        for (act in actions) {
            val remoteInputs = act.remoteInputs
            if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                return act
            }
        }
        return null
    }

    fun sendDirectReply(notificationKey: String, replyText: String): Boolean {
        val rn = replyableMap[notificationKey] ?: return false
        val action = rn.action ?: return false

        return try {
            val remoteInputs = action.remoteInputs ?: return false
            val pendingIntent: PendingIntent = action.actionIntent

            val fillInIntent = Intent()
            val bundle = Bundle()
            for (ri in remoteInputs) {
                bundle.putCharSequence(ri.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, bundle)

            pendingIntent.send(this, 0, fillInIntent)
            Log.d("XenosNL", "Sent direct reply to ${rn.appName} / ${rn.title}")
            true
        } catch (e: Exception) {
            Log.e("XenosNL", "Error sending direct reply", e)
            false
        }
    }

    /**
     * Checks if Elene is allowed to voice-announce this notification.
     * Controlled by:
     *  - theme_prefs.elene_voice_on (your Notification toggle)
     *  - battery_prefs + BatterySaverMode (Aggressive mutes her)
     */
    private fun canAnnounceNotification(): Boolean {
        val themePrefs = getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
        val batteryPrefs = getSharedPreferences("battery_prefs", Context.MODE_PRIVATE)

        val eleneVoiceOn = themePrefs.getBoolean("elene_voice_on", true)
        val batteryMode = loadBatterySaverMode(batteryPrefs)

        // Announce only when toggle ON AND battery saver is OFF
        return eleneVoiceOn && batteryMode == BatterySaverMode.OFF
    }
}
