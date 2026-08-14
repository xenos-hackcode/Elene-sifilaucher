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
    val text: String,
    // The notification's own real action buttons (e.g. a call's "End call", a download's
    // "Pause") - the same PendingIntents Android's own status bar would fire, not guessed
    // UI. RemoteInput (direct-reply) actions are filtered out here since those already have
    // their own dedicated reply flow via replyableMap/sendDirectReply.
    val actions: List<Notification.Action> = emptyList()
)

/** A VoIP call (WhatsApp/Zoom/etc.) ringing right now - these never touch TelephonyManager/
 * TelecomManager at all, so the only reliable, general way to notice one is the same convention
 * calling apps use for their own incoming-call notification: Notification.CATEGORY_CALL. Storing
 * the raw actions (rather than pre-resolving which is Accept/Decline) since their exact order
 * isn't a guaranteed contract - matched by label text when actually responding instead. */
data class IncomingVoipCall(
    val key: String,
    val appName: String,
    val packageName: String,
    val callerName: String,
    val actions: List<Notification.Action>
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

        @Volatile
        var incomingVoipCall: IncomingVoipCall? = null

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

        // Without this, only notifications posted AFTER this connect are ever seen - anything
        // already showing (e.g. a long-lived foreground-service notification that posted once
        // and hasn't updated since) stays invisible to this feed until it happens to re-post,
        // even though it's still sitting in the real system tray. Found live 2026-08-09: a
        // frequently-updating notification (charging %) always showed up, but ongoing
        // rarely-updating ones (an SMS relay app's persistent notification) never did.
        runCatching { activeNotifications }.getOrNull()?.forEach { sbn -> onNotificationPosted(sbn) }
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
            val buttonActions = notification.actions
                ?.filter { it.remoteInputs.isNullOrEmpty() }
                ?: emptyList()
            val msg = LastMessageInfo(
                appName = appName,
                packageName = pkg,
                title = title,
                text = text,
                actions = buttonActions
            )
            lastMessageInfo = msg
            hasUnreadMessage = true

            missedNotifications.add(msg)
            // Raised from 50 - this list is already in-memory only (lost on process death, not
            // persisted to disk), so a higher cap costs a bit more RAM, not storage.
            if (missedNotifications.size > 300) {
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

        // A ringing VoIP call - only broadcast once per distinct call (a ringing notification
        // can legitimately re-post/update itself while still ringing; re-announcing every update
        // would repeat "incoming call" over and over for the same call).
        if (notification.category == Notification.CATEGORY_CALL && incomingVoipCall?.key != sbn.key) {
            val call = IncomingVoipCall(
                key = sbn.key,
                appName = appName,
                packageName = pkg,
                callerName = title.ifBlank { appName },
                actions = notification.actions?.toList() ?: emptyList()
            )
            incomingVoipCall = call
            sendBroadcast(Intent("com.example.scifilauncher.INCOMING_VOIP_CALL").apply {
                putExtra("appName", appName)
                putExtra("callerName", call.callerName)
            })
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null) {
            replyableMap.remove(sbn.key)
            if (incomingVoipCall?.key == sbn.key) incomingVoipCall = null
        }
    }

    /** Accepts or declines the currently-ringing VoIP call via its own notification's real
     * action (the same PendingIntent tapping the on-screen Accept/Decline button would fire) -
     * not a guessed UI tap. Actions aren't guaranteed to come in a fixed order, so this matches
     * by the button's own visible label instead of position. */
    fun respondToVoipCall(accept: Boolean): Boolean {
        val call = incomingVoipCall ?: return false
        val keywords = if (accept) listOf("accept", "answer") else listOf("decline", "reject", "hang up", "end")
        val action = call.actions.firstOrNull { act ->
            val label = act.title?.toString()?.lowercase().orEmpty()
            keywords.any { label.contains(it) }
        } ?: return false
        return runCatching {
            action.actionIntent.send()
            incomingVoipCall = null
            true
        }.getOrDefault(false)
    }

    /** Fires a notification's own real action button (e.g. a call's "End call") - the exact
     * same PendingIntent tapping it in the system status bar would send. */
    fun fireAction(action: Notification.Action): Boolean = runCatching {
        action.actionIntent.send()
        true
    }.getOrDefault(false)

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
