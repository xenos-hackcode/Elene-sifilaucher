package com.example.scifilauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

/** Sensitive permissions worth a second look on a freshly-installed app - not a claim any
 * of these are malicious on their own (plenty of legitimate apps need them), just the same
 * things a careful person would glance at before trusting a new sideloaded app. */
private val WATCHED_PERMISSIONS = setOf(
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.BIND_DEVICE_ADMIN",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
    "android.permission.READ_SMS",
    "android.permission.SEND_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.CALL_PHONE",
    "android.permission.READ_CALL_LOG",
    "android.permission.RECORD_AUDIO",
    "android.permission.CAMERA",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.READ_CONTACTS",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.BIND_VPN_SERVICE"
)

// Deliberately NOT including "com.android.packageinstaller" / "com.google.android.packageinstaller"
// here - that's just the generic system install UI, and it mediates EVERY manual install
// (a web-downloaded APK, a file manager, anything tapped by hand), not only trustworthy ones.
// Including it would have quietly marked every sideloaded app as "not sideloaded" - only real
// app-store identities count as trusted here.
private val TRUSTED_INSTALLERS = setOf(
    "com.android.vending",
    "com.sec.android.app.samsungapps",
    "com.amazon.venezia"
)

class PackageInstallWatcher : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        // Only genuinely new installs - package replacement (app updates itself) isn't a
        // new-install security event.
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return

        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == context.packageName) return

        val watchEnabled = context.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
            .getBoolean("install_watch_enabled", true)
        if (!watchEnabled) return

        val pm = context.packageManager
        val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return
        val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        if (isSystemApp) return

        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)

        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
            }
        }.getOrNull()
        val sideloaded = installer == null || installer !in TRUSTED_INSTALLERS

        val requestedPerms = runCatching {
            pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
        }.getOrNull().orEmpty()
        val flaggedPerms = requestedPerms.filter { it in WATCHED_PERMISSIONS }

        val entry = InstallFlagEntry(
            timestamp = System.currentTimeMillis(),
            packageName = pkg,
            appLabel = label,
            sideloaded = sideloaded,
            flaggedPermissions = flaggedPerms
        )
        InstallFlags.record(context, entry)

        if (entry.isFlagged) {
            notifyFlagged(context, entry)
        }
    }

    private fun notifyFlagged(context: Context, entry: InstallFlagEntry) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "New App Alerts", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val bits = mutableListOf<String>()
        if (entry.sideloaded) bits.add("sideloaded (not from an app store)")
        if (entry.flaggedPermissions.isNotEmpty()) {
            bits.add("asks for ${entry.flaggedPermissions.size} sensitive permission(s)")
        }
        val text = "${entry.appLabel}: ${bits.joinToString(", ")}"

        val openIntent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, entry.timestamp.toInt(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("New app installed")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        runCatching {
            androidx.core.app.NotificationManagerCompat.from(context)
                .notify(entry.timestamp.toInt(), notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "install_flags"
    }
}
