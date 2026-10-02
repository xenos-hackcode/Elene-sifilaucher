package com.example.scifilauncher

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Local reminder only. No inferred emergencies, remote commands, or outgoing messages. */
object SafetyCheckIn {
    private const val CHANNEL = "safety_check_in"
    private const val ID = 8142
    private fun prefs(context: Context) = context.getSharedPreferences("safety_check_in", Context.MODE_PRIVATE)
    private fun operation(context: Context) = PendingIntent.getBroadcast(
        context, ID, Intent(context, SafetyCheckInReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun bootCount(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun deadline(context: Context): Long {
        val state = prefs(context)
        if (state.getInt("boot_count", -1) != bootCount(context)) {
            state.edit().clear().commit()
            return 0
        }
        return state.getLong("deadline", 0)
    }

    fun prepare(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "Safety check-in reminders", NotificationManager.IMPORTANCE_HIGH
            ).apply { enableVibration(true) })
            if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun arm(context: Context, minutes: Int) {
        require(minutes in 1..1440) { "Choose 1 to 1440 minutes." }
        check(prepare(context)) { "Enable check-in notifications before arming." }
        val alarms = context.getSystemService(AlarmManager::class.java)
        check(Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            "Allow alarms and reminders in Android settings before arming."
        }
        val delay = minutes * 60_000L
        val elapsedDeadline = SystemClock.elapsedRealtime() + delay
        // Elapsed time avoids changes to the phone clock moving the actual timer.
        alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            elapsedDeadline, operation(context))
        val stored = prefs(context).edit().putLong("deadline", System.currentTimeMillis() + delay)
            .putInt("boot_count", bootCount(context))
            .putLong("elapsed_deadline", elapsedDeadline).commit()
        if (!stored) {
            alarms.cancel(operation(context))
            error("Could not save check-in. Timer was cancelled.")
        }
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(operation(context))
        prefs(context).edit().clear().commit()
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    fun notifyOverdue(context: Context) {
        if (deadline(context) == 0L) return
        val elapsedDeadline = prefs(context).getLong("elapsed_deadline", 0)
        if (elapsedDeadline == 0L || SystemClock.elapsedRealtime() < elapsedDeadline) return
        if (!prepare(context)) return
        val open = PendingIntent.getActivity(context, ID,
            Intent(context, SafetyToolsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Safety check-in is due")
            .setContentText("Open Safety tools to check in. No one has been contacted.")
            .setContentIntent(open).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(ID, notification)
        } catch (_: SecurityException) {
            // Permission may have been revoked since the timer was armed.
        }
    }
}

class SafetyCheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SafetyCheckIn.notifyOverdue(context)
    }
}
