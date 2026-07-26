package com.example.scifilauncher

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Runs every ~15 minutes (WorkManager's own floor for periodic work - it won't go shorter,
 * by OS design, to keep this from being a battery drain) while Location History is enabled,
 * recording whatever the location subsystem currently has rather than forcing a fresh GPS fix
 * each time - good enough for a history trail, not worth the battery cost of an active fix
 * every single run. */
class LocationHistoryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val hasPermission = ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return Result.success()

        val enabled = applicationContext.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
            .getBoolean("location_history_enabled", false)
        if (!enabled) return Result.success()

        val lm = applicationContext.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return Result.success()
        val fix = runCatching {
            lm.getProviders(true)
                .mapNotNull { provider -> lm.getLastKnownLocation(provider) }
                .maxByOrNull { it.time }
        }.getOrNull()

        if (fix != null) {
            LocationHistory.record(applicationContext, LocationEntry(System.currentTimeMillis(), fix.latitude, fix.longitude))
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "location_history_worker"

        fun start(context: Context) {
            val request = PeriodicWorkRequestBuilder<LocationHistoryWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
