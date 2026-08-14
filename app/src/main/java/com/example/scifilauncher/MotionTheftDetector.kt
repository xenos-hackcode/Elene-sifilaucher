package com.example.scifilauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

const val MOTION_CONFIRM_TIMEOUT_WORK_NAME = "motion_alert_confirm_timeout"

/**
 * A real, if imperfect, "did someone just grab this phone and run?" signal from the phone's own
 * accelerometer - no wearable/health sensor needed, and no such sensor exists on this phone
 * anyway (worth being upfront: a heart-rate-based version of this idea was considered and
 * dropped, since it would only ever read the *owner's* pulse, not a thief's, unless the thief
 * were also wearing the owner's watch, which defeats the whole scenario).
 *
 * Worth being honest about the limitation going in: a sudden burst of motion also happens when
 * genuinely running for exercise, riding in a car over bumps, or just picking the phone up
 * quickly - this is why detection here never arms Sequence Mode directly. It only starts a
 * confirm-or-arm countdown (see SequenceMode.kt's motion-alert state + SequenceWorkers.kt's
 * MotionConfirmTimeoutWorker), giving the real owner a real chance to say "I'm fine" via
 * fingerprint before anything actually locks down.
 */
object MotionTheftDetector : SensorEventListener {
    // Linear acceleration (gravity already removed by the OS) magnitude, m/s^2, treated as one
    // "peak". Original value (11) was picked blind and confirmed wrong via real on-device data -
    // a real vigorous hand-shake only produced a ~4 m/s^2 peak (dumpsys sensorservice showed the
    // raw samples), nowhere near 11. Lowered based on that real reading, with real baseline
    // "phone just sitting there" samples (~0.02-0.15 m/s^2) confirming there's still a wide
    // enough margin against false triggers from ordinary handling. Kept on the sensitive side
    // deliberately - a false trigger only costs a confirm-fingerprint tap, not a lockdown.
    private const val PEAK_THRESHOLD = 2.5f
    private val PEAK_WINDOW_MILLIS = TimeUnit.SECONDS.toMillis(5)
    private const val PEAKS_REQUIRED = 4 // sustained motion, not one jolt - confirmed against the same real shake data

    private var registeredContext: Context? = null
    private val recentPeaks = ArrayDeque<Long>()

    fun register(context: Context) {
        if (registeredContext != null) return
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: return
        runCatching {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            registeredContext = context.applicationContext
        }
    }

    fun unregister(context: Context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        runCatching { sensorManager.unregisterListener(this) }
        registeredContext = null
        recentPeaks.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val context = registeredContext ?: return
        val magnitude = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
        if (magnitude < PEAK_THRESHOLD) return

        val now = System.currentTimeMillis()
        recentPeaks.addLast(now)
        while (recentPeaks.isNotEmpty() && now - recentPeaks.first() > PEAK_WINDOW_MILLIS) {
            recentPeaks.removeFirst()
        }
        if (recentPeaks.size < PEAKS_REQUIRED) return

        recentPeaks.clear()
        onMotionSpikeDetected(context)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onMotionSpikeDetected(context: Context) {
        val lockPrefs = context.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
        // Anti-theft mode toggle: when OFF, Elene doesn't proactively alert on sudden motion
        // ("are you running?" / the "Are you OK?" notification / the confirm-or-arm countdown).
        // Manual lockdown, failed-fingerprint auto-arm, location tracking, and wipe still work.
        if (!isAntiTheftModeEnabled(lockPrefs)) return
        if (isSequenceModeActive(lockPrefs)) return
        if (isMotionAlertPending(lockPrefs)) return
        if (isMotionAlertOnCooldown(lockPrefs)) return

        startMotionAlert(lockPrefs)
        SystemEventLog.record(context, "SequenceMode", "Motion spike detected - confirm-or-arm countdown started")

        ScifiAccessibilityService.instance?.speakElene(
            "Xenos here. I noticed sudden movement - are you running, or is everything OK? " +
                "Confirm your fingerprint within 10 minutes, or I'll assume something's wrong."
        )
        showConfirmNotification(context)

        val work = OneTimeWorkRequestBuilder<MotionConfirmTimeoutWorker>()
            .setInitialDelay(MOTION_ALERT_CONFIRM_WINDOW_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            MOTION_CONFIRM_TIMEOUT_WORK_NAME, ExistingWorkPolicy.REPLACE, work
        )
    }

    private fun showConfirmNotification(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Motion Alert", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val confirmIntent = android.content.Intent(context, BiometricAuthActivity::class.java).apply {
            putExtra(BiometricAuthActivity.EXTRA_TITLE, "Confirm it's you")
            putExtra(BiometricAuthActivity.EXTRA_SUBTITLE, "Sudden movement detected - confirm you're OK")
            putExtra(BiometricAuthActivity.EXTRA_REASON, BiometricAuthActivity.REASON_MOTION_CONFIRM)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Are you OK?")
            .setContentText("Sudden movement detected. Tap to confirm it's you, or this locks down in 10 minutes.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Deliberately NOT setAutoCancel(true) - real bug found live: that dismissed the
            // notification the instant it was tapped, before the fingerprint prompt it opens
            // had actually succeeded. If that prompt gets cancelled or fails, the notification
            // was already gone with no way to retry from it. Only onConfirmed() (a real
            // successful fingerprint match) should ever remove this - see its own
            // NotificationManagerCompat.cancel() call below.
            .setContentIntent(pendingIntent)
            .build()
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(context).notify(MOTION_NOTIFICATION_ID, notification)
        }
    }

    fun onConfirmed(context: Context) {
        val lockPrefs = context.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
        resolveMotionAlert(lockPrefs)
        WorkManager.getInstance(context).cancelUniqueWork(MOTION_CONFIRM_TIMEOUT_WORK_NAME)
        SystemEventLog.record(context, "SequenceMode", "Motion alert confirmed by owner - false alarm")
        androidx.core.app.NotificationManagerCompat.from(context).cancel(MOTION_NOTIFICATION_ID)
    }

    /** Cancels any pending motion alert without arming - used when the user turns OFF the
     * Anti-theft mode toggle. Unlike [onConfirmed], this isn't a "false alarm" confirmation -
     * it's an explicit silencing of Elene's proactive motion alerts so she stops asking
     * "are you OK?" and won't push a state change (Standing by -> ACTIVE) the user disabled. */
    fun cancelPendingMotionAlert(context: Context) {
        val lockPrefs = context.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
        if (!isMotionAlertPending(lockPrefs)) return
        resolveMotionAlert(lockPrefs)
        WorkManager.getInstance(context).cancelUniqueWork(MOTION_CONFIRM_TIMEOUT_WORK_NAME)
        androidx.core.app.NotificationManagerCompat.from(context).cancel(MOTION_NOTIFICATION_ID)
        SystemEventLog.record(context, "SequenceMode", "Anti-theft mode turned off - pending motion alert cancelled")
    }

    private const val CHANNEL_ID = "motion_theft_alert"
    private const val MOTION_NOTIFICATION_ID = 4821
}
