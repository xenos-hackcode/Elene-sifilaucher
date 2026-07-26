package com.example.scifilauncher

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fires every 12h while Sequence Mode is active; stops itself after the 2-day window. */
class SequenceAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val lockPrefs = applicationContext.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)

        if (!isSequenceModeActive(lockPrefs)) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(SEQUENCE_ALERT_WORK_NAME)
            return Result.success()
        }

        if (isAlertWindowExpired(lockPrefs)) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(SEQUENCE_ALERT_WORK_NAME)
            return Result.success()
        }

        val message = buildAlertMessage(lockPrefs)

        withContext(Dispatchers.IO) {
            resolveFamilyContacts(applicationContext).forEach { contact ->
                sendWhatsAppAlert(applicationContext, contact.phoneNumber, message)
                val smsSent = sendSmsAlert(applicationContext, contact.phoneNumber, message)
                SystemEventLog.record(
                    applicationContext, "SequenceMode",
                    "Alert to ${contact.label}: WhatsApp attempted, SMS ${if (smsSent) "sent" else "failed/unavailable"}"
                )
            }
        }

        return Result.success()
    }
}

/** One-shot, ~30 days after Sequence Mode started - wipes protected app data if never recovered. */
class SequenceWipeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val lockPrefs = applicationContext.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
        if (!isSequenceModeActive(lockPrefs)) return Result.success()

        performSequenceWipe(applicationContext)
        return Result.success()
    }
}
