package com.example.scifilauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat

/** Fires when a scheduled device action's alarm goes off - the app may well have been killed
 * in the hours since it was scheduled, so this executes directly from the receiver's own
 * context rather than assuming MainActivity (or any of its in-memory state) is still alive. */
class ScheduledActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id == -1L) return
        val action = ScheduledActions.loadAll(context).firstOrNull { it.id == id } ?: return
        ScheduledActions.remove(context, id)

        val resultText = when (action.type) {
            "uninstall" -> {
                runCatching {
                    val statusIntent = Intent(UNINSTALL_STATUS_ACTION).setPackage(context.packageName)
                    val pending = PendingIntent.getBroadcast(
                        context, action.id.toInt(), statusIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    context.packageManager.packageInstaller.uninstall(action.target, pending.intentSender)
                }.fold(
                    onSuccess = { "Uninstalling ${action.label} now." },
                    onFailure = { "Couldn't uninstall ${action.label}: ${it.message}" }
                )
            }
            "download_app" -> {
                val query = Uri.encode(action.target)
                val opened = runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$query&c=apps"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                }.getOrDefault(false)
                if (!opened) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$query&c=apps"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                "Opened the Play Store for \"${action.target}\" as scheduled."
            }
            else -> "Unknown scheduled action type: ${action.type}"
        }

        ActionLog.record(
            context,
            ActionRequestEntry(
                action.id, "Scheduled: ${action.label}", resultText, action.target,
                action.createdAtMillis, ActionRequestStatus.APPROVED, System.currentTimeMillis()
            )
        )
        notifyDone(context, action.label, resultText)
    }

    private fun notifyDone(context: Context, label: String, text: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Scheduled Actions", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Scheduled action ran: $label")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setAutoCancel(true)
            .build()
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "scheduled_actions"
    }
}
